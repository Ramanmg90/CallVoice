package com.example.callvoice

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.util.UUID

/** اتصال BLE به گجت ESP32 و فرستادن اسم تماس‌گیرنده. */
@SuppressLint("MissingPermission")
class GadgetClient(private val ctx: Context) {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("7a1e0001-6d3b-4f5a-9c10-52b0c0ca11ed")
        val CHAR_UUID: UUID = UUID.fromString("7a1e0002-6d3b-4f5a-9c10-52b0c0ca11ed")
        @Volatile var state: String = "Off"
    }

    private val adapter: BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val prefs = ctx.getSharedPreferences("gadget", Context.MODE_PRIVATE)
    private val h = Handler(Looper.getMainLooper())

    private var gatt: BluetoothGatt? = null
    private var ch: BluetoothGattCharacteristic? = null
    private var scanning = false
    private var stopped = false
    private var payload = 14

    private val queue = ArrayDeque<ByteArray>()
    private var busy = false

    private fun canUse(): Boolean {
        val a = adapter
        if (a == null || !a.isEnabled) return false
        return if (Build.VERSION.SDK_INT >= 31)
            ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        else ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    fun start() {
        stopped = false
        if (gatt != null || scanning) return
        if (!canUse()) { state = "BT unavailable"; return }
        val addr = prefs.getString("addr", null)
        if (addr == null) scan() else connect(adapter!!.getRemoteDevice(addr))
    }

    /** آدرس ذخیره‌شده را فراموش می‌کند و دوباره دنبال گجت می‌گردد. */
    fun rescan() {
        stopScan()
        val g = gatt
        gatt = null
        ch = null
        g?.close()
        prefs.edit().remove("addr").apply()
        state = "Off"
        start()
    }

    fun stop() {
        stopped = true
        stopScan()
        val g = gatt
        gatt = null
        ch = null
        g?.disconnect()
        g?.close()
        state = "Off"
    }

    private fun scan() {
        val scanner = adapter?.bluetoothLeScanner ?: run { state = "BT unavailable"; return }
        scanning = true
        state = "Searching…"
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, scanCb)
        h.postDelayed({
            if (scanning) { stopScan(); if (state == "Searching…") state = "Not found" }
        }, 20000)
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try { adapter?.bluetoothLeScanner?.stopScan(scanCb) } catch (_: Exception) {}
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            stopScan()
            prefs.edit().putString("addr", result.device.address).apply()
            connect(result.device)
        }
        override fun onScanFailed(errorCode: Int) {
            scanning = false
            state = "Scan failed"
        }
    }

    private fun connect(dev: BluetoothDevice) {
        state = "Connecting…"
        gatt = dev.connectGatt(ctx, true, cb, BluetoothDevice.TRANSPORT_LE)
    }

    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                state = "Connecting…"
                if (!g.requestMtu(247)) g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                ch = null
                synchronized(queue) { queue.clear(); busy = false }
                g.close()
                if (gatt === g) gatt = null
                if (!stopped) {
                    state = "Reconnecting…"
                    h.postDelayed({ if (!stopped) start() }, 3000)
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            payload = (mtu - 6).coerceIn(14, 240)
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            ch = g.getService(SERVICE_UUID)?.getCharacteristic(CHAR_UUID)
            state = if (ch != null) "Connected" else "Wrong device"
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int
        ) {
            synchronized(queue) { busy = false }
            sendNext()
        }
    }

    // ---------- ارسال ----------
    fun showCaller(text: String) {
        if (state != "Connected") { start(); return }
        val bytes = GadgetRenderer.render("INCOMING CALL", text)
        synchronized(queue) { queue.clear() }
        var off = 0
        while (off < bytes.size) {
            val n = minOf(payload, bytes.size - off)
            val pkt = ByteArray(n + 3)
            pkt[0] = 1
            pkt[1] = (off shr 8).toByte()
            pkt[2] = (off and 0xFF).toByte()
            System.arraycopy(bytes, off, pkt, 3, n)
            enqueue(pkt)
            off += n
        }
        enqueue(byteArrayOf(3))
    }

    fun clear() {
        if (state != "Connected") return
        synchronized(queue) { queue.clear() }
        enqueue(byteArrayOf(2))
    }

    private fun enqueue(b: ByteArray) {
        synchronized(queue) { queue.addLast(b) }
        sendNext()
    }

    @Suppress("DEPRECATION")
    private fun sendNext() {
        val g = gatt ?: return
        val c = ch ?: return
        val next = synchronized(queue) {
            if (busy) return
            val n = queue.removeFirstOrNull() ?: return
            busy = true
            n
        }
        c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        c.value = next
        if (!g.writeCharacteristic(c)) {
            synchronized(queue) { busy = false; queue.clear() }
        }
    }
}

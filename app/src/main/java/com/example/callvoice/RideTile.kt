package com.example.callvoice

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** دکمه‌ی «Ride Mode» در پنل تنظیمات سریع (کنار وای‌فای و بلوتوث). */
class RideTile : TileService() {

    override fun onStartListening() {
        setTile(CallService.running)
    }

    override fun onClick() {
        if (CallService.running) {
            stopService(Intent(this, CallService::class.java))
            setTile(false)
        } else if (hasAllPerms()) {
            try {
                startForegroundService(Intent(this, CallService::class.java))
                setTile(true)
            } catch (e: Exception) {
                openApp()
            }
        } else {
            openApp()
        }
    }

    private fun setTile(on: Boolean) {
        val t = qsTile ?: return
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "Ride Mode"
        t.icon = Icon.createWithResource(this, R.drawable.ic_ride)
        if (Build.VERSION.SDK_INT >= 29) t.subtitle = if (on) "On" else "Off"
        t.updateTile()
    }

    private fun openApp() {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE)
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(i)
        }
    }

    private fun hasAllPerms(): Boolean {
        val l = mutableListOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE
        )
        if (Build.VERSION.SDK_INT >= 31) {
            l += Manifest.permission.BLUETOOTH_CONNECT
            l += Manifest.permission.BLUETOOTH_SCAN
        } else {
            l += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33) l += Manifest.permission.POST_NOTIFICATIONS
        return l.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }
}

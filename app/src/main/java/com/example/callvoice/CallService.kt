package com.example.callvoice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.ContactsContract
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class CallService : Service(), RecognitionListener {

    companion object {
        @Volatile var running = false
        @Volatile var modelReady = false
    }

    internal val handler = Handler(Looper.getMainLooper())
    internal lateinit var tts: TextToSpeech
    private var ttsReady = false
    internal var model: Model? = null
    internal var inCall = false
    internal lateinit var assistant: Assistant
    private var speech: SpeechService? = null
    private lateinit var audio: AudioManager

    private var ringing = false
    private var lastNumber: String? = null
    private var acted = false
    private lateinit var gadget: GadgetClient
    private var scoOn = false
    private var scoReceiver: BroadcastReceiver? = null
    private var savedVoiceVol = -1

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val state = i.getStringExtra(TelephonyManager.EXTRA_STATE)
            @Suppress("DEPRECATION")
            val num = i.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            if (num != null) lastNumber = num
            when (state) {
                TelephonyManager.EXTRA_STATE_RINGING -> {
                    if (!ringing) {
                        ringing = true
                        acted = false
                        inCall = true
                        assistant.pause()
                        // گاهی شماره با broadcast دوم میاد؛ کمی صبر می‌کنیم
                        handler.postDelayed({ announce() }, 600)
                    }
                }
                else -> { // IDLE یا OFFHOOK
                    ringing = false
                    lastNumber = null
                    stopListening()
                    closeBluetoothAudio()
                    gadget.clear()
                    if (state == TelephonyManager.EXTRA_STATE_OFFHOOK) {
                        inCall = true
                        assistant.pause()
                    } else {
                        inCall = false
                        handler.postDelayed({ assistant.resume() }, 1500)
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        running = true
        gadget = GadgetClient(this)
        assistant = Assistant(this)
        gadget.start()
        startInForeground()

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.US
                tts.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                ttsReady = true
            }
        }

        Thread { loadModel() }.start()

        val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else registerReceiver(receiver, filter)
    }

    private fun startInForeground() {
        val chId = "callvoice"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(chId, "RoboCall", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, chId)
            .setContentTitle("RoboCall is active")
            .setContentText("Waiting for incoming calls")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(1, n)
    }

    // مدل Vosk از assets/model به حافظه‌ی اپ کپی می‌شود (فقط بار اول)
    private fun loadModel() {
        try {
            val target = File(filesDir, "model")
            if (!File(target, "am").exists()) copyAsset("model", target)
            model = Model(target.absolutePath)
            modelReady = true
            handler.post { if (Store.assistantOn(this)) assistant.start() }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun copyAsset(path: String, dest: File) {
        val list = assets.list(path) ?: return
        if (list.isEmpty()) {
            dest.parentFile?.mkdirs()
            assets.open(path).use { i -> FileOutputStream(dest).use { o -> i.copyTo(o) } }
        } else {
            dest.mkdirs()
            for (f in list) copyAsset("$path/$f", File(dest, f))
        }
    }

    private fun lookupName(number: String?): String? {
        if (number.isNullOrBlank()) return null
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)
            )
            contentResolver.query(
                uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null
            )?.use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (e: Exception) { null }
    }

    private fun announce() {
        if (!ringing) return
        val contact = lookupName(lastNumber)
        gadget.showCaller(contact ?: lastNumber ?: "Unknown")
        val spoken = contact
            ?: lastNumber?.toCharArray()?.joinToString(" ")
            ?: "unknown number"
        openBluetoothAudio {
            if (!ringing) return@openBluetoothAudio
            if (!ttsReady) { startListening(); return@openBluetoothAudio }
            val name = spoken

            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onError(id: String?) { handler.post { startListening() } }
                override fun onDone(id: String?) { handler.post { startListening() } }
            })
            tts.speak("$name is calling", TextToSpeech.QUEUE_FLUSH, null, "caller")
        }
    }

    // قبل از حرف زدن، مسیر صدا را به ایرپاد (بلوتوث SCO) می‌بریم
    private fun openBluetoothAudio(onReady: () -> Unit) {
        if (scoOn) { onReady(); return }
        scoOn = true
        try {
            savedVoiceVol = audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
            audio.setStreamVolume(
                AudioManager.STREAM_VOICE_CALL,
                audio.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL), 0
            )
        } catch (_: Exception) {}

        var done = false
        val finish = { if (!done) { done = true; handler.post(onReady) } }

        if (Build.VERSION.SDK_INT >= 31) {
            val dev = audio.availableCommunicationDevices
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            if (dev != null) audio.setCommunicationDevice(dev)
            handler.postDelayed({ finish() }, 1200)
        } else {
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    val st = i.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                    if (st == AudioManager.SCO_AUDIO_STATE_CONNECTED) finish()
                }
            }
            scoReceiver = r
            registerReceiver(r, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
            audio.startBluetoothSco()
            @Suppress("DEPRECATION")
            audio.isBluetoothScoOn = true
            handler.postDelayed({ finish() }, 2000)
        }
    }

    internal fun closeBluetoothAudio(force: Boolean = false) {
        if (!scoOn) return
        if (!force && assistant.holdsAudio) return
        scoOn = false
        try {
            scoReceiver?.let { unregisterReceiver(it) }
            scoReceiver = null
            if (Build.VERSION.SDK_INT >= 31) audio.clearCommunicationDevice()
            else {
                audio.stopBluetoothSco()
                @Suppress("DEPRECATION")
                audio.isBluetoothScoOn = false
            }
            if (savedVoiceVol >= 0)
                audio.setStreamVolume(AudioManager.STREAM_VOICE_CALL, savedVoiceVol, 0)
        } catch (_: Exception) {}
    }

    private fun startListening() {
        val m = model
        if (!ringing || m == null || speech != null) return
        try {
            val words = Config.ANSWER_WORDS + Config.DECLINE_WORDS
            val grammar = words.joinToString(",", "[", ",\"[unk]\"]") { "\"$it\"" }
            val rec = Recognizer(m, 16000f, grammar)
            speech = SpeechService(rec, 16000f).also {
                it.startListening(this, Config.LISTEN_TIMEOUT_MS)
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun stopListening() {
        speech?.stop()
        speech?.shutdown()
        speech = null
    }

    private fun handleText(text: String) {
        if (acted || !ringing) return
        val t = text.lowercase()
        val tm = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        try {
            when {
                Config.ANSWER_WORDS.any { t.contains(it) } -> {
                    acted = true
                    tm.acceptRingingCall()
                }
                Config.DECLINE_WORDS.any { t.contains(it) } -> {
                    acted = true
                    @Suppress("DEPRECATION")
                    tm.endCall()
                }
            }
        } catch (e: SecurityException) { e.printStackTrace() }
        if (acted) handler.post { stopListening(); closeBluetoothAudio() }
    }

    // --- RecognitionListener ---
    override fun onPartialResult(hypothesis: String?) {
        hypothesis ?: return
        handleText(JSONObject(hypothesis).optString("partial"))
    }
    override fun onResult(hypothesis: String?) {
        hypothesis ?: return
        handleText(JSONObject(hypothesis).optString("text"))
    }
    override fun onFinalResult(hypothesis: String?) {
        hypothesis ?: return
        handleText(JSONObject(hypothesis).optString("text"))
    }
    override fun onError(e: Exception?) { e?.printStackTrace() }
    override fun onTimeout() { handler.post { stopListening(); closeBluetoothAudio() } }

    override fun onDestroy() {
        running = false
        modelReady = false
        unregisterReceiver(receiver)
        stopListening()
        assistant.stop()
        closeBluetoothAudio(true)
        gadget.stop()
        tts.shutdown()
        super.onDestroy()
    }

    // ---------- کمک برای دستیار ----------
    internal fun openAudio(cb: () -> Unit) { openBluetoothAudio(cb) }

    internal fun reopenAudio(cb: () -> Unit) {
        closeBluetoothAudio(true)
        openBluetoothAudio(cb)
    }

    internal fun speak(text: String, done: () -> Unit) {
        if (!ttsReady) { done(); return }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onError(id: String?) { handler.post { done() } }
            override fun onDone(id: String?) { handler.post { done() } }
        })
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "assist")
    }

    internal fun stopSpeaking() {
        try { tts.stop() } catch (_: Exception) {}
    }

    internal fun placeCall(number: String) {
        try {
            val tm = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            tm.placeCall(Uri.fromParts("tel", number, null), Bundle())
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "RESCAN" -> gadget.rescan()
            "ASSISTANT_ON" -> if (model != null) assistant.start()
            "ASSISTANT_OFF" -> {
                assistant.stop()
                closeBluetoothAudio(true)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

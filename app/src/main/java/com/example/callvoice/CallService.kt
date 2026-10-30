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
import android.media.AudioManager
import android.net.Uri
import android.os.Build
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

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private var model: Model? = null
    private var speech: SpeechService? = null
    private lateinit var audio: AudioManager

    private var ringing = false
    private var lastNumber: String? = null
    private var acted = false

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
                        // گاهی شماره با broadcast دوم میاد؛ کمی صبر می‌کنیم
                        handler.postDelayed({ announce() }, 600)
                    }
                }
                else -> { // IDLE یا OFFHOOK
                    ringing = false
                    lastNumber = null
                    stopListening()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        startInForeground()

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.getDefault()
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
            NotificationChannel(chId, "CallVoice", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, chId)
            .setContentTitle("CallVoice فعال است")
            .setContentText("منتظر تماس ورودی")
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
        if (!ringing || !ttsReady) { startListening(); return }
        val name = lookupName(lastNumber)
            ?: lastNumber?.toCharArray()?.joinToString(" ")
            ?: "unknown number"

        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onError(id: String?) { handler.post { startListening() } }
            override fun onDone(id: String?) { handler.post { startListening() } }
        })
        tts.speak("$name is calling", TextToSpeech.QUEUE_FLUSH, null, "caller")
    }

    private fun startListening() {
        val m = model
        if (!ringing || m == null || speech != null) return
        try {
            audio.startBluetoothSco()
            @Suppress("DEPRECATION")
            audio.isBluetoothScoOn = true

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
        try {
            audio.stopBluetoothSco()
            @Suppress("DEPRECATION")
            audio.isBluetoothScoOn = false
        } catch (_: Exception) {}
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
        if (acted) handler.post { stopListening() }
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
    override fun onTimeout() { handler.post { stopListening() } }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        stopListening()
        tts.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

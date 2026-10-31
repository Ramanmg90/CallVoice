package com.example.callvoice

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/**
 * دستیار صوتی انگلیسی:
 *  "hey robo" -> "Yes sir" -> "call my father" -> "Do you want to call father?"
 *  "yes" -> تماس | "no" -> "Please say the command once again."
 *  "bye robo" -> "Bye sir" و برگشت به خواب
 */
class Assistant(private val svc: CallService) : RecognitionListener {

    private enum class S { SLEEP, COMMAND, CONFIRM }

    @Volatile
    var enabled = false
        private set
    val holdsAudio: Boolean get() = enabled

    private var paused = true
    private var busy = false
    private var state = S.SLEEP
    private var speech: SpeechService? = null
    private var pending: Contact? = null
    private val h = Handler(Looper.getMainLooper())

    fun start() {
        enabled = true
        paused = false
        svc.openAudio { listenIfActive(S.SLEEP) }
    }

    fun pause() {
        paused = true
        busy = false
        h.removeCallbacksAndMessages(null)
        stopListening()
        svc.stopSpeaking()
    }

    fun resume() {
        if (!enabled) return
        paused = false
        svc.reopenAudio { listenIfActive(S.SLEEP) }
    }

    fun stop() {
        enabled = false
        paused = true
        h.removeCallbacksAndMessages(null)
        stopListening()
        svc.stopSpeaking()
    }

    private fun listenIfActive(s: S) {
        if (enabled && !paused) listen(s)
    }

    private fun grammar(vararg phrases: String) =
        phrases.joinToString(",", "[", "]") { "\"$it\"" }

    private fun listen(s: S) {
        val m = svc.model ?: return
        stopListening()
        state = s
        busy = false
        val g = when (s) {
            S.SLEEP -> grammar(Config.WAKE, "[unk]")
            S.CONFIRM -> grammar("yes", "no", Config.BYE, "[unk]")
            S.COMMAND -> {
                val list = mutableListOf<String>()
                Store.contacts(svc).forEach {
                    list.add("call my ${it.name}")
                    list.add("call ${it.name}")
                }
                list.add(Config.BYE)
                list.add("[unk]")
                grammar(*list.toTypedArray())
            }
        }
        try {
            val rec = Recognizer(m, 16000f, g)
            rec.setWords(true)
            val sp = SpeechService(rec, 16000f)
            speech = sp
            if (s == S.SLEEP) sp.startListening(this)
            else sp.startListening(this, Config.COMMAND_TIMEOUT_MS)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopListening() {
        try {
            speech?.cancel()
            speech?.shutdown()
        } catch (_: Exception) {}
        speech = null
    }

    private fun isWake(t: String) = t.contains("hey") && t.contains("robo")
    private fun isBye(t: String) = t.contains("bye") && t.contains("robo")

    private fun confidence(o: JSONObject): Double {
        val arr = o.optJSONArray("result") ?: return 1.0
        if (arr.length() == 0) return 1.0
        var sum = 0.0
        for (i in 0 until arr.length()) sum += arr.getJSONObject(i).optDouble("conf", 1.0)
        return sum / arr.length()
    }

    private fun handle(hyp: String?) {
        if (hyp == null || paused || busy || !enabled) return
        val o = try { JSONObject(hyp) } catch (_: Exception) { return }
        val t = o.optString("text").replace("[unk]", "").trim().lowercase()
        if (t.isEmpty()) return

        when (state) {
            S.SLEEP -> {
                if (isWake(t) && confidence(o) >= Config.WAKE_MIN_CONF) {
                    busy = true
                    stopListening()
                    svc.speak("Yes sir.") { listenIfActive(S.COMMAND) }
                }
            }
            S.COMMAND -> {
                if (isBye(t)) { bye(); return }
                if (!t.contains("call")) return
                val c = Store.contacts(svc)
                    .sortedByDescending { it.name.length }
                    .firstOrNull { t.contains(it.name) }
                busy = true
                stopListening()
                if (c != null) {
                    pending = c
                    svc.speak("Do you want to call ${c.name}?") { listenIfActive(S.CONFIRM) }
                } else {
                    svc.speak("Sorry sir, I can only call your saved contacts. Please say it again.") {
                        listenIfActive(S.COMMAND)
                    }
                }
            }
            S.CONFIRM -> {
                if (isBye(t)) { bye(); return }
                val first = t.split(" ").first()
                if (first == "yes") {
                    val c = pending ?: return
                    busy = true
                    stopListening()
                    svc.speak("Calling ${c.name}.") {
                        svc.placeCall(c.number)
                        // اگر تماس شروع نشد، بعد از چند ثانیه دوباره گوش بده
                        h.postDelayed({
                            if (!svc.inCall) listenIfActive(S.SLEEP)
                        }, 10_000)
                    }
                } else if (first == "no") {
                    busy = true
                    stopListening()
                    svc.speak("Please say the command once again.") { listenIfActive(S.COMMAND) }
                }
            }
        }
    }

    private fun bye() {
        busy = true
        stopListening()
        svc.speak("Bye sir.") { listenIfActive(S.SLEEP) }
    }

    // --- RecognitionListener ---
    override fun onPartialResult(hypothesis: String?) {}
    override fun onResult(hypothesis: String?) { handle(hypothesis) }
    override fun onFinalResult(hypothesis: String?) { handle(hypothesis) }
    override fun onError(e: Exception?) { e?.printStackTrace() }
    override fun onTimeout() {
        if (state != S.SLEEP && !busy) listenIfActive(S.SLEEP)
    }
}

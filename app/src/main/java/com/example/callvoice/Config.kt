package com.example.callvoice

object Config {
    // --- جواب/رد کردن تماس ورودی ---
    val ANSWER_WORDS = listOf("answer")
    val DECLINE_WORDS = listOf("reject", "decline")
    const val LISTEN_TIMEOUT_MS = 25_000

    // --- دستیار صوتی ---
    // مدل کوچک Vosk احتمالاً کلمه‌ی "robo" را ندارد، پس "robot" در گرامر است.
    // اگر بگویی "hey robo" معمولاً همین را تشخیص می‌دهد.
    const val WAKE = "hey robot"
    const val BYE = "bye robot"
    const val COMMAND_TIMEOUT_MS = 10_000
    const val WAKE_MIN_CONF = 0.4   // اگر زیاد بیدار نمی‌شود کمترش کن، اگر زیاد اشتباه بیدار می‌شود بیشترش کن
}

package com.example.callvoice

object Config {
    // کلمه‌هایی که برای جواب دادن / رد کردن می‌شناسیم (مدل انگلیسی Vosk)
    val ANSWER_WORDS = listOf("answer")
    val DECLINE_WORDS = listOf("reject", "decline")

    // چند ثانیه بعد از گفتن اسم، به صدای تو گوش بده
    const val LISTEN_TIMEOUT_MS = 25_000
}

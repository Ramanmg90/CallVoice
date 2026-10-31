package com.example.callvoice

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

/** اسم را روی یک تصویر ۱۲۸x۶۴ تک‌رنگ می‌کشد (هر زبانی، از جمله فارسی). */
object GadgetRenderer {
    const val W = 128
    const val H = 64
    private const val AREA_TOP = 17
    private const val AREA_H = 46

    fun render(header: String, text: String): ByteArray {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.BLACK)

        val hp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 9f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.12f
        }
        c.drawText(header, (W - hp.measureText(header)) / 2f, 9f, hp)
        val line = Paint().apply { color = Color.WHITE; strokeWidth = 1f }
        c.drawLine(0f, 13f, W.toFloat(), 13f, line)

        val np = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.DEFAULT_BOLD
        }
        var size = 30f
        var layout: StaticLayout
        do {
            np.textSize = size
            layout = StaticLayout.Builder.obtain(text, 0, text.length, np, W - 4)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .build()
            if (layout.height <= AREA_H && layout.lineCount <= 2) break
            size -= 1f
        } while (size >= 11f)

        c.save()
        c.translate(2f, AREA_TOP + (AREA_H - layout.height) / 2f)
        layout.draw(c)
        c.restore()

        // تبدیل به بیت: ردیف‌به‌ردیف، MSB اول (مثل drawBitmap کتابخانه‌ی Adafruit)
        val px = IntArray(W * H)
        bmp.getPixels(px, 0, W, 0, 0, W, H)
        val out = ByteArray(W * H / 8)
        for (y in 0 until H) {
            for (x in 0 until W) {
                val p = px[y * W + x]
                val lum = (Color.red(p) + Color.green(p) + Color.blue(p)) / 3
                if (lum > 90) {
                    val i = y * (W / 8) + x / 8
                    out[i] = (out[i].toInt() or (0x80 shr (x % 8))).toByte()
                }
            }
        }
        return out
    }
}

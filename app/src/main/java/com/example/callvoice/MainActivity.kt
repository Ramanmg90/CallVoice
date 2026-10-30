package com.example.callvoice

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private val perms: Array<String>
        get() {
            val l = mutableListOf(
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.READ_CALL_LOG,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.ANSWER_PHONE_CALLS,
                Manifest.permission.RECORD_AUDIO
            )
            if (Build.VERSION.SDK_INT >= 31) l += Manifest.permission.BLUETOOTH_CONNECT
            if (Build.VERSION.SDK_INT >= 33) l += Manifest.permission.POST_NOTIFICATIONS
            return l.toTypedArray()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }
        val status = TextView(this).apply {
            text = "برای شروع، مجوزها را بده و سرویس را روشن کن."
            textSize = 16f
        }
        val start = Button(this).apply { text = "شروع سرویس" }
        val stop = Button(this).apply { text = "توقف سرویس" }
        layout.addView(status); layout.addView(start); layout.addView(stop)
        setContentView(layout)

        start.setOnClickListener {
            if (perms.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
                startForegroundService(Intent(this, CallService::class.java))
                status.text = "سرویس روشن است. صفحه را می‌توانی ببندی."
            } else {
                requestPermissions(perms, 1)
            }
        }
        stop.setOnClickListener {
            stopService(Intent(this, CallService::class.java))
            status.text = "سرویس خاموش شد."
        }
    }
}

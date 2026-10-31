package com.example.callvoice

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    // ---------- رنگ‌ها ----------
    private val BG_TOP = Color.parseColor("#060A14")
    private val BG_BOTTOM = Color.parseColor("#131C36")
    private val CARD = Color.parseColor("#1A2340")
    private val ACCENT = Color.parseColor("#00E5FF")
    private val GREEN = Color.parseColor("#00E676")
    private val RED = Color.parseColor("#FF5252")
    private val TEXT = Color.parseColor("#EAF0FF")
    private val MUTED = Color.parseColor("#8A96B8")

    private val h = Handler(Looper.getMainLooper())

    private lateinit var ring1: View
    private lateinit var ring2: View
    private lateinit var powerBtn: LinearLayout
    private lateinit var btnIcon: TextView
    private lateinit var btnLabel: TextView
    private lateinit var pill: TextView
    private lateinit var desc: TextView
    private lateinit var permValue: TextView
    private lateinit var modelValue: TextView
    private lateinit var serviceValue: TextView
    private lateinit var gadgetValue: TextView
    private lateinit var assistantBtn: TextView
    private lateinit var contactsCard: LinearLayout
    private lateinit var contactsList: LinearLayout
    private lateinit var nameEdit: EditText
    private lateinit var numberEdit: EditText

    private var pulse1: ObjectAnimator? = null
    private var pulse2: ObjectAnimator? = null
    private var pulsing = false

    private val perms: Array<String>
        get() {
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
            return l.toTypedArray()
        }

    private val poll = object : Runnable {
        override fun run() {
            refresh()
            h.postDelayed(this, 1000)
        }
    }

    // ---------- کمکی‌ها ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun tint(color: Int, a: Int) = (color and 0x00FFFFFF) or (a shl 24)

    private fun bg(
        fill: Int, radiusDp: Int = 0, oval: Boolean = false,
        strokeColor: Int = 0, strokeDp: Int = 0
    ) = GradientDrawable().apply {
        if (oval) setShape(GradientDrawable.OVAL) else cornerRadius = dp(radiusDp).toFloat()
        setColor(fill)
        if (strokeDp > 0) setStroke(dp(strokeDp), strokeColor)
    }

    private fun tv(text: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun hasPerms() = perms.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    // ---------- UI ----------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG_TOP
        window.navigationBarColor = BG_BOTTOM

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(BG_TOP, BG_BOTTOM)
            )
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(44), dp(20), dp(28))
        }
        scroll.addView(
            col, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        // --- هدر ---
        val badge = tv("🤖", 28f, TEXT).apply {
            gravity = Gravity.CENTER
            background = bg(tint(ACCENT, 40), oval = true, strokeColor = tint(ACCENT, 160), strokeDp = 1)
        }
        col.addView(badge, LinearLayout.LayoutParams(dp(64), dp(64)))
        col.addView(tv("RoboCall", 34f, TEXT, true).apply {
            letterSpacing = 0.04f
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
        col.addView(tv("BY ROBORAM", 12f, ACCENT, true).apply {
            letterSpacing = 0.35f
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })

        // --- دکمه‌ی اصلی ---
        val stage = FrameLayout(this)
        col.addView(stage, LinearLayout.LayoutParams(dp(270), dp(270)).apply { topMargin = dp(26) })

        ring1 = View(this).apply {
            background = bg(Color.TRANSPARENT, oval = true, strokeColor = ACCENT, strokeDp = 3)
            alpha = 0f
        }
        ring2 = View(this).apply {
            background = bg(Color.TRANSPARENT, oval = true, strokeColor = GREEN, strokeDp = 2)
            alpha = 0f
        }
        stage.addView(ring1, FrameLayout.LayoutParams(dp(170), dp(170), Gravity.CENTER))
        stage.addView(ring2, FrameLayout.LayoutParams(dp(170), dp(170), Gravity.CENTER))

        btnIcon = tv("▶", 46f, TEXT).apply { gravity = Gravity.CENTER }
        btnLabel = tv("START", 14f, TEXT, true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.25f
        }
        powerBtn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            elevation = dp(10).toFloat()
            addView(btnIcon)
            addView(btnLabel)
            setOnClickListener { v ->
                v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(90).withEndAction {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                }.start()
                toggle()
            }
        }
        stage.addView(powerBtn, FrameLayout.LayoutParams(dp(170), dp(170), Gravity.CENTER))

        // --- وضعیت ---
        pill = tv("● OFF", 13f, MUTED, true).apply {
            letterSpacing = 0.15f
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(7), dp(18), dp(7))
        }
        col.addView(pill, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
        desc = tv("Tap the button to start.", 14f, MUTED).apply { gravity = Gravity.CENTER }
        col.addView(desc, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })

        // --- کارت دستورهای صوتی ---
        val cmds = card("VOICE COMMANDS")
        cmds.addView(commandRow("✓", GREEN, "Say  “answer”", "Picks up the call"))
        cmds.addView(commandRow("✕", RED, "Say  “reject”", "Declines the call").apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(14)
        })
        col.addView(cmds, cardParams())

        // --- کارت بررسی سیستم ---
        val sys = card("SYSTEM CHECK")
        serviceValue = tv("", 14f, MUTED, true)
        permValue = tv("", 14f, MUTED, true)
        modelValue = tv("", 14f, MUTED, true)
        sys.addView(checkRow("Service", serviceValue))
        sys.addView(checkRow("Permissions", permValue))
        sys.addView(checkRow("Voice model", modelValue))
        col.addView(sys, cardParams())

        // --- کارت گجت ---
        val gad = card("GADGET")
        gadgetValue = tv("", 14f, MUTED, true)
        gad.addView(checkRow("OLED display", gadgetValue))
        val search = tv("Search for gadget", 13f, ACCENT, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = bg(Color.TRANSPARENT, 14, strokeColor = tint(ACCENT, 120), strokeDp = 1)
            setOnClickListener {
                if (CallService.running) {
                    startService(Intent(this@MainActivity, CallService::class.java).setAction("RESCAN"))
                }
            }
        }
        gad.addView(search, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        col.addView(gad, cardParams())

        // --- کارت دستیار صوتی ---
        val ast = card("VOICE ASSISTANT")
        assistantBtn = tv("", 15f, TEXT, true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.08f
            setPadding(dp(12), dp(14), dp(12), dp(14))
            setOnClickListener { toggleAssistant() }
        }
        ast.addView(assistantBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        ast.addView(
            tv("Say “Hey Robo”, then “Call my father”.\nAnswer “Yes” or “No”. Say “Bye Robo” to stop.", 13f, MUTED),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) }
        )
        col.addView(ast, cardParams())

        // --- کارت مخاطبین ---
        contactsCard = card("CONTACTS")
        contactsList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        contactsCard.addView(contactsList)
        nameEdit = field("Name in English (e.g. father)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        numberEdit = field("Phone number", InputType.TYPE_CLASS_PHONE)
        contactsCard.addView(nameEdit, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
        contactsCard.addView(numberEdit, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        val addBtn = tv("Add contact", 14f, ACCENT, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = bg(Color.TRANSPARENT, 14, strokeColor = tint(ACCENT, 120), strokeDp = 1)
            setOnClickListener {
                val err = Store.addContact(
                    this@MainActivity, nameEdit.text.toString(), numberEdit.text.toString()
                )
                if (err != null) {
                    Toast.makeText(this@MainActivity, err, Toast.LENGTH_SHORT).show()
                } else {
                    nameEdit.setText("")
                    numberEdit.setText("")
                    rebuildContacts()
                }
            }
        }
        contactsCard.addView(addBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        col.addView(contactsCard, cardParams())

        // --- دکمه‌ی تنظیمات باتری ---
        val battery = tv("Battery & autostart settings", 14f, ACCENT, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = bg(Color.TRANSPARENT, 16, strokeColor = tint(ACCENT, 140), strokeDp = 1)
            setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:$packageName"))
                )
            }
        }
        col.addView(battery, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })

        col.addView(tv("Made with ♥ by Roboram", 12f, MUTED).apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(22) })

        setContentView(scroll)
        rebuildContacts()
        refresh()
    }

    private fun card(title: String): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg(CARD, 20, strokeColor = tint(Color.WHITE, 18), strokeDp = 1)
            setPadding(dp(18), dp(16), dp(18), dp(18))
        }
        c.addView(tv(title, 11f, MUTED, true).apply { letterSpacing = 0.25f })
        return c
    }

    private fun cardParams() =
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) }

    private fun commandRow(icon: String, color: Int, title: String, sub: String): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val dot = tv(icon, 16f, color, true).apply {
            gravity = Gravity.CENTER
            background = bg(tint(color, 40), oval = true, strokeColor = tint(color, 160), strokeDp = 1)
        }
        row.addView(dot, LinearLayout.LayoutParams(dp(40), dp(40)))
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(tv(title, 16f, TEXT, true))
        texts.addView(tv(sub, 13f, MUTED))
        row.addView(texts, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(14) })
        row.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) }
        return row
    }

    private fun checkRow(label: String, value: TextView): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(tv(label, 14f, TEXT), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(value)
        row.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
        return row
    }

    // ---------- رفتار ----------
    private fun toggle() {
        if (CallService.running) {
            stopService(Intent(this, CallService::class.java))
            h.postDelayed({ refresh() }, 300)
        } else if (hasPerms()) {
            startForegroundService(Intent(this, CallService::class.java))
            h.postDelayed({ refresh() }, 400)
        } else {
            requestPermissions(perms, 1)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasPerms()) {
            startForegroundService(Intent(this, CallService::class.java))
        }
        h.postDelayed({ refresh() }, 400)
    }

    private fun field(hint: String, type: Int) = EditText(this).apply {
        this.hint = hint
        setHintTextColor(MUTED)
        setTextColor(TEXT)
        textSize = 15f
        inputType = type
        setSingleLine(true)
        background = bg(tint(Color.WHITE, 12), 12, strokeColor = tint(Color.WHITE, 30), strokeDp = 1)
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }

    private fun toggleAssistant() {
        val on = !Store.assistantOn(this)
        Store.setAssistant(this, on)
        if (CallService.running) {
            startService(
                Intent(this, CallService::class.java)
                    .setAction(if (on) "ASSISTANT_ON" else "ASSISTANT_OFF")
            )
        } else if (on) {
            Toast.makeText(this, "Press START to activate", Toast.LENGTH_SHORT).show()
        }
        refreshAssistantBtn()
    }

    private fun refreshAssistantBtn() {
        val on = Store.assistantOn(this)
        assistantBtn.text = if (on) "● HEY ROBO MODE: ON" else "○ HEY ROBO MODE: OFF"
        assistantBtn.setTextColor(if (on) GREEN else MUTED)
        assistantBtn.background = if (on)
            bg(tint(GREEN, 36), 16, strokeColor = tint(GREEN, 140), strokeDp = 1)
        else
            bg(tint(Color.WHITE, 14), 16, strokeColor = tint(Color.WHITE, 40), strokeDp = 1)
    }

    private fun rebuildContacts() {
        val list = Store.contacts(this)
        (contactsCard.getChildAt(0) as TextView).text = "CONTACTS  ${list.size}/${Store.MAX}"
        contactsList.removeAllViews()
        for (c in list) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(tv(c.name, 16f, TEXT, true))
            texts.addView(tv(c.number, 13f, MUTED))
            row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(tv("✕", 18f, RED, true).apply {
                setPadding(dp(14), dp(8), dp(4), dp(8))
                setOnClickListener {
                    Store.removeContact(this@MainActivity, c.name)
                    rebuildContacts()
                }
            })
            contactsList.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }
    }

    private fun setCheck(v: TextView, ok: Boolean, good: String, bad: String) {
        v.text = if (ok) "✓ $good" else "✕ $bad"
        v.setTextColor(if (ok) GREEN else RED)
    }

    private fun refresh() {
        refreshAssistantBtn()
        val running = CallService.running
        setCheck(serviceValue, running, "Running", "Stopped")
        setCheck(permValue, hasPerms(), "Granted", "Needed")
        setCheck(modelValue, CallService.modelReady, "Ready", "Not loaded")
        val gs = GadgetClient.state
        gadgetValue.text = if (gs == "Connected") "✓ Connected" else "• $gs"
        gadgetValue.setTextColor(
            when (gs) {
                "Connected" -> GREEN
                "Off" -> MUTED
                else -> Color.parseColor("#FFC107")
            }
        )

        if (running) {
            pill.text = "● ACTIVE"
            pill.setTextColor(GREEN)
            pill.background = bg(tint(GREEN, 36), 30, strokeColor = tint(GREEN, 140), strokeDp = 1)
            desc.text = "Listening for incoming calls.\nYou can close this screen."
            btnIcon.text = "■"
            btnLabel.text = "STOP"
            powerBtn.background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#00C853"), Color.parseColor("#00B8D4"))
            ).apply { setShape(GradientDrawable.OVAL) }
            startPulse()
        } else {
            pill.text = "● OFF"
            pill.setTextColor(MUTED)
            pill.background = bg(tint(Color.WHITE, 14), 30, strokeColor = tint(Color.WHITE, 40), strokeDp = 1)
            desc.text = "Tap the button to start."
            btnIcon.text = "▶"
            btnLabel.text = "START"
            powerBtn.background = bg(
                Color.parseColor("#1D2848"), oval = true, strokeColor = ACCENT, strokeDp = 2
            )
            stopPulse()
        }
    }

    private fun makePulse(v: View, delay: Long) = ObjectAnimator.ofPropertyValuesHolder(
        v,
        PropertyValuesHolder.ofFloat(View.SCALE_X, 0.9f, 1.5f),
        PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.9f, 1.5f),
        PropertyValuesHolder.ofFloat(View.ALPHA, 0.7f, 0f)
    ).apply {
        duration = 1800
        startDelay = delay
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        start()
    }

    private fun startPulse() {
        if (pulsing) return
        pulsing = true
        pulse1 = makePulse(ring1, 0)
        pulse2 = makePulse(ring2, 900)
    }

    private fun stopPulse() {
        if (!pulsing) return
        pulsing = false
        pulse1?.cancel(); pulse2?.cancel()
        ring1.alpha = 0f; ring2.alpha = 0f
    }

    override fun onResume() {
        super.onResume()
        h.post(poll)
    }

    override fun onPause() {
        super.onPause()
        h.removeCallbacks(poll)
    }
}

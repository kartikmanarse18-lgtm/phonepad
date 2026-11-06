package com.kar.phonepad

import android.Manifest
import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.*
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity() {
    private var backend: Backend? = null
    private var hid: Any? = null              // HidBackend (kept as Any so old Androids never load the class)
    private lateinit var status: TextView
    private lateinit var padPanel: LinearLayout
    private lateinit var streamPanel: LinearLayout
    private lateinit var panelBtn: Button
    private lateinit var streamBtn: Button
    private lateinit var streamInfo: TextView
    private lateinit var camCheck: CheckBox
    private lateinit var micCheck: CheckBox
    private val modButtons = HashMap<Int, Button>()
    private val modNames = HashMap<Int, String>()
    private val ui = Handler(Looper.getMainLooper())
    private var stickyMod = 0
    private var sens = 2f
    private var netMode = false
    private lateinit var modeBtn: Button

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun granted(p: String) = Build.VERSION.SDK_INT < 23 || checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ---------- lifecycle ----------
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        netMode = getSharedPreferences("pp", 0).getBoolean("net", false)
        updateModeLabel()
        if (netMode) useNetwork()
        else if (Build.VERSION.SDK_INT < 28) status.text = "Bluetooth mouse/keyboard needs Android 9+: tap Mode to use Network. Camera/mic streaming still works."
        else startBluetoothOrAsk()
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= 28) resumeBt()
        StreamService.onState = { runOnUiThread { refreshStream() } }
        refreshStream()
    }

    @TargetApi(28)
    private fun resumeBt() { (hid as? HidBackend)?.start() }

    override fun onPause() { StreamService.onState = null; super.onPause() }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        closeBackend()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(r: Int, p: Array<out String>, g: IntArray) {
        super.onRequestPermissionsResult(r, p, g)
        if (r == 1) {
            if (g.isNotEmpty() && g.all { it == PackageManager.PERMISSION_GRANTED }) useBluetooth()
            else status.text = "Bluetooth permission denied: allow it in Settings, or tap Mode to use Network"
        } else if (r == 2) launchStream()
    }

    // ---------- backends ----------
    private fun closeBackend() { backend?.close(); backend = null; hid = null }

    private fun startBluetoothOrAsk() {
        if (Build.VERSION.SDK_INT >= 31 && !granted(Manifest.permission.BLUETOOTH_CONNECT))
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE), 1)
        else useBluetooth()
    }

    @TargetApi(28)
    private fun useBluetooth() {
        closeBackend()
        val h = HidBackend(this) { runOnUiThread { status.text = it } }
        hid = h; backend = h
        status.text = "Starting Bluetooth…"
        h.start()
    }

    private fun updateModeLabel() { modeBtn.text = if (netMode) "Mode: Net" else "Mode: BT" }

    private fun useNetwork() {
        closeBackend()
        backend = NetBackend(this) { runOnUiThread { status.text = it } }
        status.text = "Network mode: run phonepad_receiver.py on the PC, then tap Connect"
    }

    private fun toggleMode() {
        netMode = !netMode
        getSharedPreferences("pp", 0).edit().putBoolean("net", netMode).apply()
        updateModeLabel()
        if (netMode) useNetwork()
        else if (Build.VERSION.SDK_INT < 28) { closeBackend(); status.text = "Bluetooth mouse/keyboard needs Android 9+" }
        else startBluetoothOrAsk()
    }

    private fun askPcAddress() {
        val nb = backend as? NetBackend ?: return
        val et = EditText(this).apply {
            hint = "PC address, e.g. 192.168.1.20"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(getSharedPreferences("pp", 0).getString("ip", ""))
        }
        AlertDialog.Builder(this).setTitle("PC receiver address").setView(et)
            .setPositiveButton("Connect") { _, _ ->
                val ip = et.text.toString().trim()
                if (ip.isNotEmpty()) { getSharedPreferences("pp", 0).edit().putString("ip", ip).apply(); nb.connect(ip) }
            }.setNegativeButton("Cancel", null).show()
    }

    @TargetApi(28)
    private fun pickDevice() { (hid as? HidBackend)?.pick() }

    // ---------- keyboard helpers ----------
    private fun sendKey(code: Int, mod: Int = 0) {
        val m = mod or stickyMod
        if (stickyMod != 0) { stickyMod = 0; updateMods() }
        backend?.key(code, m)
    }

    private fun updateMods() {
        for ((bit, b) in modButtons) b.text = (if (stickyMod and bit != 0) "● " else "") + modNames[bit]
    }

    private fun keyFor(c: Char): Pair<Int, Int>? {
        val sh = 0x02
        return when {
            c in 'a'..'z' -> Pair(0x04 + (c - 'a'), 0)
            c in 'A'..'Z' -> Pair(0x04 + (c - 'A'), sh)
            c in '1'..'9' -> Pair(0x1E + (c - '1'), 0)
            c == '0' -> Pair(0x27, 0)
            else -> when (c) {
                '!' -> Pair(0x1E, sh); '@' -> Pair(0x1F, sh); '#' -> Pair(0x20, sh); '$' -> Pair(0x21, sh)
                '%' -> Pair(0x22, sh); '^' -> Pair(0x23, sh); '&' -> Pair(0x24, sh); '*' -> Pair(0x25, sh)
                '(' -> Pair(0x26, sh); ')' -> Pair(0x27, sh)
                '-' -> Pair(0x2D, 0); '_' -> Pair(0x2D, sh); '=' -> Pair(0x2E, 0); '+' -> Pair(0x2E, sh)
                '[' -> Pair(0x2F, 0); '{' -> Pair(0x2F, sh); ']' -> Pair(0x30, 0); '}' -> Pair(0x30, sh)
                '\\' -> Pair(0x31, 0); '|' -> Pair(0x31, sh); ';' -> Pair(0x33, 0); ':' -> Pair(0x33, sh)
                '\'' -> Pair(0x34, 0); '"' -> Pair(0x34, sh); '`' -> Pair(0x35, 0); '~' -> Pair(0x35, sh)
                ',' -> Pair(0x36, 0); '<' -> Pair(0x36, sh); '.' -> Pair(0x37, 0); '>' -> Pair(0x37, sh)
                '/' -> Pair(0x38, 0); '?' -> Pair(0x38, sh)
                ' ' -> Pair(0x2C, 0); '\n' -> Pair(0x28, 0); '\t' -> Pair(0x2B, 0)
                else -> null
            }
        }
    }

    private fun typeText(s: String) { for (c in s) keyFor(c)?.let { sendKey(it.first, it.second) } }

    // ---------- UI builders ----------
    private fun button(t: String, f: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; minWidth = 0; minimumWidth = 0; setPadding(0, 0, 0, 0)
        setOnClickListener { f() }
    }

    private fun row(vararg v: View) = LinearLayout(this).apply {
        for (x in v) addView(x, LinearLayout.LayoutParams(0, dp(46), 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
    }

    /** key button with press-and-hold auto repeat */
    @SuppressLint("ClickableViewAccessibility")
    private fun keyButton(t: String, code: Int, repeat: Boolean = true) = button(t) {}.apply {
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true; sendKey(code)
                    if (repeat) {
                        val r = object : Runnable { override fun run() { sendKey(code); ui.postDelayed(this, 60) } }
                        v.tag = r; ui.postDelayed(r, 400)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    (v.tag as? Runnable)?.let { ui.removeCallbacks(it) }; v.tag = null
                }
            }
            true
        }
    }

    private fun modButton(t: String, bit: Int) = button(t) {
        stickyMod = stickyMod xor bit; updateMods()
    }.also { modButtons[bit] = it; modNames[bit] = t }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(6), dp(6), dp(6)); fitsSystemWindows = true
        }
        status = TextView(this).apply { text = "PhonePad"; textSize = 14f }
        root.addView(status)

        modeBtn = button("Mode: BT") { toggleMode() }
        panelBtn = button("Stream") {
            val showStream = streamPanel.visibility != View.VISIBLE
            streamPanel.visibility = if (showStream) View.VISIBLE else View.GONE
            padPanel.visibility = if (showStream) View.GONE else View.VISIBLE
            panelBtn.text = if (showStream) "Pad" else "Stream"
        }
        root.addView(row(
            button("Visible") {
                if (netMode) toast("Visible is only for Bluetooth mode") else
                if (hid == null) toast("Needs Android 9+ and Bluetooth permission") else
                    startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                        .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120))
            },
            button("Connect") {
                if (netMode) askPcAddress()
                else if (hid == null) toast("Needs Android 9+ and Bluetooth permission")
                else if (Build.VERSION.SDK_INT >= 28) pickDevice()
            },
            modeBtn, panelBtn))

        // ----- pad panel -----
        padPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val pad = View(this).apply { setBackgroundColor(0xFF2B2B2B.toInt()) }
        var lx = 0f; var ly = 0f; var moved = 0f; var t0 = 0L; var ax = 0f; var ay = 0f; var sc = 0f
        var maxN = 0; var lastTapUp = 0L; var lastWasTap = false; var dragging = false
        pad.setOnTouchListener { _, e ->
            val skip = if (e.actionMasked == MotionEvent.ACTION_POINTER_UP) e.actionIndex else -1
            var sx = 0f; var sy = 0f; var cnt = 0
            for (i in 0 until e.pointerCount) { if (i == skip) continue; sx += e.getX(i); sy += e.getY(i); cnt++ }
            val x = sx / max(cnt, 1); val y = sy / max(cnt, 1)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lx = x; ly = y; moved = 0f; t0 = e.eventTime; maxN = 1; ax = 0f; ay = 0f; sc = 0f
                    if (lastWasTap && e.eventTime - lastTapUp < 300) { dragging = true; backend?.buttons(1) }
                }
                MotionEvent.ACTION_POINTER_DOWN -> { lx = x; ly = y; maxN = max(maxN, e.pointerCount) }
                MotionEvent.ACTION_POINTER_UP -> { lx = x; ly = y }
                MotionEvent.ACTION_MOVE -> {
                    val dx = x - lx; val dy = y - ly; lx = x; ly = y
                    moved += abs(dx) + abs(dy)
                    if (cnt >= 2) {
                        sc += -dy / 6f
                        val s = sc.toInt()
                        if (s != 0) { backend?.mouse(0, 0, s); sc -= s }
                    } else {
                        val g = sens * (1f + min(hypot(dx, dy) / 25f, 1.5f))   // mild acceleration
                        ax += dx * g; ay += dy * g
                        val ix = ax.toInt(); val iy = ay.toInt()
                        if (ix != 0 || iy != 0) { backend?.mouse(ix, iy, 0); ax -= ix; ay -= iy }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) { backend?.buttons(0); dragging = false; lastWasTap = false }
                    else if (moved < 12 && e.eventTime - t0 < 250) {
                        if (maxN >= 2) { backend?.click(2); lastWasTap = false }          // two-finger tap = right click
                        else { backend?.click(1); lastWasTap = true; lastTapUp = e.eventTime }
                    } else lastWasTap = false
                }
                MotionEvent.ACTION_CANCEL -> { if (dragging) { backend?.buttons(0); dragging = false }; lastWasTap = false }
            }
            true
        }
        padPanel.addView(pad, LinearLayout.LayoutParams(-1, 0, 1f).apply { setMargins(dp(2), dp(4), dp(2), dp(4)) })

        val speed = SeekBar(this).apply {
            max = 70; progress = 30
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { sens = 0.5f + p / 20f }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        padPanel.addView(LinearLayout(this).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply { text = "Speed" })
            addView(speed, LinearLayout.LayoutParams(0, -2, 1f))
        })

        padPanel.addView(row(
            button("Left") { backend?.click(1) }, button("Middle") { backend?.click(4) }, button("Right") { backend?.click(2) }))
        padPanel.addView(row(
            keyButton("←", 0x50), keyButton("↑", 0x52), keyButton("↓", 0x51), keyButton("→", 0x4F)))
        padPanel.addView(row(
            keyButton("OK", 0x28), keyButton("Esc", 0x29, false), keyButton("⌫", 0x2A)))
        padPanel.addView(row(
            button("Vol −") { backend?.media(2) }, button("Mute") { backend?.media(4) }, button("Vol +") { backend?.media(1) }))

        val extra = LinearLayout(this)
        fun add(v: View) = extra.addView(v, LinearLayout.LayoutParams(dp(62), dp(46)).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
        add(modButton("Ctrl", 0x01)); add(modButton("Shift", 0x02)); add(modButton("Alt", 0x04)); add(modButton("Win", 0x08))
        add(keyButton("Tab", 0x2B)); add(keyButton("Del", 0x4C)); add(keyButton("Home", 0x4A)); add(keyButton("End", 0x4D))
        add(keyButton("PgUp", 0x4B)); add(keyButton("PgDn", 0x4E))
        for (i in 1..12) add(keyButton("F$i", 0x3A + i - 1, false))
        padPanel.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(extra) })

        val typeBox = object : EditText(this) {
            override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
                outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                    InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
                return object : BaseInputConnection(this, false) {
                    var comp = ""
                    private fun diff(s: String) {      // diff against the composing text so nothing is typed twice
                        val common = comp.commonPrefixWith(s).length
                        repeat(comp.length - common) { sendKey(0x2A) }
                        typeText(s.substring(common)); comp = s
                    }
                    override fun commitText(t: CharSequence, p: Int): Boolean { diff(t.toString()); comp = ""; return true }
                    override fun setComposingText(t: CharSequence, p: Int): Boolean { diff(t.toString()); return true }
                    override fun finishComposingText(): Boolean { comp = ""; return true }
                    override fun deleteSurroundingText(b: Int, a: Int): Boolean { repeat(max(b, 1)) { sendKey(0x2A) }; comp = ""; return true }
                    override fun getTextBeforeCursor(n: Int, f: Int): CharSequence = ""
                    override fun getTextAfterCursor(n: Int, f: Int): CharSequence = ""
                    override fun sendKeyEvent(ev: KeyEvent): Boolean {
                        if (ev.action == KeyEvent.ACTION_DOWN) when (ev.keyCode) {
                            KeyEvent.KEYCODE_DEL -> sendKey(0x2A)
                            KeyEvent.KEYCODE_ENTER -> sendKey(0x28)
                            else -> { val u = ev.unicodeChar; if (u != 0) typeText(u.toChar().toString()) }
                        }
                        return true
                    }
                }
            }
        }.apply { hint = "Tap here to type on the PC" }
        padPanel.addView(typeBox, LinearLayout.LayoutParams(-1, -2))
        root.addView(padPanel, LinearLayout.LayoutParams(-1, 0, 1f))

        // ----- stream panel -----
        streamPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(dp(4), dp(8), dp(4), dp(4)) }
        camCheck = CheckBox(this).apply { text = "Share camera"; isChecked = true }
        micCheck = CheckBox(this).apply { text = "Share microphone"; isChecked = true }
        streamBtn = button("Start streaming") {
            if (StreamService.running) stopService(Intent(this, StreamService::class.java)) else startStream()
        }
        streamInfo = TextView(this).apply { textSize = 15f; setTextIsSelectable(true); setPadding(dp(4), dp(10), dp(4), dp(4)) }
        streamPanel.addView(camCheck); streamPanel.addView(micCheck)
        streamPanel.addView(row(streamBtn, button("Flip cam") { StreamService.instance?.flip() },
            button("Rotate") { StreamService.instance?.rotate() }))
        streamPanel.addView(streamInfo)
        streamPanel.addView(TextView(this).apply {
            textSize = 13f
            text = "The PC needs a network path to this phone: phone hotspot, USB tethering, or the same router. " +
                "Open the address in the PC's browser. See README for making it appear as a real webcam/mic."
        })
        root.addView(streamPanel, LinearLayout.LayoutParams(-1, 0, 1f))

        setContentView(root)
    }

    // ---------- streaming ----------
    private fun startStream() {
        val need = ArrayList<String>()
        if (camCheck.isChecked && !granted(Manifest.permission.CAMERA)) need.add(Manifest.permission.CAMERA)
        if (micCheck.isChecked && !granted(Manifest.permission.RECORD_AUDIO)) need.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (need.isEmpty()) launchStream() else requestPermissions(need.toTypedArray(), 2)
    }

    private fun launchStream() {
        val cam = camCheck.isChecked && granted(Manifest.permission.CAMERA)
        val mic = micCheck.isChecked && granted(Manifest.permission.RECORD_AUDIO)
        if (!cam && !mic) { toast("Allow camera and/or microphone to stream"); return }
        val i = Intent(this, StreamService::class.java).putExtra("cam", cam).putExtra("mic", mic)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
    }

    private fun refreshStream() {
        val on = StreamService.running
        streamBtn.text = if (on) "Stop streaming" else "Start streaming"
        streamInfo.text = if (on) "Open on the PC:\n" + StreamService.urls.joinToString("\n") +
            "\n\nVideo: /video\nAudio: /audio" else "Not streaming"
    }
}

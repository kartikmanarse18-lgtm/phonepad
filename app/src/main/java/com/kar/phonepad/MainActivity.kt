package com.kar.phonepad

import android.Manifest
import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.HapticFeedbackConstants
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
    private fun resumeBt() { (hid as? HidBackend)?.ensure() }

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
        else if (r == 3) toast("Permission updated: tap Connect again to scan")
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
        val h = HidBackend(this, tvMode) { runOnUiThread { status.text = it } }
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
        for ((bit, b) in modButtons) setActive(b, stickyMod and bit != 0)
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
    private val cBg = 0xFF121417.toInt()
    private val cSurf = 0xFF1E2227.toInt()
    private val cSurf2 = 0xFF2A3038.toInt()
    private val cPress = 0xFF3A444F.toInt()
    private val cAccent = 0xFF4DB6AC.toInt()
    private val cText = 0xFFECEFF1.toInt()
    private val cSub = 0xFF90A4AE.toInt()
    private val cOk = 0xFF66BB6A.toInt()
    private val cWarn = 0xFFFFB74D.toInt()
    private val cBad = 0xFFEF5350.toInt()

    private var sensP = 30
    private var scrollP = 5
    private var natural = true
    private var tvMode = false
    private lateinit var leftB: Button
    private lateinit var midB: Button
    private lateinit var rightB: Button
    private var scrollSpeed = 1f
    private var selectMode = false
    private var scrollAcc = 0f
    private var flingV = 0f
    private lateinit var dot: View
    private lateinit var hintView: TextView
    private lateinit var keysPanel: LinearLayout
    private lateinit var selectBtn: Button
    private lateinit var keysBtn: Button

    private val flingRun = object : Runnable {
        override fun run() {
            flingV *= 0.93f
            if (abs(flingV) < 0.05f) { flingV = 0f; return }
            doScroll(flingV * 16f)
            ui.postDelayed(this, 16)
        }
    }

    /** [dyPx]: how far the finger moved vertically (positive = down). Sends smooth wheel steps. */
    private fun doScroll(dyPx: Float) {
        scrollAcc += (if (natural) dyPx else -dyPx) / (dp(12) / scrollSpeed)
        val s = scrollAcc.toInt()
        if (s != 0) { backend?.mouse(0, 0, s); scrollAcc -= s }
    }

    private fun shape(c: Int, r: Int) = GradientDrawable().apply { setColor(c); cornerRadius = dp(r).toFloat() }

    private fun styled(b: Button, n: Int = cSurf2, p: Int = cPress, r: Int = 12) {
        b.background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), shape(p, r))
            addState(intArrayOf(), shape(n, r))
        }
        b.setTextColor(cText); b.textSize = 14f; b.isAllCaps = false; b.stateListAnimator = null
    }

    private fun setActive(b: Button, on: Boolean) {
        styled(b, if (on) cAccent else cSurf2, if (on) cAccent else cPress)
        b.setTextColor(if (on) 0xFF00201D.toInt() else cText)
    }

    private fun button(t: String, f: () -> Unit) = Button(this).apply {
        text = t; minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0; setPadding(dp(4), 0, dp(4), 0)
        styled(this)
        setOnClickListener { it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); f() }
    }

    private fun row(vararg v: View) = LinearLayout(this).apply {
        for (x in v) addView(x, LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
    }

    /** key button with press-and-hold auto repeat */
    @SuppressLint("ClickableViewAccessibility")
    private fun keyButton(t: String, code: Int, repeat: Boolean = true) = button(t) {}.apply {
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true; v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); sendKey(code)
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

    private val padHint = "Touchpad\n\n1 finger: move  •  tap: click\n2 fingers: scroll  •  2-finger tap: right click\n" +
        "3-finger tap: middle click\nLong-press or double-tap and drag: select\nRight strip: scroll"
    private val tvHint = "TV remote mode\n\nSwipe: arrow keys\nTap: OK / Enter\n2-finger tap: Back\n" +
        "Right strip: up / down\nKeys button: Home, Back, Menu, Play"

    private fun applyTvMode() {
        leftB.text = if (tvMode) "OK / Select" else "Left click"
        midB.text = if (tvMode) "Home" else "Middle"
        rightB.text = if (tvMode) "Back" else "Right click"
        hintView.text = if (tvMode) tvHint else padHint
        hintView.visibility = View.VISIBLE
    }

    private fun tvClick(b: Int) {
        when (b) { 1 -> sendKey(0x28); 2 -> sendKey(0x29); 4 -> backend?.media(16) }
    }

    private fun showSettings() {
        val sp = getSharedPreferences("pp", 0)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(4)) }
        fun addSeek(label: String, maxP: Int, prog: Int, on: (Int) -> Unit) {
            box.addView(TextView(this).apply { text = label; setPadding(0, dp(8), 0, 0) })
            box.addView(SeekBar(this).apply {
                max = maxP; progress = prog
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { on(p) }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            })
        }
        addSeek("Pointer speed", 70, sensP) { p -> sensP = p; sens = 0.5f + p / 20f; sp.edit().putInt("sens", p).apply() }
        addSeek("Scroll speed", 30, scrollP) { p -> scrollP = p; scrollSpeed = 0.5f + p / 10f; sp.edit().putInt("scroll", p).apply() }
        box.addView(CheckBox(this).apply {
            text = "Natural scrolling (content follows finger)"; isChecked = natural
            setOnCheckedChangeListener { _, c -> natural = c; sp.edit().putBoolean("natural", c).apply() }
        })
        box.addView(CheckBox(this).apply {
            text = "TV mode: swipes = arrow keys, tap = OK (for smart TVs)"; isChecked = tvMode
            setOnCheckedChangeListener { _, c ->
                tvMode = c; sp.edit().putBoolean("tv", c).apply(); applyTvMode()
                if (!netMode && Build.VERSION.SDK_INT >= 28) startBluetoothOrAsk()
            }
        })
        AlertDialog.Builder(this).setTitle("Touchpad settings").setView(box).setPositiveButton("Done", null).show()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildUi() {
        val sp = getSharedPreferences("pp", 0)
        sensP = sp.getInt("sens", 30); sens = 0.5f + sensP / 20f
        scrollP = sp.getInt("scroll", 5); scrollSpeed = 0.5f + scrollP / 10f
        natural = sp.getBoolean("natural", true)
        tvMode = sp.getBoolean("tv", false)
        window.statusBarColor = cBg; window.navigationBarColor = cBg

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(6), dp(8), dp(6)); fitsSystemWindows = true
            setBackgroundColor(cBg)
        }

        // ----- top bar: status pill + mode + stream -----
        dot = View(this).apply { background = GradientDrawable().apply { setShape(GradientDrawable.OVAL); setColor(cWarn) } }
        status = TextView(this).apply { text = "PhonePad"; textSize = 12f; setTextColor(cText); maxLines = 3 }
        status.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val t = s.toString().lowercase()
                val c = when {
                    "not connected" in t || "disconnected" in t || "fail" in t || "error" in t || "denied" in t ||
                        "refused" in t || "not ready" in t || "not available" in t || "turn bluetooth on" in t -> cBad
                    "connected" in t -> cOk
                    else -> cWarn
                }
                (dot.background as GradientDrawable).setColor(c)
            }
        })
        val pill = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; background = shape(cSurf, 14); setPadding(dp(12), dp(6), dp(12), dp(6))
            addView(dot, LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(10) })
            addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        }
        pill.setOnClickListener {
            if (netMode) toast("Network mode: tap Connect")
            else if (Build.VERSION.SDK_INT >= 28) { status.text = "Restarting Bluetooth…"; startBluetoothOrAsk() }
        }
        modeBtn = button("Mode: BT") { toggleMode() }
        panelBtn = button("Stream") {
            val showStream = streamPanel.visibility != View.VISIBLE
            streamPanel.visibility = if (showStream) View.VISIBLE else View.GONE
            padPanel.visibility = if (showStream) View.GONE else View.VISIBLE
            panelBtn.text = if (showStream) "Pad" else "Stream"
        }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(pill, LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
        top.addView(modeBtn, LinearLayout.LayoutParams(dp(84), dp(48)).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
        top.addView(panelBtn, LinearLayout.LayoutParams(dp(72), dp(48)).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
        root.addView(top)

        // ----- pad panel -----
        padPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        selectBtn = button("Select") {
            selectMode = !selectMode; setActive(selectBtn, selectMode)
            if (selectMode) toast("Select mode: swipe on the pad to select. Tap Select again to stop")
        }
        keysBtn = button("Keys") {
            val show = keysPanel.visibility != View.VISIBLE
            keysPanel.visibility = if (show) View.VISIBLE else View.GONE; setActive(keysBtn, show)
        }
        padPanel.addView(row(
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
            selectBtn, keysBtn, button("⚙") { showSettings() }))

        // touchpad + scroll strip
        val pad = View(this).apply { background = shape(cSurf, 18) }
        hintView = TextView(this).apply {
            text = padHint
            setTextColor(cSub); textSize = 13f; gravity = Gravity.CENTER; isClickable = false
        }
        val padFrame = FrameLayout(this).apply {
            addView(pad, FrameLayout.LayoutParams(-1, -1))
            addView(hintView, FrameLayout.LayoutParams(-1, -1))
        }
        val tapSlop = dp(8).toFloat()
        val moveDead = dp(4).toFloat()
        var lx = 0f; var ly = 0f; var moved = 0f; var t0 = 0L; var lastT = 0L; var ax = 0f; var ay = 0f
        var maxN = 0; var lastTapUp = 0L; var lastWasTap = false; var dragging = false; var started = false
        val longPress = Runnable {
            if (!dragging && moved < tapSlop && maxN == 1) {
                dragging = true; backend?.buttons(1)
                pad.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }
        }
        pad.setOnTouchListener { _, e ->
            val skip = if (e.actionMasked == MotionEvent.ACTION_POINTER_UP) e.actionIndex else -1
            var sx = 0f; var sy = 0f; var cnt = 0
            for (i in 0 until e.pointerCount) { if (i == skip) continue; sx += e.getX(i); sy += e.getY(i); cnt++ }
            val x = sx / max(cnt, 1); val y = sy / max(cnt, 1)
            if (tvMode) {                                   // TV remote: swipe = arrow keys, tap = OK, 2-finger tap = Back
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { hintView.visibility = View.GONE; lx = x; ly = y; moved = 0f; t0 = e.eventTime; ax = 0f; ay = 0f; maxN = 1 }
                    MotionEvent.ACTION_POINTER_DOWN -> { maxN = max(maxN, e.pointerCount); lx = x; ly = y }
                    MotionEvent.ACTION_POINTER_UP -> { lx = x; ly = y }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = x - lx; val dy = y - ly; lx = x; ly = y
                        moved += abs(dx) + abs(dy); ax += dx; ay += dy
                        val step = dp(36).toFloat()
                        if (abs(ax) >= step || abs(ay) >= step) {
                            if (abs(ax) > abs(ay)) sendKey(if (ax > 0) 0x4F else 0x50) else sendKey(if (ay > 0) 0x51 else 0x52)
                            ax = 0f; ay = 0f
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (moved < tapSlop && e.eventTime - t0 < 300) sendKey(if (maxN >= 2) 0x29 else 0x28)
                    }
                }
                return@setOnTouchListener true
            }
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    hintView.visibility = View.GONE
                    ui.removeCallbacks(flingRun); flingV = 0f; scrollAcc = 0f
                    lx = x; ly = y; moved = 0f; t0 = e.eventTime; lastT = e.eventTime; maxN = 1; ax = 0f; ay = 0f; started = false
                    if (selectMode || (lastWasTap && e.eventTime - lastTapUp < 300)) { dragging = true; backend?.buttons(1) }
                    else ui.postDelayed(longPress, 450)
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    ui.removeCallbacks(longPress)
                    lx = x; ly = y; lastT = e.eventTime; maxN = max(maxN, e.pointerCount)
                }
                MotionEvent.ACTION_POINTER_UP -> { lx = x; ly = y }
                MotionEvent.ACTION_MOVE -> {
                    val dx = x - lx; val dy = y - ly; lx = x; ly = y
                    moved += abs(dx) + abs(dy)
                    if (moved > tapSlop) ui.removeCallbacks(longPress)
                    if (cnt >= 2 && !dragging) {
                        doScroll(dy)
                        val dt = max(e.eventTime - lastT, 1L); lastT = e.eventTime
                        flingV = 0.6f * (dy / dt) + 0.4f * flingV
                    } else {
                        if (!started && moved >= moveDead) started = true      // ignore tiny finger jitter so taps stay accurate
                        if (started) {
                            val g = sens * (1f + min(hypot(dx, dy) / 25f, 1.5f))   // mild acceleration
                            ax += dx * g; ay += dy * g
                            val ix = ax.toInt(); val iy = ay.toInt()
                            if (ix != 0 || iy != 0) { backend?.mouse(ix, iy, 0); ax -= ix; ay -= iy }
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    ui.removeCallbacks(longPress)
                    val tap = moved < tapSlop && e.eventTime - t0 < 300
                    if (dragging) { backend?.buttons(0); dragging = false; lastWasTap = false }
                    else if (tap) {
                        when {
                            maxN >= 3 -> { backend?.click(4); lastWasTap = false }
                            maxN == 2 -> { backend?.click(2); lastWasTap = false }
                            else -> { backend?.click(1); lastWasTap = true; lastTapUp = e.eventTime }
                        }
                    } else {
                        lastWasTap = false
                        if (maxN >= 2 && abs(flingV) > 0.3f) ui.post(flingRun)
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    ui.removeCallbacks(longPress)
                    if (dragging) { backend?.buttons(0); dragging = false }
                    lastWasTap = false
                }
            }
            true
        }

        val strip = TextView(this).apply {
            text = "⇅"; gravity = Gravity.CENTER; setTextColor(cSub); textSize = 20f; background = shape(cSurf, 18)
        }
        var stY = 0f; var stT = 0L
        strip.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.parent.requestDisallowInterceptTouchEvent(true)
                    ui.removeCallbacks(flingRun); flingV = 0f; scrollAcc = 0f; stY = e.y; stT = e.eventTime
                    (v.background as GradientDrawable).setColor(cSurf2)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = (e.y - stY) * 1.8f; stY = e.y
                    doScroll(dy)
                    val dt = max(e.eventTime - stT, 1L); stT = e.eventTime
                    flingV = 0.6f * (dy / dt) + 0.4f * flingV
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    (v.background as GradientDrawable).setColor(cSurf)
                    if (abs(flingV) > 0.3f) ui.post(flingRun)
                }
            }
            true
        }
        val padRow = LinearLayout(this)
        padRow.addView(padFrame, LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
        padRow.addView(strip, LinearLayout.LayoutParams(dp(44), -1).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
        padPanel.addView(padRow, LinearLayout.LayoutParams(-1, 0, 1f))

        // click buttons, big and thumb-friendly
        val clickRow = LinearLayout(this)
        fun cl(t: String, w: Float, b: Int): Button = button(t) { if (tvMode) tvClick(b) else backend?.click(b) }.also {
            clickRow.addView(it, LinearLayout.LayoutParams(0, dp(56), w).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
        }
        leftB = cl("Left click", 2f, 1); midB = cl("Middle", 1f, 4); rightB = cl("Right click", 2f, 2)
        padPanel.addView(clickRow)

        // extra keys (hidden until "Keys" is tapped)
        keysPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        keysPanel.addView(row(
            button("Home") { backend?.media(16) }, button("Back") { backend?.media(32) },
            button("Menu") { backend?.media(64) }, button("Play/Pause") { backend?.media(8) }))
        keysPanel.addView(row(keyButton("←", 0x50), keyButton("↑", 0x52), keyButton("↓", 0x51), keyButton("→", 0x4F)))
        keysPanel.addView(row(keyButton("Enter", 0x28), keyButton("Esc", 0x29, false), keyButton("⌫", 0x2A)))
        keysPanel.addView(row(
            button("Vol −") { backend?.media(2) }, button("Mute") { backend?.media(4) }, button("Vol +") { backend?.media(1) }))
        val extra = LinearLayout(this)
        fun add(v: View) = extra.addView(v, LinearLayout.LayoutParams(dp(62), dp(46)).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
        add(modButton("Ctrl", 0x01)); add(modButton("Shift", 0x02)); add(modButton("Alt", 0x04)); add(modButton("Win", 0x08))
        add(keyButton("Tab", 0x2B)); add(keyButton("Del", 0x4C)); add(keyButton("Home", 0x4A)); add(keyButton("End", 0x4D))
        add(keyButton("PgUp", 0x4B)); add(keyButton("PgDn", 0x4E))
        for (i in 1..12) add(keyButton("F$i", 0x3A + i - 1, false))
        keysPanel.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(extra) })
        padPanel.addView(keysPanel)

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
        }.apply {
            hint = "⌨  Tap here to type on the PC"
            setTextColor(cText); setHintTextColor(cSub); textSize = 15f
            background = shape(cSurf, 14); setPadding(dp(14), 0, dp(14), 0)
        }
        padPanel.addView(typeBox, LinearLayout.LayoutParams(-1, dp(48)).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
        root.addView(padPanel, LinearLayout.LayoutParams(-1, 0, 1f))

        // ----- stream panel -----
        streamPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(dp(4), dp(8), dp(4), dp(4)) }
        camCheck = CheckBox(this).apply { text = "Share camera"; isChecked = true; setTextColor(cText) }
        micCheck = CheckBox(this).apply { text = "Share microphone"; isChecked = true; setTextColor(cText) }
        streamBtn = button("Start streaming") {
            if (StreamService.running) stopService(Intent(this, StreamService::class.java)) else startStream()
        }
        streamInfo = TextView(this).apply { textSize = 15f; setTextColor(cText); setTextIsSelectable(true); setPadding(dp(4), dp(10), dp(4), dp(4)) }
        streamPanel.addView(camCheck); streamPanel.addView(micCheck)
        streamPanel.addView(row(streamBtn, button("Flip cam") { StreamService.instance?.flip() },
            button("Rotate") { StreamService.instance?.rotate() }))
        streamPanel.addView(streamInfo)
        streamPanel.addView(TextView(this).apply {
            textSize = 13f; setTextColor(cSub)
            text = "The PC needs a network path to this phone: phone hotspot, USB tethering, or the same router. " +
                "Open the address in the PC's browser. See README for making it appear as a real webcam/mic."
        })
        root.addView(streamPanel, LinearLayout.LayoutParams(-1, 0, 1f))

        setContentView(root)
        applyTvMode()
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

package com.kar.phonepad

import android.Manifest
import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.ArrayAdapter
import android.widget.ListView
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.min

interface Backend {
    fun mouse(dx: Int, dy: Int, w: Int)
    fun buttons(mask: Int)       // held buttons: 1 = left, 2 = right, 4 = middle (for drag)
    fun click(b: Int)            // 1 = left, 2 = right, 4 = middle
    fun key(code: Int, mod: Int = 0)   // HID usage code + modifier bitmask (1 ctrl, 2 shift, 4 alt, 8 win)
    fun media(bit: Int)          // 1 vol+, 2 vol-, 4 mute, 8 play/pause, 16 home, 32 back, 64 menu
    fun close() {}
}

// ---- HID report descriptor pieces ----
private val MOUSE_DESC = intArrayOf(
    0x05,0x01,0x09,0x02,0xA1,0x01,0x85,0x01,0x09,0x01,0xA1,0x00,0x05,0x09,0x19,0x01,0x29,0x03,
    0x15,0x00,0x25,0x01,0x95,0x03,0x75,0x01,0x81,0x02,0x95,0x01,0x75,0x05,0x81,0x03,
    0x05,0x01,0x09,0x30,0x09,0x31,0x09,0x38,0x15,0x81,0x25,0x7F,0x75,0x08,0x95,0x03,0x81,0x06,0xC0,0xC0)
private val KEYBOARD_DESC = intArrayOf(
    0x05,0x01,0x09,0x06,0xA1,0x01,0x85,0x02,0x05,0x07,0x19,0xE0,0x29,0xE7,0x15,0x00,0x25,0x01,
    0x75,0x01,0x95,0x08,0x81,0x02,0x95,0x01,0x75,0x08,0x81,0x01,0x95,0x06,0x75,0x08,0x15,0x00,
    0x25,0x65,0x05,0x07,0x19,0x00,0x29,0x65,0x81,0x00,0xC0)
private val CONSUMER_COMBO = intArrayOf(
    0x05,0x0C,0x09,0x01,0xA1,0x01,0x85,0x03,0x15,0x00,0x25,0x01,0x75,0x01,0x95,0x03,
    0x09,0xE9,0x09,0xEA,0x09,0xE2,0x81,0x02,0x95,0x05,0x81,0x03,0xC0)
// TV remote keys: vol+, vol-, mute, play/pause, home (AC Home), back (AC Back), menu
private val CONSUMER_TV = intArrayOf(
    0x05,0x0C,0x09,0x01,0xA1,0x01,0x85,0x03,0x15,0x00,0x25,0x01,0x75,0x01,0x95,0x07,
    0x09,0xE9,0x09,0xEA,0x09,0xE2,0x09,0xCD,0x0A,0x23,0x02,0x0A,0x24,0x02,0x09,0x40,
    0x81,0x02,0x95,0x01,0x81,0x03,0xC0)
private fun descOf(vararg parts: IntArray) = parts.flatMap { it.toList() }.map { it.toByte() }.toByteArray()

/**
 * Real Bluetooth HID device. Needs Android 9+ (API 28).
 * tvMode = keyboard + remote keys only (smart TVs usually reject a "mouse" from a phone).
 */
@TargetApi(28)
@SuppressLint("MissingPermission")
class HidBackend(private val act: Activity, private val tvMode: Boolean, private val status: (String) -> Unit) : Backend {
    private var hid: BluetoothHidDevice? = null
    @Volatile private var host: BluetoothDevice? = null
    @Volatile private var registered = false
    private var closing = false
    private var starting = false
    private var attempts = 0
    private val maxAttempts = 5
    private var ignoreFalseUntil = 0L
    private var btn = 0
    private var lastWarn = 0L
    private var scanRx: BroadcastReceiver? = null
    private var pairing: BluetoothDevice? = null
    private val handler = Handler(Looper.getMainLooper())
    private val watchdog = Runnable { if (!registered && !closing) retry("no answer from the Bluetooth system") }

    private val desc: ByteArray =
        if (tvMode) descOf(KEYBOARD_DESC, CONSUMER_TV) else descOf(MOUSE_DESC, KEYBOARD_DESC, CONSUMER_COMBO)

    private val cb = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(d: BluetoothDevice?, reg: Boolean) {
            act.runOnUiThread {
                if (closing) return@runOnUiThread
                if (reg) {
                    handler.removeCallbacks(watchdog)
                    registered = true; attempts = 0
                    if (d != null) { host = d; status("Bluetooth connected: ${d.name ?: d.address}") }
                    else status("Bluetooth ready: tap Connect (pair the PC/TV first)")
                } else {
                    registered = false
                    // ignore the "false" that our own unregister-before-register produces
                    if (SystemClock.elapsedRealtime() >= ignoreFalseUntil) {
                        handler.removeCallbacks(watchdog); retry("registration refused")
                    }
                }
            }
        }
        override fun onConnectionStateChanged(d: BluetoothDevice, s: Int) {
            act.runOnUiThread {
                when (s) {
                    BluetoothProfile.STATE_CONNECTED -> { host = d; status("Bluetooth connected: ${d.name ?: d.address}") }
                    BluetoothProfile.STATE_CONNECTING -> status("Connecting to ${d.name ?: d.address}…")
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        if (d == host || host == null) { host = null; status("Bluetooth disconnected: tap Connect") }
                    }
                    else -> {}
                }
            }
        }
    }

    private fun retry(reason: String) {
        if (closing) return
        if (attempts < maxAttempts) {
            attempts++
            status("Bluetooth HID: $reason, retrying ($attempts/$maxAttempts)…")
            handler.postDelayed({ if (attempts >= 3) reopenProxy() else register() }, 1000L * attempts)
        } else {
            status("Bluetooth HID failed (${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}). " +
                "Tap this status bar to retry. If it keeps failing: turn Bluetooth off/on, close other keyboard/mouse apps, " +
                "or use Network mode.")
        }
    }

    /** Drops the Bluetooth profile connection and opens a fresh one (clears a stuck HID state). */
    private fun reopenProxy() {
        if (closing) return
        try {
            act.getSystemService(BluetoothManager::class.java)?.adapter
                ?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid)
        } catch (e: Exception) {}
        hid = null; registered = false; starting = false; host = null
        handler.postDelayed({ start() }, 800)
    }

    /** User-triggered full restart (tap on the status bar). */
    fun restart() {
        if (closing) return
        handler.removeCallbacksAndMessages(null)
        attempts = 0
        status("Restarting Bluetooth HID…")
        reopenProxy()
    }

    /** Called from onResume: starts, or revives a registration that was lost while the app was away. */
    fun ensure() {
        if (closing) return
        if (hid == null) start()
        else if (!registered && attempts >= maxAttempts) restart()
    }

    /** Disconnects stale hosts, clears any old registration, then registers as a HID device. */
    private fun register() {
        val h = hid ?: return
        if (closing) return
        ignoreFalseUntil = SystemClock.elapsedRealtime() + 1100
        host = null
        try { for (d in h.connectedDevices) h.disconnect(d) } catch (e: Exception) {}
        try { h.unregisterApp() } catch (e: Exception) {}
        handler.postDelayed({
            if (closing) return@postDelayed
            val sdp = BluetoothHidDeviceAppSdpSettings(
                if (tvMode) "PhonePad Remote" else "PhonePad", "Phone controller", "PhonePad",
                if (tvMode) BluetoothHidDevice.SUBCLASS1_KEYBOARD else BluetoothHidDevice.SUBCLASS1_COMBO, desc)
            val ok = try { h.registerApp(sdp, null, null, act.mainExecutor, cb) }
                     catch (e: Exception) { status("Bluetooth HID error: ${e.message}"); false }
            if (ok) handler.postDelayed(watchdog, 5000) else retry("request rejected")
        }, 800)
    }

    /** Safe to call repeatedly; does nothing once started. */
    fun start() {
        if (hid != null || starting || closing) return
        val adapter = act.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) { status("This device has no Bluetooth"); return }
        if (!adapter.isEnabled) { status("Turn Bluetooth on, then come back to this app"); return }
        starting = true
        status("Starting Bluetooth…")
        val ok = adapter.getProfileProxy(act, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                starting = false
                hid = proxy as BluetoothHidDevice
                register()
            }
            override fun onServiceDisconnected(p: Int) { hid = null; registered = false; host = null; starting = false }
        }, BluetoothProfile.HID_DEVICE)
        if (!ok) { starting = false; status("Bluetooth HID is not available on this phone: tap Mode to use Network") }
    }

    // ---------- pairing / connecting ----------
    fun pick() {
        if (!registered) { status("Bluetooth HID not ready: restarting…"); restart(); return }
        val adapter = act.getSystemService(BluetoothManager::class.java)?.adapter
        val devs = adapter?.bondedDevices?.toList() ?: emptyList()
        val labels = devs.map { it.name ?: it.address } + "🔍  Scan & pair a new device…"
        AlertDialog.Builder(act).setTitle("Connect to")
            .setItems(labels.toTypedArray()) { _, i -> if (i == devs.size) scanDialog() else connectTo(devs[i]) }
            .show()
    }

    private fun connectTo(d: BluetoothDevice) {
        status("Connecting to ${d.name ?: d.address}…")
        if (hid?.connect(d) != true) status("Connect refused: remove the old pairing in Bluetooth settings, then scan & pair again")
    }

    @Suppress("DEPRECATION")
    private fun dev(i: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)

    private fun stopScan() {
        scanRx?.let { try { act.unregisterReceiver(it) } catch (e: Exception) {} }
        scanRx = null
    }

    /** Lists nearby Bluetooth devices (TVs, PCs); tap one to pair, then it connects automatically. */
    private fun scanDialog() {
        val adapter = act.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        val perms = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN)
                    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (perms.any { act.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            status("Allow the permission, then tap Connect again to scan (turn Location on for Android 11 and older)")
            act.requestPermissions(perms, 3); return
        }
        stopScan()
        val found = ArrayList<BluetoothDevice>()
        val names = ArrayList<String>()
        val lv = ListView(act)
        val ad = ArrayAdapter(act, android.R.layout.simple_list_item_1, names)
        lv.adapter = ad
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val d = dev(i) ?: return
                        val n = d.name ?: return
                        if (found.none { it.address == d.address }) {
                            found.add(d); names.add("$n   (${d.address.takeLast(5)})"); ad.notifyDataSetChanged()
                        }
                    }
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        val d = dev(i) ?: return
                        if (d.address != pairing?.address) return
                        when (i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)) {
                            BluetoothDevice.BOND_BONDED -> {
                                status("Paired: connecting to ${d.name ?: d.address}…")
                                pairing = null; stopScan(); hid?.connect(d)
                            }
                            BluetoothDevice.BOND_NONE -> { status("Pairing failed or declined: try again"); pairing = null; stopScan() }
                        }
                    }
                }
            }
        }
        val f = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND); addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) act.registerReceiver(rx, f, Context.RECEIVER_EXPORTED) else act.registerReceiver(rx, f)
        scanRx = rx
        val dlg = AlertDialog.Builder(act).setTitle("Put the TV/PC in pairing mode, then tap it")
            .setView(lv).setNegativeButton("Close", null).create()
        dlg.setOnDismissListener {
            try { adapter.cancelDiscovery() } catch (e: Exception) {}
            if (pairing == null) stopScan()
        }
        lv.setOnItemClickListener { _, _, pos, _ ->
            val d = found[pos]
            try { adapter.cancelDiscovery() } catch (e: Exception) {}
            if (d.bondState == BluetoothDevice.BOND_BONDED) { stopScan(); connectTo(d) }
            else { pairing = d; status("Pairing with ${d.name}… accept the prompt on the phone and on the TV/PC"); d.createBond() }
            dlg.dismiss()
        }
        try { adapter.cancelDiscovery() } catch (e: Exception) {}
        if (!adapter.startDiscovery()) status("Could not start scanning: check Bluetooth (and Location on older Android)")
        dlg.show()
    }

    // ---------- sending ----------
    private fun warn(s: String) {
        val n = SystemClock.elapsedRealtime()
        if (n - lastWarn > 2500) { lastWarn = n; act.runOnUiThread { status(s) } }
    }

    private fun send(id: Int, data: ByteArray) {
        val h = hid; val d = host
        if (h == null || !registered) { warn("Bluetooth HID not ready: tap this status bar to restart"); return }
        if (d == null) { warn("Not connected: tap Connect"); return }
        try { h.sendReport(d, id, data) } catch (e: Exception) {}
    }

    private fun m(w: Int, dx: Int, dy: Int) {
        if (tvMode) return                                   // no mouse in TV mode
        send(1, byteArrayOf(btn.toByte(), dx.toByte(), dy.toByte(), w.toByte()))
    }

    override fun mouse(dx: Int, dy: Int, w: Int) {
        if (tvMode) {                                        // scroll strip -> up/down keys
            if (w != 0) repeat(min(abs(w), 3)) { key(if (w > 0) 0x52 else 0x51) }
            return
        }
        var x = dx; var y = dy; var s = w
        while (x != 0 || y != 0 || s != 0) {                 // split big moves instead of dropping the excess
            val cx = x.coerceIn(-127, 127); val cy = y.coerceIn(-127, 127); val cs = s.coerceIn(-127, 127)
            m(cs, cx, cy); x -= cx; y -= cy; s -= cs
        }
    }
    override fun buttons(mask: Int) { btn = mask and 7; m(0, 0, 0) }
    override fun click(b: Int) { val keep = btn; btn = keep or b; m(0, 0, 0); btn = keep; m(0, 0, 0) }
    override fun key(code: Int, mod: Int) {
        send(2, byteArrayOf(mod.toByte(), 0, code.toByte(), 0, 0, 0, 0, 0))
        send(2, ByteArray(8))
    }
    override fun media(bit: Int) { send(3, byteArrayOf(bit.toByte())); send(3, byteArrayOf(0)) }

    override fun close() {
        closing = true
        handler.removeCallbacksAndMessages(null)
        stopScan()
        try {
            hid?.unregisterApp()
            act.getSystemService(BluetoothManager::class.java)?.adapter
                ?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid)
        } catch (e: Exception) {}
        hid = null; host = null; registered = false; starting = false
    }
}

/**
 * Network backend (works on every phone, any Android version): sends simple text commands over TCP
 * to phonepad_receiver.py running on the PC (Wi-Fi, hotspot or USB tethering).
 */
class NetBackend(private val act: Activity, private val status: (String) -> Unit) : Backend {
    private val q = LinkedBlockingQueue<String>(4000)
    @Volatile private var sock: Socket? = null
    @Volatile private var gen = 0
    private var lastWarn = 0L

    private fun ui(s: String) = act.runOnUiThread { status(s) }

    /** [addr] is "ip" or "ip:port" (default port 8765). */
    fun connect(addr: String) {
        val g = ++gen
        try { sock?.close() } catch (e: Exception) {}
        sock = null; q.clear()
        val host = addr.substringBefore(":")
        val port = addr.substringAfter(":", "8765").toIntOrNull() ?: 8765
        ui("Connecting to $host:$port…")
        Thread {
            var s: Socket? = null
            try {
                s = Socket()
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(host, port), 5000)
                if (g == gen) {
                    sock = s
                    ui("Network connected: $host")
                    val sk = s
                    Thread {                       // notice when the PC closes the connection
                        try { while (sk.getInputStream().read() >= 0) {} } catch (e: Exception) {}
                        if (g == gen) { sock = null; ui("Network disconnected: tap Connect") }
                    }.start()
                    val out = s.getOutputStream()
                    while (g == gen) {
                        val line = q.poll(1, TimeUnit.SECONDS) ?: continue
                        out.write((line + "\n").toByteArray()); out.flush()
                    }
                }
            } catch (e: Exception) {
                if (g == gen) { sock = null; ui("Network error: ${e.message ?: "cannot reach PC"}. Is phonepad_receiver.py running?") }
            } finally {
                try { s?.close() } catch (e: Exception) {}
            }
        }.start()
    }

    private fun send(line: String) {
        if (sock == null) {
            val n = SystemClock.elapsedRealtime()
            if (n - lastWarn > 2500) { lastWarn = n; ui("Not connected: tap Connect and enter the PC address") }
            return
        }
        q.offer(line)
    }

    override fun mouse(dx: Int, dy: Int, w: Int) = send("M $dx $dy $w")
    override fun buttons(mask: Int) = send("B ${mask and 7}")
    override fun click(b: Int) = send("C $b")
    override fun key(code: Int, mod: Int) = send("K $code $mod")
    override fun media(bit: Int) = send("D $bit")
    override fun close() { gen++; try { sock?.close() } catch (e: Exception) {}; sock = null }
}

package com.kar.phonepad

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

interface Backend {
    fun mouse(dx: Int, dy: Int, w: Int)
    fun buttons(mask: Int)       // held buttons: 1 = left, 2 = right, 4 = middle (for drag)
    fun click(b: Int)            // 1 = left, 2 = right, 4 = middle
    fun key(code: Int, mod: Int = 0)   // HID usage code + modifier bitmask (1 ctrl, 2 shift, 4 alt, 8 win)
    fun media(bit: Int)          // 1 = vol+, 2 = vol-, 4 = mute
    fun close() {}
}

/** Real Bluetooth HID mouse/keyboard/remote. Needs Android 9+ (API 28). */
@TargetApi(28)
@SuppressLint("MissingPermission")
class HidBackend(private val act: Activity, private val status: (String) -> Unit) : Backend {
    private var hid: BluetoothHidDevice? = null
    @Volatile private var host: BluetoothDevice? = null
    @Volatile private var registered = false
    private var closing = false
    private var starting = false
    private var attempts = 0
    private var ignoreFalseUntil = 0L
    private var btn = 0
    private var lastWarn = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val watchdog = Runnable { if (!registered && !closing) retry("no answer from the Bluetooth system") }

    private val desc: ByteArray = intArrayOf(
        0x05,0x01,0x09,0x02,0xA1,0x01,0x85,0x01,0x09,0x01,0xA1,0x00,0x05,0x09,0x19,0x01,0x29,0x03,
        0x15,0x00,0x25,0x01,0x95,0x03,0x75,0x01,0x81,0x02,0x95,0x01,0x75,0x05,0x81,0x03,
        0x05,0x01,0x09,0x30,0x09,0x31,0x09,0x38,0x15,0x81,0x25,0x7F,0x75,0x08,0x95,0x03,0x81,0x06,0xC0,0xC0,
        0x05,0x01,0x09,0x06,0xA1,0x01,0x85,0x02,0x05,0x07,0x19,0xE0,0x29,0xE7,0x15,0x00,0x25,0x01,
        0x75,0x01,0x95,0x08,0x81,0x02,0x95,0x01,0x75,0x08,0x81,0x01,0x95,0x06,0x75,0x08,0x15,0x00,
        0x25,0x65,0x05,0x07,0x19,0x00,0x29,0x65,0x81,0x00,0xC0,
        0x05,0x0C,0x09,0x01,0xA1,0x01,0x85,0x03,0x15,0x00,0x25,0x01,0x75,0x01,0x95,0x03,
        0x09,0xE9,0x09,0xEA,0x09,0xE2,0x81,0x02,0x95,0x05,0x81,0x03,0xC0
    ).map { it.toByte() }.toByteArray()

    private val cb = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(d: BluetoothDevice?, reg: Boolean) {
            act.runOnUiThread {
                if (closing) return@runOnUiThread
                if (reg) {
                    handler.removeCallbacks(watchdog)
                    registered = true; attempts = 0
                    if (d != null) { host = d; status("Bluetooth connected: ${d.name ?: d.address}") }
                    else status("Bluetooth ready: tap Visible, pair from the PC, then Connect")
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
                    BluetoothProfile.STATE_DISCONNECTED -> if (d == host || host == null) {
                        host = null; status("Bluetooth disconnected: tap Connect")
                    }
                    else -> {}
                }
            }
        }
    }

    private fun retry(reason: String) {
        if (closing) return
        if (attempts < 3) {
            attempts++
            status("Bluetooth HID: $reason, retrying ($attempts/3)…")
            handler.postDelayed({ register() }, 1000)
        } else {
            status("Bluetooth HID failed on this phone (${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}). " +
                "Turn Bluetooth off and on, close other keyboard/mouse apps, reopen PhonePad. " +
                "If it still fails your phone blocks it: tap Mode to use Network.")
        }
    }

    /** Clears any stale registration, then registers as a HID combo device. */
    private fun register() {
        val h = hid ?: return
        if (closing) return
        ignoreFalseUntil = SystemClock.elapsedRealtime() + 600
        try { h.unregisterApp() } catch (e: Exception) {}
        handler.postDelayed({
            if (closing) return@postDelayed
            val sdp = BluetoothHidDeviceAppSdpSettings(
                "PhonePad", "Phone touchpad", "PhonePad", BluetoothHidDevice.SUBCLASS1_COMBO, desc)
            val ok = try { h.registerApp(sdp, null, null, act.mainExecutor, cb) }
                     catch (e: Exception) { status("Bluetooth HID error: ${e.message}"); false }
            if (ok) handler.postDelayed(watchdog, 5000) else retry("request rejected")
        }, 700)
    }

    /** Safe to call repeatedly (e.g. from onResume); does nothing once started. */
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
                attempts = 0
                register()
            }
            override fun onServiceDisconnected(p: Int) { hid = null; registered = false; host = null; starting = false }
        }, BluetoothProfile.HID_DEVICE)
        if (!ok) { starting = false; status("Bluetooth HID is not available on this phone: tap Mode to use Network") }
    }

    fun pick() {
        if (!registered) { status("Bluetooth HID is not ready yet (see the message above)"); return }
        val adapter = act.getSystemService(BluetoothManager::class.java)?.adapter
        val devs = adapter?.bondedDevices?.toList() ?: emptyList()
        if (devs.isEmpty()) { status("No paired devices: tap Visible and pair from the PC first"); return }
        AlertDialog.Builder(act).setTitle("Connect to")
            .setItems(devs.map { it.name ?: it.address }.toTypedArray()) { _, i ->
                status("Connecting to ${devs[i].name ?: devs[i].address}…")
                if (hid?.connect(devs[i]) != true) status("Connect refused: pair again from the PC, then retry")
            }.show()
    }

    private fun warn(s: String) {
        val n = SystemClock.elapsedRealtime()
        if (n - lastWarn > 2500) { lastWarn = n; act.runOnUiThread { status(s) } }
    }

    private fun send(id: Int, data: ByteArray) {
        val h = hid; val d = host
        if (h == null || !registered) { warn("Bluetooth HID is not ready (see Mode / Visible)"); return }
        if (d == null) { warn("Not connected to a PC: pair it, then tap Connect"); return }
        try { h.sendReport(d, id, data) } catch (e: Exception) {}
    }

    private fun m(w: Int, dx: Int, dy: Int) =
        send(1, byteArrayOf(btn.toByte(), dx.toByte(), dy.toByte(), w.toByte()))

    override fun mouse(dx: Int, dy: Int, w: Int) {
        var x = dx; var y = dy; var s = w
        while (x != 0 || y != 0 || s != 0) {          // split big moves instead of dropping the excess
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

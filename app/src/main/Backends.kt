package com.kar.phonepad

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.*
import android.content.Context
import android.os.Build

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
    private var btn = 0
    private var starting = false

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
        override fun onAppStatusChanged(d: BluetoothDevice?, registered: Boolean) {
            act.runOnUiThread {
                status(if (registered) "Bluetooth ready: tap Visible, pair from the PC, then Connect"
                       else "Bluetooth HID registration failed (some phones block this)")
            }
        }
        override fun onConnectionStateChanged(d: BluetoothDevice, s: Int) {
            act.runOnUiThread {
                if (s == BluetoothProfile.STATE_CONNECTED) { host = d; status("Bluetooth connected: ${d.name ?: d.address}") }
                else if (s == BluetoothProfile.STATE_DISCONNECTED && d == host) { host = null; status("Bluetooth disconnected") }
            }
        }
    }

    /** Safe to call repeatedly (e.g. from onResume); does nothing once started. */
    fun start() {
        if (hid != null || starting) return
        val adapter = act.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) { status("This device has no Bluetooth"); return }
        if (!adapter.isEnabled) { status("Turn Bluetooth on, then come back to this app"); return }
        starting = true
        val ok = adapter.getProfileProxy(act, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                starting = false
                val h = proxy as BluetoothHidDevice
                hid = h
                val sdp = BluetoothHidDeviceAppSdpSettings(
                    "PhonePad", "Phone touchpad", "PhonePad", BluetoothHidDevice.SUBCLASS1_COMBO, desc)
                try { h.registerApp(sdp, null, null, act.mainExecutor, cb) }
                catch (e: Exception) { status("Bluetooth HID error: ${e.message}") }
            }
            override fun onServiceDisconnected(p: Int) { hid = null; starting = false }
        }, BluetoothProfile.HID_DEVICE)
        if (!ok) { starting = false; status("Bluetooth HID is not available on this phone") }
    }

    fun pick() {
        val adapter = act.getSystemService(BluetoothManager::class.java)?.adapter
        val devs = adapter?.bondedDevices?.toList() ?: emptyList()
        if (devs.isEmpty()) { status("No paired devices: tap Visible and pair from the PC first"); return }
        AlertDialog.Builder(act).setTitle("Connect to")
            .setItems(devs.map { it.name ?: it.address }.toTypedArray()) { _, i -> hid?.connect(devs[i]) }.show()
    }

    private fun send(id: Int, data: ByteArray) {
        val h = hid; val d = host
        if (h == null || d == null) return
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
        try {
            hid?.unregisterApp()
            act.getSystemService(BluetoothManager::class.java)?.adapter
                ?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid)
        } catch (e: Exception) {}
        hid = null; host = null; starting = false
    }
}

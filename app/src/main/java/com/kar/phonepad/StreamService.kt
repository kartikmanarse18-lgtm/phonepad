package com.kar.phonepad

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.net.Inet4Address
import java.net.NetworkInterface

/** Foreground service: keeps camera + mic streaming even when the screen is off. */
class StreamService : Service() {
    companion object {
        const val PORT = 8080
        @Volatile var instance: StreamService? = null
        @Volatile var running = false
        @Volatile var urls: List<String> = emptyList()
        @Volatile var onState: (() -> Unit)? = null

        fun localUrls(): List<String> {
            val l = ArrayList<String>()
            try {
                for (ni in NetworkInterface.getNetworkInterfaces().toList()) {
                    if (!ni.isUp || ni.isLoopback) continue
                    for (a in ni.inetAddresses.toList()) if (a is Inet4Address) l.add("http://${a.hostAddress}:$PORT/")
                }
            } catch (e: Exception) {}
            return l
        }
    }

    private var server: StreamServer? = null
    private var cam: CameraStreamer? = null
    private var mic: MicStreamer? = null
    private var wl: PowerManager.WakeLock? = null

    override fun onBind(i: Intent?): IBinder? = null

    fun flip() { cam?.flip() }
    fun rotate() { cam?.rotate() }

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        val useCam = i?.getBooleanExtra("cam", false) ?: false
        val useMic = i?.getBooleanExtra("mic", false) ?: false
        if (!useCam && !useMic) { stopSelf(); return START_NOT_STICKY }
        try {
            goForeground(useCam, useMic)
            stopAll()
            val fh = if (useCam) FrameHub() else null
            val ah = if (useMic) AudioHub() else null
            val m = if (useMic && ah != null) MicStreamer(ah) else null
            if (m != null && !m.start()) { /* mic unavailable: continue with video only */ }
            mic = m
            if (fh != null) { cam = CameraStreamer(fh).also { it.start() } }
            server = StreamServer(PORT, fh, ah, 16000).also { it.start() }
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "phonepad:stream").also { it.acquire() }
            instance = this; urls = localUrls(); running = true
        } catch (e: Exception) {
            stopAll(); running = false; stopSelf()
        }
        onState?.invoke()
        return START_NOT_STICKY
    }

    private fun goForeground(useCam: Boolean, useMic: Boolean) {
        val chan = "stream"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(NotificationChannel(chan, "Streaming", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0))
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, chan) else Notification.Builder(this)
        val n = b.setContentTitle("PhonePad streaming").setContentText("Phone camera/mic are being shared. Tap to stop.")
            .setSmallIcon(android.R.drawable.presence_video_online).setContentIntent(pi).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 30) {
            var t = 0
            if (useCam) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (useMic) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            startForeground(1, n, t)
        } else startForeground(1, n)
    }

    private fun stopAll() {
        try { server?.stop() } catch (e: Exception) {}
        try { cam?.stop() } catch (e: Exception) {}
        try { mic?.stop() } catch (e: Exception) {}
        try { if (wl?.isHeld == true) wl?.release() } catch (e: Exception) {}
        server = null; cam = null; mic = null; wl = null
    }

    override fun onDestroy() {
        stopAll(); running = false; instance = null; urls = emptyList()
        onState?.invoke()
        super.onDestroy()
    }
}

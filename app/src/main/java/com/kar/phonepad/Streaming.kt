package com.kar.phonepad

import android.annotation.SuppressLint
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.YuvImage
import android.hardware.Camera
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.abs
import kotlin.math.max

class FrameHub {
    private val lk = ReentrantLock()
    private val cond = lk.newCondition()
    private var jpeg: ByteArray? = null
    private var seq = 0L
    fun put(b: ByteArray) { lk.withLock { jpeg = b; seq++; cond.signalAll() } }
    /** waits for a frame newer than [after]; null on timeout */
    fun next(after: Long, timeoutMs: Long): Pair<ByteArray, Long>? {
        lk.lock()
        try {
            var left = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (seq <= after || jpeg == null) {
                if (left <= 0) return null
                left = cond.awaitNanos(left)
            }
            return Pair(jpeg!!, seq)
        } finally { lk.unlock() }
    }
}

class AudioHub {
    private val subs = CopyOnWriteArrayList<LinkedBlockingQueue<ByteArray>>()
    fun subscribe(): LinkedBlockingQueue<ByteArray> = LinkedBlockingQueue<ByteArray>(64).also { subs.add(it) }
    fun unsubscribe(q: LinkedBlockingQueue<ByteArray>) { subs.remove(q) }
    fun publish(b: ByteArray) { for (q in subs) q.offer(b) }   // slow clients just drop audio
}

/** Phone camera -> JPEG frames (Camera1 API: works on every Android version without extra libraries). */
@Suppress("DEPRECATION")
class CameraStreamer(private val hub: FrameHub) {
    private val thread = HandlerThread("phonepad-cam").also { it.start() }
    private val h = Handler(thread.looper)
    private var cam: Camera? = null
    private var camId = 0
    @Volatile private var extraRot = 0

    fun start() { h.post { open(0) } }
    fun flip() { h.post { val n = Camera.getNumberOfCameras(); if (n > 1) { release(); open((camId + 1) % n) } } }
    fun rotate() { extraRot = (extraRot + 90) % 360 }
    fun stop() { h.post { release(); thread.quitSafely() } }

    private fun release() {
        try { cam?.setPreviewCallback(null); cam?.stopPreview(); cam?.release() } catch (e: Exception) {}
        cam = null
    }

    private fun open(id: Int) {
        try {
            camId = id
            val info = Camera.CameraInfo(); Camera.getCameraInfo(id, info)
            val sensor = info.orientation
            val c = Camera.open(id)
            val p = c.parameters
            val sz = p.supportedPreviewSizes.minByOrNull { abs(it.width - 640) + abs(it.height - 480) }!!
            p.setPreviewSize(sz.width, sz.height)
            p.previewFormat = ImageFormat.NV21
            if (p.supportedFocusModes?.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO) == true)
                p.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO
            c.parameters = p
            c.setPreviewTexture(SurfaceTexture(10))
            val w = sz.width; val hh = sz.height
            c.setPreviewCallback { data, _ ->
                try {
                    val rot = (sensor + extraRot) % 360
                    val rw = if (rot == 90 || rot == 270) hh else w
                    val rh = if (rot == 90 || rot == 270) w else hh
                    val out = ByteArrayOutputStream(64 * 1024)
                    YuvImage(rotate(data, w, hh, rot), ImageFormat.NV21, rw, rh, null)
                        .compressToJpeg(Rect(0, 0, rw, rh), 70, out)
                    hub.put(out.toByteArray())
                } catch (e: Exception) {}
            }
            c.startPreview()
            cam = c
        } catch (e: Exception) { release() }
    }

    private fun rotate(s: ByteArray, w: Int, h: Int, deg: Int): ByteArray {
        if (deg == 0) return s
        val d = ByteArray(s.size); val fs = w * h
        when (deg) {
            90 -> {
                for (y in 0 until h) for (x in 0 until w) d[x * h + (h - 1 - y)] = s[y * w + x]
                for (y in 0 until h / 2) for (x in 0 until w / 2) {
                    val si = fs + y * w + x * 2; val di = fs + x * h + (h / 2 - 1 - y) * 2
                    d[di] = s[si]; d[di + 1] = s[si + 1]
                }
            }
            180 -> {
                for (i in 0 until fs) d[fs - 1 - i] = s[i]
                for (q in 0 until fs / 4) {
                    val si = fs + q * 2; val di = fs + (fs / 4 - 1 - q) * 2
                    d[di] = s[si]; d[di + 1] = s[si + 1]
                }
            }
            else -> { // 270
                for (y in 0 until h) for (x in 0 until w) d[(w - 1 - x) * h + y] = s[y * w + x]
                for (y in 0 until h / 2) for (x in 0 until w / 2) {
                    val si = fs + y * w + x * 2; val di = fs + (w / 2 - 1 - x) * h + y * 2
                    d[di] = s[si]; d[di + 1] = s[si + 1]
                }
            }
        }
        return d
    }
}

/** Phone microphone -> 16 kHz mono 16-bit PCM. */
class MicStreamer(private val hub: AudioHub, val rate: Int = 16000) {
    @Volatile private var run = false
    private var rec: AudioRecord? = null

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, max(min, 4096) * 2)
        if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); return false }
        rec = r; run = true; r.startRecording()
        Thread {
            val buf = ByteArray(2048)
            try {
                while (run) { val n = r.read(buf, 0, buf.size); if (n > 0) hub.publish(buf.copyOf(n)) else if (n < 0) break }
            } finally { try { r.stop() } catch (e: Exception) {}; r.release() }
        }.start()
        return true
    }
    fun stop() { run = false }
}

/** Tiny HTTP server: / (page), /video (MJPEG), /snapshot.jpg, /audio (WAV stream), /audio.pcm (raw). */
class StreamServer(private val port: Int, private val frames: FrameHub?, private val audio: AudioHub?, private val rate: Int) {
    private var ss: ServerSocket? = null
    private val clients: MutableSet<Socket> = Collections.synchronizedSet(HashSet<Socket>())
    @Volatile private var running = false

    fun start() {
        val s = ServerSocket(); s.reuseAddress = true; s.bind(InetSocketAddress(port))
        ss = s; running = true
        Thread {
            while (running) {
                try { val c = s.accept(); clients.add(c); Thread { handle(c) }.start() }
                catch (e: Exception) { break }
            }
        }.start()
    }

    fun stop() {
        running = false
        try { ss?.close() } catch (e: Exception) {}
        synchronized(clients) { for (c in clients) try { c.close() } catch (e: Exception) {}; clients.clear() }
    }

    private fun handle(c: Socket) {
        try {
            c.tcpNoDelay = true; c.soTimeout = 5000
            val rd = c.getInputStream().bufferedReader()
            val line = rd.readLine() ?: return
            while (true) { val hl = rd.readLine() ?: break; if (hl.isEmpty()) break }
            c.soTimeout = 0
            val path = line.split(" ").getOrElse(1) { "/" }.substringBefore("?")
            val out = BufferedOutputStream(c.getOutputStream(), 16 * 1024)
            val f = frames; val a = audio
            when {
                path == "/" -> page(out)
                path == "/video" && f != null -> mjpeg(out, f)
                path == "/snapshot.jpg" && f != null -> snap(out, f)
                path == "/audio" && a != null -> pcm(out, a, true)
                path == "/audio.pcm" && a != null -> pcm(out, a, false)
                else -> reply(out, "404 Not Found", "text/plain", "Not found".toByteArray())
            }
        } catch (e: Exception) {
        } finally { try { c.close() } catch (e: Exception) {}; clients.remove(c) }
    }

    private fun reply(out: BufferedOutputStream, status: String, type: String, body: ByteArray) {
        out.write("HTTP/1.0 $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nCache-Control: no-cache\r\n\r\n".toByteArray())
        out.write(body); out.flush()
    }

    private fun page(out: BufferedOutputStream) {
        val sb = StringBuilder("<!DOCTYPE html><html><head><meta charset=utf-8><title>PhonePad</title></head>" +
            "<body style=\"margin:0;background:#111;color:#eee;font-family:sans-serif\">")
        if (frames != null) sb.append("<img src=\"/video\" style=\"max-width:100%\"><br>")
        if (audio != null) sb.append("<audio controls autoplay src=\"/audio\"></audio>")
        sb.append("<p style=\"padding:8px\">")
        if (frames != null) sb.append("Video: /video &middot; Snapshot: /snapshot.jpg<br>")
        if (audio != null) sb.append("Audio: /audio (WAV) &middot; /audio.pcm (raw s16le, $rate Hz, mono)")
        sb.append("</p></body></html>")
        reply(out, "200 OK", "text/html; charset=utf-8", sb.toString().toByteArray())
    }

    private fun mjpeg(out: BufferedOutputStream, fh: FrameHub) {
        out.write(("HTTP/1.0 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=frame\r\n" +
            "Cache-Control: no-cache\r\nPragma: no-cache\r\n\r\n").toByteArray()); out.flush()
        var last = 0L
        while (running) {
            val fr = fh.next(last, 2000) ?: continue
            last = fr.second
            out.write("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${fr.first.size}\r\n\r\n".toByteArray())
            out.write(fr.first); out.write("\r\n".toByteArray()); out.flush()
        }
    }

    private fun snap(out: BufferedOutputStream, fh: FrameHub) {
        val fr = fh.next(0, 3000)
        if (fr == null) reply(out, "503 Service Unavailable", "text/plain", "No frame yet".toByteArray())
        else reply(out, "200 OK", "image/jpeg", fr.first)
    }

    private fun pcm(out: BufferedOutputStream, ah: AudioHub, wav: Boolean) {
        val q = ah.subscribe()
        try {
            val type = if (wav) "audio/wav" else "audio/L16;rate=$rate;channels=1"
            out.write("HTTP/1.0 200 OK\r\nContent-Type: $type\r\nCache-Control: no-cache\r\n\r\n".toByteArray())
            if (wav) out.write(wavHeader())
            out.flush()
            while (running) { val b = q.poll(1, TimeUnit.SECONDS) ?: continue; out.write(b); out.flush() }
        } finally { ah.unsubscribe(q) }
    }

    private fun wavHeader(): ByteArray {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt(0x7FFFFFFF); b.put("WAVEfmt ".toByteArray())
        b.putInt(16); b.putShort(1); b.putShort(1); b.putInt(rate); b.putInt(rate * 2)
        b.putShort(2); b.putShort(16); b.put("data".toByteArray()); b.putInt(0x7FFFFFFF)
        return b.array()
    }
}

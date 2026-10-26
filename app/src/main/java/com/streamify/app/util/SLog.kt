package com.streamify.app.util

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SLog — application-wide logging facade with OPT-IN diagnostic capture.
 *
 * ── OFF (default for every user) ──────────────────────────────────────────
 * append() = one volatile read + forward to android.util.Log. Zero allocation,
 * zero locks, zero disk I/O. Touch/nav/HTTP/lifecycle logging costs ~nothing.
 *
 * ── ON (user toggles it in Settings; self-disables after 2h) ──────────────
 * Frames are written into ONE pre-allocated DirectByteBuffer (off the GC heap):
 *   [int len][long tsMs][byte level][short tagLen][tag utf8][short msgLen][msg utf8][int MAGIC]
 * Appends perform no Java-object allocation beyond the byte[] copies of the
 * tag/message themselves. Formatting + credential redaction happen at READ
 * time (viewer / export / batched disk flusher) — never on the hot path.
 *
 * Disk spool only runs while capture is enabled; buffer auto-wipes and the
 * direct buffer is released when disabled.
 */
object SLog {

    // ---------------------------------------------------------- pluggable sink
    // Strict-mock migration (sprint P1): the android.util.Log calls used to be
    // hardwired here, which is why `isReturnDefaultValues = true` existed —
    // JVM unit tests crashed with "Method ... not mocked" unless every
    // framework call silently returned 0/null. The logging backend is now a
    // pluggable [Sink] so JVM tests install an explicit fake
    // (see app/src/test/java/com/streamify/app/testkit/JvmSLogSink.kt)
    // instead of the build masking the entire framework surface.
    internal interface Sink {
        fun v(tag: String, msg: String, tr: Throwable?)
        fun d(tag: String, msg: String, tr: Throwable?)
        fun i(tag: String, msg: String, tr: Throwable?)
        fun w(tag: String, msg: String, tr: Throwable?)
        fun e(tag: String, msg: String, tr: Throwable?)
        fun wtf(tag: String, msg: String, tr: Throwable?)
        fun stackTraceString(tr: Throwable): String
    }

    /** Production sink: forwards 1:1 to android.util.Log (identical behavior). */
    private object AndroidSink : Sink {
        override fun v(tag: String, msg: String, tr: Throwable?) = android.util.Log.v(tag, msg, tr).let { Unit }
        override fun d(tag: String, msg: String, tr: Throwable?) = android.util.Log.d(tag, msg, tr).let { Unit }
        override fun i(tag: String, msg: String, tr: Throwable?) = android.util.Log.i(tag, msg, tr).let { Unit }
        override fun w(tag: String, msg: String, tr: Throwable?) = android.util.Log.w(tag, msg, tr).let { Unit }
        override fun e(tag: String, msg: String, tr: Throwable?) = android.util.Log.e(tag, msg, tr).let { Unit }
        override fun wtf(tag: String, msg: String, tr: Throwable?) = android.util.Log.wtf(tag, msg, tr).let { Unit }
        override fun stackTraceString(tr: Throwable): String = android.util.Log.getStackTraceString(tr)
    }

    /** Swap point for tests. Never call this from production code. */
    @Volatile
    internal var sink: Sink = AndroidSink

    // -------------------------------------------------------------- levels
    const val LEVEL_V = 'V'
    const val LEVEL_D = 'D'
    const val LEVEL_I = 'I'
    const val LEVEL_W = 'W'
    const val LEVEL_E = 'E'
    const val LEVEL_F = 'F'

    private const val TAG_INIT = "SLog"
    private const val SESSION_FILE = "session-current.log"
    private const val MAX_FILE_BYTES = 5L * 1024 * 1024
    private const val ROTATE_KEEP = 2
    private const val RING_CAPACITY = 4 * 1024 * 1024 // 4MB off-heap
    private const val FRAME_MAGIC = 0x534C4F47.toInt() // 'SLOG'
    private const val AUTO_OFF_MS = 2L * 60 * 60 * 1000 // 2 hours
    private const val MAX_TAG = 64
    private const val MAX_MSG = 3072

    // -------------------------------------------------------------- state
    @Volatile
    var captureEnabled: Boolean = false
        private set

    private var appContext: Context? = null
    private var prefs: android.content.SharedPreferences? = null
    private val armingLock = Any()
    private var autoOffJob: Job? = null
    private var flushJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Off-heap ring (created on arm, released on disarm)
    private var ring: ByteBuffer? = null
    private val ringLock = Any()
    private var writeOffset = 0
    private var validBytes = 0
    private val frameListeners = CopyOnWriteArraySet<() -> Unit>()

    private lateinit var logDir: File
    private val tsFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val started = AtomicBoolean(false)

    // Precompiled redaction patterns — applied at READ time only.
    private val RX_SAPISID = Regex("(SAPISIDHASH\\s)[A-Za-z0-9_]+")
    private val RX_COOKIE = Regex("(?i)(cookie:\\s*?)[^\\n]{8,}")
    private val RX_SIG = Regex("(?i)((?:signature|sig|s)=)[^&\\s\"']+")

    fun redact(msg: String): String {
        if (!msg.contains("SAPISID") && !msg.contains("Cookie", ignoreCase = true) &&
            !msg.contains("signature=", ignoreCase = true)
        ) return msg
        return msg
            .replace(RX_SAPISID, "$1<redacted>")
            .replace(RX_COOKIE, "$1<redacted>")
            .replace(RX_SIG, "$1<redacted>")
    }

    // ------------------------------------------------------------ lifecycle

    fun initialize(context: Context) {
        if (!started.compareAndSet(false, true)) return
        appContext = context.applicationContext
        prefs = context.getSharedPreferences("diagnostic_logging", Context.MODE_PRIVATE)
        logDir = File(context.filesDir, "logs").apply { mkdirs() }
        installCrashHook()
        i(TAG_INIT, "SLog ready — capture=$captureEnabled (opt-in)")
        runCatching {
            logBootBanner("1.0.${context.packageManager.getPackageInfo(context.packageName, 0).versionName}")
        }
        // Restore user intent across launches; each launch arms a fresh 2h window.
        if (prefs!!.getBoolean("enabled", false)) setCaptureEnabled(true, persist = false)
    }

    /** Boot banner with explicit version string. */
    fun logBootBanner(versionName: String) {
        i(TAG_INIT, "boot version=$versionName device=${Build.MANUFACTURER} ${Build.MODEL} sdk=${Build.VERSION.SDK_INT}")
    }

    // ---------------------------------------------------------- public api
    // Identical signatures to android.util.Log — drop-in facade.

    fun v(tag: String, msg: String): Int { sink.v(tag, msg, null); return append(LEVEL_V, tag, msg) }
    fun v(tag: String, msg: String, tr: Throwable?): Int { sink.v(tag, msg, tr); return append(LEVEL_V, tag, msg + fmt(tr)) }
    fun d(tag: String, msg: String): Int { sink.d(tag, msg, null); return append(LEVEL_D, tag, msg) }
    fun d(tag: String, msg: String, tr: Throwable?): Int { sink.d(tag, msg, tr); return append(LEVEL_D, tag, msg + fmt(tr)) }
    fun i(tag: String, msg: String): Int { sink.i(tag, msg, null); return append(LEVEL_I, tag, msg) }
    fun i(tag: String, msg: String, tr: Throwable?): Int { sink.i(tag, msg, tr); return append(LEVEL_I, tag, msg + fmt(tr)) }
    fun w(tag: String, msg: String): Int { sink.w(tag, msg, null); return append(LEVEL_W, tag, msg) }
    fun w(tag: String, msg: String, tr: Throwable?): Int { sink.w(tag, msg, tr); return append(LEVEL_W, tag, msg + fmt(tr)) }
    fun e(tag: String, msg: String): Int { sink.e(tag, msg, null); return append(LEVEL_E, tag, msg) }
    fun e(tag: String, msg: String, tr: Throwable?): Int { sink.e(tag, msg, tr); return append(LEVEL_E, tag, msg + fmt(tr)) }
    fun wtf(tag: String, msg: String): Int { sink.wtf(tag, msg, null); return append(LEVEL_F, tag, msg) }

    /** Replaces bare printStackTrace() with contextual capture. */
    fun st(tag: String, context: String, tr: Throwable?) {
        w(tag, "$context — ${tr?.javaClass?.simpleName}: ${tr?.message}", tr)
    }

    // ------------------------------------------------------ capture toggle

    /**
     * Arms/disarms diagnostic capture. When armed: allocates the 4MB off-heap
     * ring, starts the batched disk flusher, schedules the 2h auto-shutoff.
     */
    fun setCaptureEnabled(enable: Boolean, persist: Boolean = true) {
        synchronized(armingLock) {
            if (enable == captureEnabled) return
            if (enable) {
                ring = ByteBuffer.allocateDirect(RING_CAPACITY).order(ByteOrder.LITTLE_ENDIAN)
                writeOffset = 0
                validBytes = 0
                captureEnabled = true
                if (persist) prefs?.edit()?.putBoolean("enabled", true)?.apply()
                startFlusher()
                scheduleAutoOff()
                i(TAG_INIT, "capture ARMED — auto-off in 2h")
            } else {
                captureEnabled = false
                autoOffJob?.cancel(); autoOffJob = null
                if (persist) prefs?.edit()?.putBoolean("enabled", false)?.apply()
                stopFlusherAndDrain()
                frameListeners.clear()
                i(TAG_INIT, "capture DISARMED — ring wiped")
            }
        }
    }

    fun remainingCaptureMs(): Long {
        val end = armedUntilMs
        return if (captureEnabled && end > 0) (end - System.currentTimeMillis()).coerceAtLeast(0) else 0
    }

    @Volatile private var armedUntilMs = 0L

    private fun scheduleAutoOff() {
        armedUntilMs = System.currentTimeMillis() + AUTO_OFF_MS
        autoOffJob?.cancel()
        autoOffJob = scope.launch {
            delay(AUTO_OFF_MS)
            if (captureEnabled) {
                w(TAG_INIT, "auto-shutoff after 2h — disarming capture")
                setCaptureEnabled(false, persist = true)
            }
        }
    }

    // ------------------------------------------------------------- hot path

    private fun fmt(tr: Throwable?): String =
        if (tr == null) "" else "\n${sink.stackTraceString(tr)}"

    private fun append(level: Char, tag: String, message: String): Int {
        if (!captureEnabled) return 0 // OFF: this is the entire cost.
        writeFrame(level, tag.take(MAX_TAG), message.take(MAX_MSG))
        notifyFrameListeners()
        return 0
    }

    private fun writeFrame(level: Char, tag: String, msg: String) {
        val r = ring ?: return
        val tagB = tag.toByteArray(Charsets.UTF_8)
        val msgB = msg.toByteArray(Charsets.UTF_8)
        val payloadLen = 8 + 1 + 2 + tagB.size + 2 + msgB.size
        val frameLen = 4 + payloadLen + 4

        synchronized(ringLock) {
            // wrap pad: sentinel len=0 tells the decoder to jump to start
            if (writeOffset + frameLen > RING_CAPACITY) {
                if (writeOffset + 4 <= RING_CAPACITY) {
                    r.putInt(writeOffset, 0)
                }
                writeOffset = 0
            }
            if (frameLen > RING_CAPACITY) return // oversized guard

            var p = writeOffset
            r.putInt(p, payloadLen); p += 4
            r.putLong(p, System.currentTimeMillis()); p += 8
            r.put(p, level.code.toByte()); p += 1
            r.putShort(p, tagB.size.toShort()); p += 2
            r.position(p); r.put(tagB); p += tagB.size
            r.putShort(p, msgB.size.toShort()); p += 2
            r.position(p); r.put(msgB); p += msgB.size
            r.putInt(p, FRAME_MAGIC); p += 4

            writeOffset = p
            validBytes = (validBytes + frameLen).coerceAtMost(RING_CAPACITY)
        }
    }

    // ------------------------------------------------------------ listeners

    fun addFrameListener(l: () -> Unit) { frameListeners.add(l) }
    fun removeFrameListener(l: () -> Unit) { frameListeners.remove(l) }

    private fun notifyFrameListeners() {
        if (frameListeners.isEmpty()) return
        for (l in frameListeners) runCatching { l() }
    }

    // ------------------------------------------------------- viewer/export

    /** Decodes up to [max] most recent frames as formatted, redacted lines. */
    fun snapshotLines(max: Int = 2000): List<String> {
        val out = ArrayList<String>(minOf(max, 512))
        synchronized(ringLock) {
            val r = ring ?: return out
            var remaining = validBytes
            var pos = if (validBytes < RING_CAPACITY) 0 else writeOffset // oldest
            var safety = 0
            while (remaining > 0 && out.size < max && safety++ < 200_000) {
                if (pos + 4 > RING_CAPACITY) pos = 0
                val len = r.getInt(pos)
                if (len == 0) { pos = 0; continue }             // wrap pad
                if (len <= 0 || len > remaining || pos + 4 + len > RING_CAPACITY) break
                var p = pos + 4
                val ts = r.getLong(p); p += 8
                val lvl = r.get(p).toInt().toChar(); p += 1
                val tagLen = r.getShort(p).toInt(); p += 2
                val tagB = ByteArray(tagLen); r.position(p); r.get(tagB); p += tagLen
                val msgLen = r.getShort(p).toInt(); p += 2
                val msgB = ByteArray(msgLen); r.position(p); r.get(msgB); p += msgLen
                if (r.getInt(p) != FRAME_MAGIC) break           // torn/overwritten
                remaining -= len + 8
                pos = p + 4
                out.add(formatLine(ts, lvl, String(tagB, Charsets.UTF_8), String(msgB, Charsets.UTF_8)))
            }
        }
        return out
    }

    /** Entire decodable buffer, formatted + redacted — used by Copy/Download. */
    fun exportAll(): String {
        val sb = StringBuilder(1 shl 16)
        for (line in snapshotLines(Int.MAX_VALUE)) sb.append(line).append('\n')
        return sb.toString()
    }

    fun clearBuffer() {
        synchronized(ringLock) {
            validBytes = 0
            writeOffset = 0
        }
    }

    private fun formatLine(ts: Long, lvl: Char, tag: String, msg: String): String =
        "${tsFormat.format(Date(ts))} $lvl/${tag}: ${redact(msg)}"

    // --------------------------------------------------------- disk spool

    private fun startFlusher() {
        flushJob?.cancel()
        flushJob = scope.launch {
            val sb = StringBuilder(4096)
            while (isActive && captureEnabled) {
                delay(250)
                drainToDisk(sb)
            }
        }
    }

    /**
     * Non-suspend disarm tail: final drain runs on the IO scope BEFORE the ring
     * is released (appends already stopped via the captureEnabled flag).
     */
    private fun stopFlusherAndDrain() {
        flushJob?.cancel()
        val job = flushJob
        scope.launch {
            job?.join()
            drainToDisk(StringBuilder(4096))
            synchronized(ringLock) {
                ring = null
                writeOffset = 0
                validBytes = 0
            }
        }
    }

    private fun drainToDisk(sb: StringBuilder) {
        try {
            val lines = snapshotLines(Int.MAX_VALUE)
            if (lines.isEmpty()) return
            val f = File(logDir, SESSION_FILE)
            if (f.exists() && f.length() > MAX_FILE_BYTES) rotate()
            sb.setLength(0)
            for (l in lines) sb.append(l).append('\n')
            f.appendText(sb.toString())
        } catch (_: Throwable) {
        }
    }

    private fun rotate() {
        try {
            val oldest = File(logDir, "session-old-${ROTATE_KEEP}.log")
            if (oldest.exists()) oldest.delete()
            for (i in ROTATE_KEEP downTo 2) {
                val from = File(logDir, "session-old-${i - 1}.log")
                if (from.exists()) from.renameTo(File(logDir, "session-old-$i.log"))
            }
            File(logDir, SESSION_FILE).renameTo(File(logDir, "session-old-1.log"))
        } catch (_: Throwable) {
        }
    }

    fun currentLogFile(): File? =
        if (this::logDir.isInitialized) File(logDir, SESSION_FILE).takeIf { it.exists() } else null

    fun allLogFiles(): List<File> =
        if (this::logDir.isInitialized) logDir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList() else emptyList()

    // ---------------------------------------------------------- crash hook

    private fun installCrashHook() {
        val prior = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                // Always visible in logcat:
                sink.e("CRASH", "uncaught on ${t.name}", e)
                // And into the spool if the user opted in:
                if (captureEnabled) {
                    e("CRASH", "uncaught on thread=${t.name}: ${e.javaClass.name}: ${e.message}")
                    e("CRASH", sink.stackTraceString(e))
                }
                synchronized(armingLock) { runCatching { drainToDisk(StringBuilder(1024)) } }
            } catch (_: Throwable) {
            }
            prior?.uncaughtException(t, e)
        }
    }
}

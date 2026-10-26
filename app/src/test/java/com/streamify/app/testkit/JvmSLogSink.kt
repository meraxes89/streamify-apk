package com.streamify.app.testkit

import com.streamify.app.util.SLog
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Explicit JVM fake for the SLog logging backend (strict-mock migration, P1).
 *
 * `isReturnDefaultValues = false` means every android.util.Log call that
 * reaches a JVM unit test crashes with "Method ... not mocked". Instead of
 * masking the whole framework surface in the build, tests install this fake
 * once at process start — logging stays loud (console) and capture-free.
 *
 * Usage (typically in a shared base class or a companion-installed hook):
 * ```
 * @BeforeClass @JvmStatic fun setupLog() { JvmSLogSink.install() }
 * ```
 *
 * The fake never touches android.*: it prints to stdout/stderr so failures
 * remain diagnosable, and it keeps [SLog.captureEnabled] semantics intact
 * (capture is simply never armed on the JVM; the 4MB off-heap ring stays
 * unallocated).
 */
object JvmSLogSink {

    /** Echo log lines to stdout (off by default: keep test output clean). */
    @Volatile
    var verbose: Boolean = false

    /** Last line seen — handy for assertions that logging happened. */
    @Volatile
    var lastLine: String? = null
        private set

    private val lines = object : SLog.Sink {
        private fun emit(level: Char, tag: String, msg: String, tr: Throwable?) {
            val line = "$level/$tag: $msg"
            lastLine = line
            if (verbose) {
                println(line)
                tr?.printStackTrace(System.out)
            }
        }

        override fun v(tag: String, msg: String, tr: Throwable?) = emit('V', tag, msg, tr)
        override fun d(tag: String, msg: String, tr: Throwable?) = emit('D', tag, msg, tr)
        override fun i(tag: String, msg: String, tr: Throwable?) = emit('I', tag, msg, tr)
        override fun w(tag: String, msg: String, tr: Throwable?) = emit('W', tag, msg, tr)
        override fun e(tag: String, msg: String, tr: Throwable?) {
            emit('E', tag, msg, tr)
            if (!verbose && tr != null) {
                // Errors are always worth surfacing in test output.
                val sw = StringWriter()
                tr.printStackTrace(PrintWriter(sw))
                System.err.println(sw.toString())
            }
        }

        override fun wtf(tag: String, msg: String, tr: Throwable?) {
            emit('F', tag, msg, tr)
            if (!verbose && tr != null) tr.printStackTrace(System.err)
        }

        override fun stackTraceString(tr: Throwable): String {
            val sw = StringWriter()
            tr.printStackTrace(PrintWriter(sw))
            return sw.toString()
        }
    }

    private val installed = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Installs the JVM sink exactly once (idempotent across test classes). */
    fun install() {
        if (installed.compareAndSet(false, true)) {
            SLog.sink = lines
        }
    }
}

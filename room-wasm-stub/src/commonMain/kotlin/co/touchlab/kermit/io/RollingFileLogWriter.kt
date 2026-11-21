package co.touchlab.kermit.io

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.MessageStringFormatter
import co.touchlab.kermit.Severity
import kotlinx.io.files.Path

class RollingFileLogWriterConfig(
    val logFileName: String,
    val logFilePath: Path,
    val rollOnSize: Long,
    val maxLogFiles: Int,
)

open class RollingFileLogWriter(
    val config: RollingFileLogWriterConfig,
    val messageStringFormatter: MessageStringFormatter,
) : LogWriter() {
    override fun isLoggable(tag: String, severity: Severity): Boolean = true
    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {}
}

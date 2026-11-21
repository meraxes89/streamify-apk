package kotlin

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

fun String.Companion.format(locale: Any?, format: String, vararg args: Any?): String {
    var result = format
    for (arg in args) {
        val s = arg?.toString() ?: ""
        if (result.contains("%02d")) {
            val num = (arg as? Number)?.toLong() ?: 0L
            val padded = if (num in 0..9) "0$num" else num.toString()
            result = result.replaceFirst("%02d", padded)
        } else if (result.contains("%,d")) {
            result = result.replaceFirst("%,d", s)
        } else if (result.contains("%d")) {
            result = result.replaceFirst("%d", s)
        } else if (result.contains("%s")) {
            result = result.replaceFirst("%s", s)
        } else if (result.contains("%f")) {
            result = result.replaceFirst("%f", s)
        } else {
            break
        }
    }
    return result
}

fun String.Companion.format(format: String, vararg args: Any?): String = format(null, format, *args)

fun String.format(vararg args: Any?): String = String.format(null, this, *args)

object Character {
    fun isWhitespace(ch: Char): Boolean = ch.isWhitespace()
    fun isLetter(ch: Char): Boolean = ch.isLetter()
    fun isDigit(ch: Char): Boolean = ch.isDigit()
}

object System {
    fun currentTimeMillis(): Long = kotlin.time.TimeSource.Monotonic.markNow().elapsedNow().inWholeMilliseconds
}

val Dispatchers.IO: CoroutineDispatcher get() = Dispatchers.Default
val IO: CoroutineDispatcher get() = Dispatchers.Default

fun String(): String = ""

class StackTraceElement(
    val className: String = "",
    val methodName: String = "",
    val fileName: String = "",
    val lineNumber: Int = 0,
)

val Throwable.stackTrace: Array<StackTraceElement> get() = emptyArray()

fun <T> MutableCollection<T>.removeIf(filter: (T) -> Boolean): Boolean {
    var removed = false
    val each = iterator()
    while (each.hasNext()) {
        if (filter(each.next())) {
            each.remove()
            removed = true
        }
    }
    return removed
}

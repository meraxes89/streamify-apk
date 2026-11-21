package java.util.concurrent

enum class TimeUnit {
    NANOSECONDS,
    MICROSECONDS,
    MILLISECONDS,
    SECONDS,
    MINUTES,
    HOURS,
    DAYS;

    fun convert(sourceDuration: Long, sourceUnit: TimeUnit): Long {
        val millis = when (sourceUnit) {
            NANOSECONDS -> sourceDuration / 1_000_000L
            MICROSECONDS -> sourceDuration / 1_000L
            MILLISECONDS -> sourceDuration
            SECONDS -> sourceDuration * 1_000L
            MINUTES -> sourceDuration * 60_000L
            HOURS -> sourceDuration * 3_600_000L
            DAYS -> sourceDuration * 86_400_000L
        }
        return when (this) {
            NANOSECONDS -> millis * 1_000_000L
            MICROSECONDS -> millis * 1_000L
            MILLISECONDS -> millis
            SECONDS -> millis / 1_000L
            MINUTES -> millis / 60_000L
            HOURS -> millis / 3_600_000L
            DAYS -> millis / 86_400_000L
        }
    }
}

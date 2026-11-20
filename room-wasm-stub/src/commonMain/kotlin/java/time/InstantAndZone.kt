package java.time

class Instant {
    companion object {
        fun ofEpochMilli(epochMilli: Long): Instant = Instant()
    }
}

class ZoneId {
    companion object {
        fun systemDefault(): ZoneId = ZoneId()
    }
}

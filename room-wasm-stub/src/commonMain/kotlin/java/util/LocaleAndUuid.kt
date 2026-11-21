package java.util

class Locale(val language: String) {
    companion object {
        val ENGLISH = Locale("en")
        val ROOT = Locale("")
        fun getDefault(): Locale = ENGLISH
    }
}

class UUID(val mostSigBits: Long, val leastSigBits: Long) {
    override fun toString(): String = "00000000-0000-0000-0000-000000000000"
    companion object {
        fun randomUUID(): UUID = UUID(0L, 0L)
        fun fromString(name: String): UUID = UUID(0L, 0L)
    }
}

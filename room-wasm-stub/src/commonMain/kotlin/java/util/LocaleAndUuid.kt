package java.util

import kotlin.random.Random

class Locale(val language: String) {
    companion object {
        val ENGLISH = Locale("en")
        val ROOT = Locale("")
        fun getDefault(): Locale = ENGLISH
    }
}

class UUID(val mostSigBits: Long, val leastSigBits: Long) {
    override fun toString(): String {
        return buildString(36) {
            appendHex(mostSigBits ushr 32, 8)
            append('-')
            appendHex((mostSigBits ushr 16) and 0xFFFFL, 4)
            append('-')
            appendHex(mostSigBits and 0xFFFFL, 4)
            append('-')
            appendHex((leastSigBits ushr 48) and 0xFFFFL, 4)
            append('-')
            appendHex(leastSigBits and 0xFFFFFFFFFFFFL, 12)
        }
    }

    override fun equals(other: Any?): Boolean =
        other is UUID && other.mostSigBits == mostSigBits && other.leastSigBits == leastSigBits

    override fun hashCode(): Int = mostSigBits.hashCode() * 31 + leastSigBits.hashCode()

    companion object {
        fun randomUUID(): UUID {
            val bytes = Random.nextBytes(16)
            bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x40).toByte() // version 4
            bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte() // IETF variant
            var msb = 0L
            var lsb = 0L
            for (i in 0..7) msb = (msb shl 8) or (bytes[i].toLong() and 0xFFL)
            for (i in 8..15) lsb = (lsb shl 8) or (bytes[i].toLong() and 0xFFL)
            return UUID(msb, lsb)
        }

        fun fromString(name: String): UUID {
            val hex = name.replace("-", "")
            require(hex.length == 32) { "Invalid UUID string: $name" }
            val msb = hex.substring(0, 16).toULong(16).toLong()
            val lsb = hex.substring(16, 32).toULong(16).toLong()
            return UUID(msb, lsb)
        }
    }
}

private fun StringBuilder.appendHex(value: Long, digits: Int) {
    val hex = value.toULong().toString(16)
    for (i in hex.length until digits) append('0')
    append(hex)
}

package java.lang

object Character {
    fun isWhitespace(ch: Char): Boolean = ch.isWhitespace()
    fun isHighSurrogate(ch: Char): Boolean = ch.isHighSurrogate()
    fun isLowSurrogate(ch: Char): Boolean = ch.isLowSurrogate()
}

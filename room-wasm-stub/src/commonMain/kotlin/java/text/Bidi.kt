package java.text

class Bidi(val text: String, val flags: Int) {
    fun baseIsLeftToRight(): Boolean = true
    val isLeftToRight: Boolean get() = true
    companion object {
        const val DIRECTION_DEFAULT_LEFT_TO_RIGHT = -2
        const val DIRECTION_DEFAULT_RIGHT_TO_LEFT = -1
        const val DIRECTION_LEFT_TO_RIGHT = 0
        const val DIRECTION_RIGHT_TO_LEFT = 1
    }
}

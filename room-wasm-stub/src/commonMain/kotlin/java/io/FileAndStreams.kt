package java.io

open class File(val path: String) {
    constructor(parent: String, child: String) : this("$parent/$child")
    constructor(parent: File, child: String) : this("${parent.path}/$child")
    fun exists(): Boolean = false
    val isDirectory: Boolean get() = false
    val isFile: Boolean get() = false
    fun length(): Long = 0L
    fun listFiles(): Array<File>? = emptyArray()
    fun delete(): Boolean = false
    fun mkdirs(): Boolean = false
    val name: String get() = path.substringAfterLast('/')
    val absolutePath: String get() = path
    fun readText(): String = ""
}

open class InputStream : AutoCloseable {
    open fun read(): Int = -1
    open fun read(b: ByteArray): Int = -1
    open fun read(b: ByteArray, off: Int, len: Int): Int = -1
    open fun flush() {}
    override fun close() {}
}

open class OutputStream : AutoCloseable {
    open fun write(b: Int) {}
    open fun write(b: ByteArray?) {}
    open fun write(b: ByteArray?, off: Int, len: Int) {}
    open fun flush() {}
    override fun close() {}
}

class FileOutputStream(val file: Any?) : OutputStream()
class FileInputStream(val file: Any?) : InputStream()

class RandomAccessFile(val file: Any?, val mode: String) : AutoCloseable {
    fun length(): Long = 0L
    fun seek(pos: Long) {}
    fun readFully(b: ByteArray) {}
    fun readLine(): String? = null
    override fun close() {}
}

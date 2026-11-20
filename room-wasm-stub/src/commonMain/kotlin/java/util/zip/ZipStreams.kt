package java.util.zip

import java.io.InputStream
import java.io.OutputStream

class ZipEntry(val name: String) {
    fun isDirectory(): Boolean = false
}

class ZipInputStream(val input: InputStream) : InputStream() {
    fun getNextEntry(): ZipEntry? = null
    fun closeEntry() {}
}

class ZipOutputStream(val output: OutputStream) : OutputStream() {
    fun putNextEntry(e: ZipEntry) {}
    fun closeEntry() {}
}

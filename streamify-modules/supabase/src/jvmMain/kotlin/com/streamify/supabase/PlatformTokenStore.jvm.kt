package com.streamify.supabase

import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

internal actual object PlatformTokenStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun sessionFile(): File {
        val home = System.getProperty("user.home").orEmpty().ifBlank { "." }
        return File(File(home, ".streamify"), "auth_session.json")
    }

    actual fun init(context: Any?) {
        // File-backed; no context required.
    }

    actual fun load(): StoredAuthSession? {
        val file = sessionFile()
        if (!file.isFile) return null
        return runCatching {
            json.decodeFromString<StoredAuthSession>(file.readText(Charsets.UTF_8))
        }.getOrNull()
    }

    actual fun save(session: StoredAuthSession) {
        runCatching {
            val file = sessionFile()
            file.parentFile?.mkdirs()
            val encoded = json.encodeToString(StoredAuthSession.serializer(), session)
            file.writeText(encoded, Charsets.UTF_8)
            try {
                Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------"))
            } catch (_: UnsupportedOperationException) {
                file.setReadable(false, false)
                file.setWritable(false, false)
                file.setReadable(true, true)
                file.setWritable(true, true)
            }
        }
    }

    actual fun clear() {
        runCatching {
            val file = sessionFile()
            if (file.exists()) file.delete()
        }
    }
}

package com.maxrave.simpmusic.data.update

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

actual object PlatformUpdater {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null

    private val _state = MutableStateFlow<UpdateDownloadState>(UpdateDownloadState.Idle)
    actual val state: StateFlow<UpdateDownloadState> = _state.asStateFlow()

    private val client = HttpClient(CIO) {
        followRedirects = true
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 120_000
            socketTimeoutMillis = 30_000
        }
    }

    actual fun startDownload(tag: String, url: String, expectedSha256: String) {
        if (downloadJob?.isActive == true) return
        if (_state.value is UpdateDownloadState.Ready) return

        downloadJob = scope.launch {
            runCatching {
                val updatesDir = File(System.getProperty("java.io.tmpdir"), "streamify-updates").apply { mkdirs() }
                val ext = url.substringAfterLast('.', "bin").substringBefore('?')
                val safeTag = tag.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                val finalFile = File(updatesDir, "streamify-$safeTag.$ext")
                val partFile = File(updatesDir, "streamify-$safeTag.$ext.part")

                if (finalFile.exists() && finalFile.length() > 0L) {
                    if (verifySha256(finalFile, expectedSha256)) {
                        _state.value = UpdateDownloadState.Ready(finalFile.absolutePath)
                        return@launch
                    } else {
                        finalFile.delete()
                    }
                }

                val existingBytes = if (partFile.exists()) partFile.length() else 0L

                client.prepareGet(url) {
                    header(HttpHeaders.UserAgent, "Streamify-App")
                    if (existingBytes > 0L) {
                        header(HttpHeaders.Range, "bytes=$existingBytes-")
                    }
                }.execute { response ->
                    val status = response.status
                    if (status == HttpStatusCode.RequestedRangeNotSatisfiable) {
                        _state.value = UpdateDownloadState.Verifying
                        if (verifySha256(partFile, expectedSha256)) {
                            partFile.renameTo(finalFile)
                            _state.value = UpdateDownloadState.Ready(finalFile.absolutePath)
                        } else {
                            partFile.delete()
                            _state.value = UpdateDownloadState.Error("Checksum verification failed")
                        }
                        return@execute
                    }

                    if (status != HttpStatusCode.OK && status != HttpStatusCode.PartialContent) {
                        _state.value = UpdateDownloadState.Error("Download failed (HTTP ${status.value})")
                        return@execute
                    }

                    val append = status == HttpStatusCode.PartialContent && existingBytes > 0L
                    val contentLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: -1L
                    val totalBytes = if (append && contentLength > 0L) existingBytes + contentLength else contentLength
                    var downloaded = if (append) existingBytes else 0L

                    _state.value = UpdateDownloadState.Downloading(downloaded, totalBytes)

                    val channel = response.bodyAsChannel()
                    val buffer = ByteArray(16 * 1024)
                    FileOutputStream(partFile, append).use { fos ->
                        while (!channel.isClosedForRead) {
                            val read = channel.readAvailable(buffer, 0, buffer.size)
                            if (read <= 0) break
                            fos.write(buffer, 0, read)
                            downloaded += read
                            _state.value = UpdateDownloadState.Downloading(downloaded, totalBytes)
                        }
                    }

                    _state.value = UpdateDownloadState.Verifying
                    if (verifySha256(partFile, expectedSha256)) {
                        if (finalFile.exists()) finalFile.delete()
                        partFile.renameTo(finalFile)
                        _state.value = UpdateDownloadState.Ready(finalFile.absolutePath)
                    } else {
                        partFile.delete()
                        _state.value = UpdateDownloadState.Error("Checksum verification failed")
                    }
                }
            }.onFailure { e ->
                _state.value = UpdateDownloadState.Error(e.message ?: "Download interrupted")
            }
        }
    }

    actual fun installOrApply(onFallbackOpenUrl: (String) -> Unit, fallbackUrl: String) {
        val ready = _state.value as? UpdateDownloadState.Ready
        if (ready == null) {
            onFallbackOpenUrl(fallbackUrl)
            return
        }
        runCatching {
            val file = File(ready.filePath)
            val osName = System.getProperty("os.name", "").lowercase()
            if (osName.contains("linux") && file.name.endsWith(".AppImage", ignoreCase = true)) {
                file.setExecutable(true)
            }
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(file)
            } else {
                ProcessBuilder("xdg-open", file.absolutePath).start()
            }
        }.onFailure {
            onFallbackOpenUrl(fallbackUrl)
        }
    }

    actual fun reset() {
        downloadJob?.cancel()
        downloadJob = null
        _state.value = UpdateDownloadState.Idle
    }

    private fun verifySha256(file: File, expectedSha256: String): Boolean {
        if (expectedSha256.isBlank()) return file.exists() && file.length() > 0L
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { fis ->
            val buf = ByteArray(16 * 1024)
            var read: Int
            while (fis.read(buf).also { read = it } != -1) {
                digest.update(buf, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        return actual.equals(expectedSha256.trim(), ignoreCase = true)
    }
}

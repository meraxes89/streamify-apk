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
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.system.exitProcess

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

    actual fun canAutoInstall(): Boolean {
        val osName = System.getProperty("os.name", "").lowercase()
        return when {
            osName.contains("win") -> true
            osName.contains("linux") -> !System.getenv("APPIMAGE").isNullOrBlank()
            osName.contains("mac") -> currentMacAppBundle() != null
            else -> false
        }
    }

    actual fun installOrApply(onFallbackOpenUrl: (String) -> Unit, fallbackUrl: String) {
        val ready = _state.value as? UpdateDownloadState.Ready
        if (ready == null) {
            onFallbackOpenUrl(fallbackUrl)
            return
        }
        val file = File(ready.filePath)
        if (!file.exists()) {
            onFallbackOpenUrl(fallbackUrl)
            return
        }
        val osName = System.getProperty("os.name", "").lowercase()
        val applied =
            when {
                osName.contains("linux") && file.name.endsWith(".AppImage", ignoreCase = true) ->
                    installLinuxAppImage(file)
                osName.contains("win") && (file.name.endsWith(".msi", ignoreCase = true) || file.name.endsWith(".exe", ignoreCase = true)) ->
                    installWindowsPackage(file)
                osName.contains("mac") && file.name.endsWith(".dmg", ignoreCase = true) ->
                    installMacOsDmg(file)
                else -> false
            }
        if (applied) {
            exitProcess(0)
        }

        runCatching {
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

    private fun installLinuxAppImage(file: File): Boolean {
        val appImageEnv = System.getenv("APPIMAGE")?.takeIf { it.isNotBlank() } ?: return false
        return runCatching {
            file.setExecutable(true)
            val target = Path.of(appImageEnv)
            runCatching {
                Files.move(file.toPath(), target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }.getOrElse {
                Files.copy(file.toPath(), target, StandardCopyOption.REPLACE_EXISTING)
                target.toFile().setExecutable(true)
            }
            ProcessBuilder(appImageEnv).start()
            true
        }.getOrDefault(false)
    }

    private fun installWindowsPackage(file: File): Boolean =
        runCatching {
            val pid = ProcessHandle.current().pid()
            val quotedPath = file.absolutePath.replace("\"", "\"\"")
            val installCmd =
                if (file.name.endsWith(".msi", ignoreCase = true)) {
                    "msiexec /i \"$quotedPath\" /qn /norestart"
                } else {
                    "\"$quotedPath\" /S"
                }
            val script =
                "while (Get-Process -Id $pid -ErrorAction SilentlyContinue) { Start-Sleep -Milliseconds 300 }; " +
                    "Start-Process -FilePath 'cmd.exe' -ArgumentList '/c','$installCmd' -WindowStyle Hidden"
            ProcessBuilder("powershell", "-NoProfile", "-WindowStyle", "Hidden", "-Command", script).start()
            true
        }.getOrDefault(false)

    private fun installMacOsDmg(file: File): Boolean {
        val appBundle = currentMacAppBundle() ?: return false
        return runCatching {
            val pid = ProcessHandle.current().pid()
            val mountDir = Files.createTempDirectory("streamify-dmg").toAbsolutePath().toString()
            val script =
                """
                while kill -0 $pid 2>/dev/null; do sleep 0.3; done
                hdiutil attach -nobrowse -quiet -mountpoint "$mountDir" "${file.absolutePath}" || exit 1
                SRC_APP=$(ls -d "$mountDir"/*.app 2>/dev/null | head -n 1)
                if [ -n "${'$'}SRC_APP" ]; then
                  rm -rf "$appBundle"
                  cp -R "${'$'}SRC_APP" "$appBundle"
                  open "$appBundle"
                fi
                hdiutil detach -quiet "$mountDir" || true
                rmdir "$mountDir" 2>/dev/null || true
                """.trimIndent()
            ProcessBuilder("/bin/sh", "-c", script).start()
            true
        }.getOrDefault(false)
    }

    private fun currentMacAppBundle(): String? {
        val cmd = ProcessHandle.current().info().command().orElse(null) ?: return null
        val idx = cmd.indexOf(".app/")
        return if (idx > 0) cmd.substring(0, idx + 4) else null
    }

    actual fun reset() {
        downloadJob?.cancel()
        downloadJob = null
        _state.value = UpdateDownloadState.Idle
    }

    private fun verifySha256(file: File, expectedSha256: String): Boolean {
        if (expectedSha256.isBlank() || !file.exists() || file.length() <= 0L) return false
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

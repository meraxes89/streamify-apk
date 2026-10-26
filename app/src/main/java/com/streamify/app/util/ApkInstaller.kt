package com.streamify.app.util

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import com.streamify.app.util.SLog

object ApkInstaller {

    fun downloadAndInstall(
        context: Context,
        url: String,
        fileName: String = "streamify_update.apk"
    ) {
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
        if (downloadManager == null) {
            Toast.makeText(context, "Download Manager not available", Toast.LENGTH_SHORT).show()
            // Fallback to browser
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(browserIntent)
            return
        }

        // Clean up previous update file if it exists
        val destinationFile = File(context.getExternalFilesDir(null), fileName)
        if (destinationFile.exists()) {
            destinationFile.delete()
        }

        Toast.makeText(context, "Starting Streamify update download...", Toast.LENGTH_SHORT).show()

        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle("Streamify Update")
            setDescription("Downloading latest Streamify release...")
            setDestinationInExternalFilesDir(context, null, fileName)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
        }

        val downloadId = downloadManager.enqueue(request)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (id == downloadId) {
                    try {
                        ctx?.unregisterReceiver(this)
                    } catch (e: Exception) {
                        // Receiver might already be unregistered
                    }

                    val apkFile = File(ctx?.getExternalFilesDir(null), fileName)
                    if (apkFile.exists() && ctx != null) {
                        promptInstallation(ctx, apkFile)
                    }
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(
                receiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                Context.RECEIVER_EXPORTED
            )
        } else {
            context.registerReceiver(
                receiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            )
        }
    }

    private fun promptInstallation(context: Context, apkFile: File) {
        // SEC-04: pin the updater certificate — inspect the downloaded
        // archive's package signature BEFORE firing the package-installer
        // intent (see ApkSignatureVerifier for the pinning model).
        val verdict = ApkSignatureVerifier.verifyUpdateCertificate(context, apkFile)
        if (verdict is ApkSignatureVerifier.Verification.Rejected) {
            Toast.makeText(
                context,
                "Update blocked: signature verification failed",
                Toast.LENGTH_LONG
            ).show()
            SLog.st(
                "ApkInstaller",
                "SEC-04 blocked update ${apkFile.name}: ${verdict.reason}",
                null
            )
            // The update file lives in app-owned external storage: remove
            // the rejected artifact so it cannot be installed by mistake.
            runCatching { apkFile.delete() }
            return
        }
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to launch installer: ${e.message}", Toast.LENGTH_LONG).show()
            SLog.st("ApkInstaller", "ApkInstaller.promptInstallation failed", e)
        }
    }
}

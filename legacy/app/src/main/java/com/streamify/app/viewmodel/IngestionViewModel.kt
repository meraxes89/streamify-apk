package com.streamify.app.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import com.streamify.app.util.SLog

data class DownloadTask(
    val id: UUID,
    val title: String,
    val progress: String = "0%",
    val speed: String = "",
    val state: String = "Queued"
)

class IngestionViewModel : ViewModel() {
    private val _downloadTasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val downloadTasks: StateFlow<List<DownloadTask>> = _downloadTasks.asStateFlow()

    private var isObserving = false

    fun observeDownloads(context: Context) {
        if (isObserving) return
        isObserving = true

        val workManager = WorkManager.getInstance(context.applicationContext)
        viewModelScope.launch {
            workManager.getWorkInfosByTagFlow("download_worker").collect { workInfos ->
                val tasks = workInfos.filter { !it.state.isFinished }.map { workInfo ->
                    val progressStr = workInfo.progress.getString("progress") ?: "Downloading..."
                    val speedStr = workInfo.progress.getString("speed") ?: ""
                    val title = workInfo.tags.firstOrNull { it.startsWith("TITLE:") }?.removePrefix("TITLE:") ?: "Unknown"
                    DownloadTask(
                        id = workInfo.id,
                        title = title,
                        progress = progressStr,
                        speed = speedStr,
                        state = workInfo.state.name
                    )
                }
                _downloadTasks.value = tasks
            }
        }
    }

    fun enqueueDownload(context: Context, url: String, title: String, artist: String, album: String, quality: String? = null) {
        observeDownloads(context)
        val workManager = WorkManager.getInstance(context)
        
        val savedQuality = quality ?: context.getSharedPreferences("audio_settings", Context.MODE_PRIVATE)
            .getString("download_quality", "320") ?: "320"

        val inputData = Data.Builder()
            .putString("url", url)
            .putString("title", title)
            .putString("artist", artist)
            .putString("album", album)
            .putString("quality", savedQuality)
            .build()
            
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
            
        val downloadRequest = OneTimeWorkRequestBuilder<com.streamify.app.worker.DownloadWorker>()
            .addTag("download_worker")
            .addTag("TITLE:$title")
            .setInputData(inputData)
            .setConstraints(constraints)
            .build()
            
        workManager.enqueue(downloadRequest)
    }

    fun cancelDownload(context: Context, taskId: UUID) {
        WorkManager.getInstance(context).cancelWorkById(taskId)
    }

    companion object {
        fun enqueueDownloadDirect(
            context: Context? = null,
            url: String,
            title: String,
            artist: String,
            album: String = "Streamify",
            quality: String = "320"
        ) {
            try {
                val targetContext = context ?: com.streamify.app.data.repository.TrackRepository.appContext ?: return
                val workManager = WorkManager.getInstance(targetContext)
                val inputData = Data.Builder()
                    .putString("url", url)
                    .putString("title", title)
                    .putString("artist", artist)
                    .putString("album", album)
                    .putString("quality", quality)
                    .build()

                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()

                val downloadRequest = OneTimeWorkRequestBuilder<com.streamify.app.worker.DownloadWorker>()
                    .addTag("download_worker")
                    .addTag("TITLE:$title")
                    .setInputData(inputData)
                    .setConstraints(constraints)
                    .build()

                workManager.enqueue(downloadRequest)
            } catch (e: Exception) {
                SLog.st("IngestionViewModel", "IngestionViewModel.enqueueDownloadDirect failed", e)
            }
        }
    }
}


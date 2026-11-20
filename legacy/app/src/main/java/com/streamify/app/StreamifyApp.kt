package com.streamify.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache

class StreamifyApp : Application(), ImageLoaderFactory {

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    // 20%: leaves headroom on 3GB devices where artwork caches
                    // compete with the audio pipeline.
                    .maxSizePercent(0.20)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(100 * 1024 * 1024L) // 100MB LRU disk cache for instantaneous album art loading
                    .build()
            }
            .crossfade(300)
            .respectCacheHeaders(false)
            .build()
    }

    override fun onCreate() {
        super.onCreate()

        // Single wiring point for the service-layer dependency graph.
        // Init order (SLog → fleet config → repositories → native/async
        // engines → services → authenticated resolution) is owned and
        // documented in AppGraph.initialize.
        com.streamify.app.di.AppGraph.initialize(this)

        // Phase 4 — Google Cast route stack (no-ops quietly on hardware
        // without Play Services; discovery feeds ConnectRuntime's registry).
        com.streamify.app.cast.CastMediaManager.initialize(this)

        // Phase 4 — WearOS companion links: wrist transport controls, Jam
        // voting and run-bundle pushes ride the GMS wearable clients.
        com.streamify.app.wear.WearSessionManager.initialize(this)

        // Screen-level lifecycle breadcrumbs for the admin terminal.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private fun name(a: android.app.Activity) = a.javaClass.simpleName
            override fun onActivityCreated(a: android.app.Activity, s: android.os.Bundle?) {
                com.streamify.app.util.SLog.i("LIFECYCLE", "created ${name(a)}")
            }
            override fun onActivityStarted(a: android.app.Activity) {
                com.streamify.app.util.SLog.i("LIFECYCLE", "started ${name(a)}")
            }
            override fun onActivityResumed(a: android.app.Activity) {
                com.streamify.app.util.SLog.i("LIFECYCLE", "resumed ${name(a)}")
            }
            override fun onActivityPaused(a: android.app.Activity) {
                com.streamify.app.util.SLog.i("LIFECYCLE", "paused ${name(a)}")
            }
            override fun onActivityStopped(a: android.app.Activity) {
                com.streamify.app.util.SLog.i("LIFECYCLE", "stopped ${name(a)}")
            }
            override fun onActivitySaveInstanceState(a: android.app.Activity, s: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {
                com.streamify.app.util.SLog.i("LIFECYCLE", "destroyed ${name(a)}")
            }
        })

        createNotificationChannels()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        com.streamify.app.util.SLog.w("LIFECYCLE", "onTrimMemory level=$level")
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            com.streamify.app.media.sync.ThermalGovernorManager.handleLowMemory(this)
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val playbackChannel = NotificationChannel(
                "streamify_playback",
                "Playback Settings",
                NotificationManager.IMPORTANCE_LOW
            )
            playbackChannel.description = "Controls for the current playing track"

            val downloadChannel = NotificationChannel(
                "streamify_download",
                "Downloads",
                NotificationManager.IMPORTANCE_LOW
            )
            downloadChannel.description = "Background download progress"

            manager.createNotificationChannel(playbackChannel)
            manager.createNotificationChannel(downloadChannel)
        }
    }
}

package com.streamify.sync.v5

actual fun createPlatformSyncV5Store(): SyncV5Store = InMemorySyncV5Store()

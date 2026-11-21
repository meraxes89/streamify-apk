package com.streamify.app.data.persistence
import com.streamify.app.util.SLog
import kotlinx.coroutines.CompletableDeferred
import com.streamify.app.data.NativeBridge

object DatabaseInitializer {
    private val initDeferred = CompletableDeferred<Unit>()

    fun startInitialization(dbPath: String) {
        try {
            NativeBridge.initDatabase(dbPath)
        } catch (e: Throwable) {
            SLog.e("DatabaseInitializer", "Failed to initialize NativeBridge Database", e)
        } finally {
            if (!initDeferred.isCompleted) {
                initDeferred.complete(Unit)
            }
        }
    }

    suspend fun ensureInitialized() {
        initDeferred.await()
    }
}

package com.streamify.sync.v5

import com.streamify.sync.HybridLogicalClock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Deterministic value-keyed echo suppression for asynchronous DataStore setting flows.
 * Eliminates `isApplyingRemoteSetting` boolean races across Android, JVM, iOS, and WasmJS.
 */
class SettingsSyncObserver(
    private val store: SyncV5Store,
    private val deviceIdProvider: () -> String,
) {
    private val mutex = Mutex()
    private val suppressedValues = mutableMapOf<String, String>()

    suspend fun markRemoteWrite(key: String, serializedValue: String) {
        mutex.withLock {
            suppressedValues[key] = serializedValue
        }
    }

    /**
     * Evaluates an emitted `(key, serializedValue)` from DataStore.
     * Returns `true` if the emission was enqueued to `sync_outbox_v5`, `false` if suppressed as a remote echo.
     */
    @OptIn(ExperimentalUuidApi::class)
    suspend fun onLocalSettingChanged(key: String, serializedValue: String): Boolean {
        val suppressed = mutex.withLock {
            val expected = suppressedValues.remove(key)
            expected == serializedValue
        }
        if (suppressed) return false

        val hlc = HybridLogicalClock.now()
        val payload = buildJsonObject {
            put("key", key)
            put("value", runCatching { Json.parseToJsonElement(serializedValue) }.getOrElse { JsonPrimitive(serializedValue) })
        }
        store.enqueueOrCoalesce(
            SyncOutboxRow(
                mutationId = Uuid.random().toString(),
                entityType = "user_setting",
                entityKey = key,
                op = "upsert",
                hlc = hlc,
                deviceId = deviceIdProvider(),
                payloadJson = payload.toString(),
            )
        )
        return true
    }
}

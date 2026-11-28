package com.streamify.supabase.v5

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

@Serializable
data class PlaneAMutationDto(
    @SerialName("mutation_id") val mutationId: String,
    @SerialName("entity_type") val entityType: String,
    @SerialName("entity_key") val entityKey: String,
    @SerialName("op") val op: String,
    @Serializable(with = LongAsStringSerializer::class)
    @SerialName("hlc") val hlc: Long,
    @SerialName("payload") val payload: JsonObject,
)

@Serializable
data class PlaneAMutationResultDto(
    @SerialName("mutation_id") val mutationId: String,
    @SerialName("status") val status: String,
    @Serializable(with = LongAsStringSerializer::class)
    @SerialName("rev") val rev: Long? = null,
    @SerialName("error_code") val errorCode: String? = null,
    @Serializable(with = LongAsStringSerializer::class)
    @SerialName("server_hlc") val serverHlc: Long? = null,
    @SerialName("server_by") val serverBy: String? = null,
    @Serializable(with = LongAsStringSerializer::class)
    @SerialName("server_time_ms") val serverTimeMs: Long? = null,
)

@Serializable
data class PlaneAPushResponse(
    @SerialName("protocol_version") val protocolVersion: Int = 5,
    @SerialName("status") val status: String,
    @Serializable(with = LongAsStringSerializer::class)
    @SerialName("server_time_ms") val serverTimeMs: Long? = null,
    @Serializable(with = LongAsStringSerializer::class)
    @SerialName("own_rev") val ownRev: Long? = null,
    @Serializable(with = LongAsStringSerializer::class)
    @SerialName("head_rev") val headRev: Long = 0L,
    @SerialName("results") val results: List<PlaneAMutationResultDto> = emptyList(),
)

@Serializable
data class PlaneAChangeDto(
    @Serializable(with = LongAsStringSerializer::class)
    @SerialName("rev") val rev: Long,
    @SerialName("mutation_id") val mutationId: String,
    @SerialName("entity_type") val entityType: String,
    @SerialName("entity_key") val entityKey: String,
    @SerialName("op") val op: String,
    @Serializable(with = LongAsStringSerializer::class)
    @SerialName("hlc") val hlc: Long,
    @SerialName("origin_device_id") val originDeviceId: String,
    @SerialName("payload") val payload: JsonObject,
)

@Serializable
data class PlaneAPullResponse(
    @SerialName("protocol_version") val protocolVersion: Int = 5,
    @SerialName("status") val status: String,
    @Serializable(with = LongAsStringSerializer::class)
    @SerialName("server_time_ms") val serverTimeMs: Long? = null,
    @Serializable(with = LongAsStringSerializer::class)
    @SerialName("head_rev") val headRev: Long = 0L,
    @Serializable(with = LongAsStringSerializer::class)
    @SerialName("min_available_rev") val minAvailableRev: Long = 0L,
    @Serializable(with = LongAsStringSerializer::class)
    @SerialName("next_rev") val nextRev: Long? = null,
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("changes") val changes: List<PlaneAChangeDto> = emptyList(),
    @SerialName("catalog") val catalog: JsonObject? = null,
)

@Serializable
data class PlaneABootstrapRowDto(
    @SerialName("entity_type") val entityType: String,
    @SerialName("entity_key") val entityKey: String,
    @Serializable(with = LongAsStringSerializer::class)
    @SerialName("hlc") val hlc: Long,
    @SerialName("modified_by") val modifiedBy: String,
    @SerialName("payload") val payload: JsonObject,
)

@Serializable
data class PlaneABootstrapResponse(
    @SerialName("protocol_version") val protocolVersion: Int = 5,
    @SerialName("status") val status: String,
    @Serializable(with = LongAsStringSerializer::class)
    @SerialName("server_time_ms") val serverTimeMs: Long? = null,
    @Serializable(with = LongAsStringSerializer::class)
    @SerialName("snapshot_rev") val snapshotRev: Long = 0L,
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("next_page_token") val nextPageToken: JsonElement? = null,
    @SerialName("rows") val rows: List<PlaneABootstrapRowDto> = emptyList(),
    @SerialName("catalog") val catalog: JsonObject? = null,
)

/**
 * Plane A Supabase RPC transport (`sync_push_v5`, `sync_pull_v5`, `sync_bootstrap_v5`).
 */
class PlaneAStateSyncClient(
    private val executor: SupabaseRpcExecutor,
    private val json: Json = SupabaseRpcExecutor.DefaultJson,
) {
    suspend fun push(
        deviceId: String,
        mutations: List<PlaneAMutationDto>,
    ): RpcResult<PlaneAPushResponse> {
        val body = buildJsonObject {
            put("p_device_id", deviceId)
            put("p_mutations", json.encodeToJsonElement(mutations))
        }
        return executor.invoke("sync_push_v5", json.encodeToString(body), deviceId) { root ->
            json.decodeFromJsonElement<PlaneAPushResponse>(root)
        }
    }

    suspend fun pull(
        deviceId: String,
        sinceRev: Long,
        limit: Int = 200,
        excludeOwn: Boolean = true,
        includeCatalog: Boolean = true,
    ): RpcResult<PlaneAPullResponse> {
        val body = buildJsonObject {
            put("p_device_id", deviceId)
            put("p_since_rev", sinceRev.toString())
            put("p_limit", limit)
            put("p_exclude_own", excludeOwn)
            put("p_include_catalog", includeCatalog)
        }
        return executor.invoke("sync_pull_v5", json.encodeToString(body), deviceId) { root ->
            json.decodeFromJsonElement<PlaneAPullResponse>(root)
        }
    }

    suspend fun bootstrap(
        pageToken: JsonElement? = null,
        pageSize: Int = 500,
        includeCatalog: Boolean = true,
    ): RpcResult<PlaneABootstrapResponse> {
        val body = buildJsonObject {
            if (pageToken != null) put("p_page_token", pageToken) else put("p_page_token", null as String?)
            put("p_page_size", pageSize)
            put("p_include_catalog", includeCatalog)
        }
        return executor.invoke("sync_bootstrap_v5", json.encodeToString(body)) { root ->
            json.decodeFromJsonElement<PlaneABootstrapResponse>(root)
        }
    }
}

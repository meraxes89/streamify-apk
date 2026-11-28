package com.streamify.supabase.v5

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

@Serializable
data class CatalogTrackDto(
    @SerialName("id") val id: String,
    @SerialName("source") val source: String,
    @SerialName("external_id") val externalId: String,
    @SerialName("title") val title: String,
    @SerialName("artist_name") val artistName: String,
    @SerialName("album_name") val albumName: String? = null,
    @SerialName("artist_id") val artistId: String? = null,
    @SerialName("album_id") val albumId: String? = null,
    @SerialName("duration_sec") val durationSec: Int? = null,
    @SerialName("thumb_url") val thumbUrl: String? = null,
    @SerialName("metadata_authority") val metadataAuthority: Int = 10,
    @SerialName("metadata_quality") val metadataQuality: Int = 0,
    @SerialName("enrichment_status") val enrichmentStatus: String = "pending",
    @SerialName("catalog_version") val catalogVersion: Int = 1,
    @SerialName("enriched_at") val enrichedAt: String? = null,
)

@Serializable
data class GetTracksBatchResponse(
    @SerialName("status") val status: String,
    @SerialName("tracks") val tracks: Map<String, CatalogTrackDto> = emptyMap(),
    @SerialName("id_aliases") val idAliases: Map<String, String> = emptyMap(),
    @SerialName("unchanged_ids") val unchangedIds: List<String> = emptyList(),
    @SerialName("missing_ids") val missingIds: List<String> = emptyList(),
)

@Serializable
data class TrackHintInputDto(
    @SerialName("track_id") val trackId: String,
    @SerialName("title") val title: String? = null,
    @SerialName("artist_name") val artistName: String? = null,
    @SerialName("album_name") val albumName: String? = null,
    @SerialName("duration_sec") val durationSec: Int? = null,
    @SerialName("thumb_url") val thumbUrl: String? = null,
)

@Serializable
data class IngestTrackHintsResponse(
    @SerialName("status") val status: String,
    @SerialName("accepted") val accepted: Int = 0,
    @SerialName("ignored_higher_authority") val ignoredHigherAuthority: Int = 0,
    @SerialName("enqueued_for_enrichment") val enqueuedForEnrichment: Int = 0,
    @SerialName("rate_limited") val rateLimited: Boolean = false,
)

/**
 * Plane D Supabase RPC transport for canonical catalog hydration and non-authoritative hint ingestion.
 */
class PlaneDCatalogClient(
    private val executor: SupabaseRpcExecutor,
    private val json: Json = SupabaseRpcExecutor.DefaultJson,
) {
    suspend fun getTracksBatch(
        trackIds: List<String>,
        knownVersions: Map<String, Int> = emptyMap(),
        enqueueMissing: Boolean = true,
    ): RpcResult<GetTracksBatchResponse> {
        val body = buildJsonObject {
            put("p_track_ids", json.encodeToJsonElement(trackIds))
            put("p_known_versions", json.encodeToJsonElement(knownVersions))
            put("p_enqueue_missing", enqueueMissing)
        }
        return executor.invoke("get_tracks_batch", json.encodeToString(body)) { root ->
            json.decodeFromJsonElement<GetTracksBatchResponse>(root)
        }
    }

    suspend fun ingestTrackHints(
        hints: List<TrackHintInputDto>,
    ): RpcResult<IngestTrackHintsResponse> {
        val body = buildJsonObject {
            put("p_hints", json.encodeToJsonElement(hints))
        }
        return executor.invoke("ingest_track_hints", json.encodeToString(body)) { root ->
            json.decodeFromJsonElement<IngestTrackHintsResponse>(root)
        }
    }
}

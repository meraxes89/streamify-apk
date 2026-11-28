package com.streamify.supabase.v5

import com.streamify.supabase.SupabaseClient
import com.streamify.supabase.SupabaseConfig
import com.streamify.supabase.ioDispatcher
import com.streamify.supabase.resolvePlatformDeviceInfo
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.time.Clock

sealed interface RpcResult<out T> {
    data class Ok<T>(val data: T, val serverTimeMs: Long? = null) : RpcResult<T>
    data class DeviceRevoked(val revokedAt: String? = null) : RpcResult<Nothing>
    data class CursorExpired(val headRev: Long, val minAvailableRev: Long) : RpcResult<Nothing>
    data class ClockSkew(val serverTimeMs: Long, val deltaMs: Long) : RpcResult<Nothing>
    data class RateLimited(val retryAfterMs: Long) : RpcResult<Nothing>
    data class Unauthorized(val reason: String) : RpcResult<Nothing>
    data class TransientFailure(val httpStatus: Int?, val message: String) : RpcResult<Nothing>
    data class TerminalError(val errorCode: String, val message: String) : RpcResult<Nothing>
}

data class DeviceRevokedEvent(
    val deviceId: String,
    val revokedAt: String?,
)

/**
 * Tracks EWMA clock offset (serverEpochMs - localEpochMs) across RPC responses.
 */
class ClockSkewEstimator(
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val _estimatedOffsetMs = MutableStateFlow(0L)
    val estimatedOffsetMs: StateFlow<Long> = _estimatedOffsetMs.asStateFlow()

    private val _skewWarningActive = MutableStateFlow(false)
    val skewWarningActive: StateFlow<Boolean> = _skewWarningActive.asStateFlow()

    private var hasSample = false

    fun recordSample(serverMs: Long, rttMs: Long = 0L) {
        val localMidMs = nowMs() - (rttMs.coerceAtLeast(0L) / 2L)
        val rawOffset = serverMs - localMidMs
        val smoothed = if (!hasSample) {
            hasSample = true
            rawOffset
        } else {
            (_estimatedOffsetMs.value * 0.7 + rawOffset * 0.3).roundToLong()
        }
        _estimatedOffsetMs.value = smoothed
        _skewWarningActive.value = abs(smoothed) > WARNING_THRESHOLD_MS
    }

    companion object {
        const val WARNING_THRESHOLD_MS = 120_000L
    }
}

/**
 * Shared PostgREST RPC pipeline for Sync v5 (Planes A-F):
 * - Contract & platform headers (`X-Sync-Contract-Version: 5`)
 * - Single-flight 401 refresh via [SupabaseClient.refreshSession]
 * - HTTP `Date` / `server_time_ms` clock-skew sampling
 * - Global `DEVICE_REVOKED` event broadcast
 */
open class SupabaseRpcExecutor(
    private val httpClient: HttpClient = SupabaseClient.httpClient,
    private val skewEstimator: ClockSkewEstimator = ClockSkewEstimator(),
    private val json: Json = DefaultJson,
) {
    private val _deviceRevokedEvents = MutableSharedFlow<DeviceRevokedEvent>(extraBufferCapacity = 4)
    val deviceRevokedEvents: SharedFlow<DeviceRevokedEvent> = _deviceRevokedEvents.asSharedFlow()
    private val refreshMutex = Mutex()

    open suspend fun <T> invoke(
        rpcName: String,
        bodyJson: String,
        deviceId: String? = null,
        decode: (JsonObject) -> T,
    ): RpcResult<T> = withContext(ioDispatcher) {
        val initialToken = SupabaseClient.ensureValidAccessToken()
            .takeIf { it.isNotBlank() && it != SupabaseConfig.ANON_KEY }
            ?: SupabaseClient.accessToken.value?.takeIf { it.isNotBlank() && it != SupabaseConfig.ANON_KEY }
            ?: return@withContext RpcResult.Unauthorized("No active user session")

        val resolvedDeviceId = deviceId?.takeIf { it.isNotBlank() } ?: "unknown"
        val firstAttempt = executeOnce(rpcName, bodyJson, initialToken, resolvedDeviceId)

        val finalResponse = if (firstAttempt.status == HttpStatusCode.Unauthorized) {
            val refreshed = refreshMutex.withLock {
                val current = SupabaseClient.accessToken.value
                    ?.takeIf { it.isNotBlank() && it != SupabaseConfig.ANON_KEY && it != initialToken }
                current ?: SupabaseClient.refreshSession().getOrNull()
            } ?: return@withContext RpcResult.Unauthorized("Session refresh failed")
            executeOnce(rpcName, bodyJson, refreshed, resolvedDeviceId)
        } else {
            firstAttempt
        }

        parseResponse(finalResponse, resolvedDeviceId, decode)
    }

    private suspend fun executeOnce(
        rpcName: String,
        bodyJson: String,
        accessToken: String,
        deviceId: String,
    ): HttpResponse {
        val url = "${SupabaseConfig.SUPABASE_URL}/rest/v1/rpc/$rpcName"
        return httpClient.post(url) {
            header("apikey", SupabaseConfig.ANON_KEY)
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("X-Sync-Contract-Version", "5")
            header("X-Client-Platform", resolvePlatformDeviceInfo().platform)
            header("X-Device-Id", deviceId)
            contentType(ContentType.Application.Json)
            setBody(bodyJson)
        }
    }

    private suspend fun <T> parseResponse(
        response: HttpResponse,
        deviceId: String,
        decode: (JsonObject) -> T,
    ): RpcResult<T> {
        val statusCode = response.status.value
        val rawBody = runCatching { response.bodyAsText() }.getOrElse { "" }
        if (statusCode == 429) {
            val retryAfterSec = response.headers[HttpHeaders.RetryAfter]?.toLongOrNull() ?: 15L
            return RpcResult.RateLimited((retryAfterSec * 1000L).coerceIn(1_000L, 120_000L))
        }
        return decodeRawResponse(statusCode, rawBody, deviceId, decode)
    }

    protected fun <T> decodeRawResponse(
        statusCode: Int,
        rawBody: String,
        deviceId: String,
        decode: (JsonObject) -> T,
    ): RpcResult<T> {
        if (statusCode == 401) {
            return RpcResult.Unauthorized("HTTP 401")
        }
        if (statusCode == 429) {
            return RpcResult.RateLimited(15_000L)
        }
        if (statusCode >= 500) {
            return RpcResult.TransientFailure(statusCode, rawBody.take(256))
        }
        if (statusCode !in 200..299) {
            return RpcResult.TerminalError("HTTP_$statusCode", rawBody.take(256))
        }

        val root = runCatching { json.parseToJsonElement(rawBody).jsonObject }.getOrElse { e ->
            return RpcResult.TransientFailure(statusCode, "Malformed RPC JSON: ${e.message}")
        }

        val serverTimeMs = root["server_time_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        if (serverTimeMs != null) {
            skewEstimator.recordSample(serverTimeMs)
        }

        return when (val topStatus = root["status"]?.jsonPrimitive?.contentOrNull) {
            "UNAUTHORIZED" -> RpcResult.Unauthorized("RPC UNAUTHORIZED")
            "DEVICE_REVOKED" -> {
                val revokedAt = root["revoked_at"]?.jsonPrimitive?.contentOrNull
                _deviceRevokedEvents.tryEmit(DeviceRevokedEvent(deviceId = deviceId, revokedAt = revokedAt))
                RpcResult.DeviceRevoked(revokedAt)
            }
            "CURSOR_EXPIRED" -> {
                val headRev = root["head_rev"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
                val minRev = root["min_available_rev"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
                RpcResult.CursorExpired(headRev = headRev, minAvailableRev = minRev)
            }
            "INVALID_DEVICE_ID", "INVALID_BATCH", "BATCH_TOO_LARGE", "INVALID_CURSOR" ->
                RpcResult.TerminalError(topStatus, rawBody.take(256))
            else -> runCatching { RpcResult.Ok(decode(root), serverTimeMs) }
                .getOrElse { e -> RpcResult.TerminalError("DECODE_ERROR", e.message ?: "decode failed") }
        }
    }

    companion object {
        fun default(): SupabaseRpcExecutor = SupabaseRpcExecutor()

        val DefaultJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            isLenient = true
            coerceInputValues = true
        }
    }
}

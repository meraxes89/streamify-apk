package com.streamify.sync.v5.planec

import com.streamify.supabase.v5.PlaneCPresenceClient
import com.streamify.supabase.v5.RecordDeviceSessionResponse
import com.streamify.supabase.v5.RpcResult

data class DeviceMetadata(
    val deviceId: String,
    val deviceName: String,
    val platform: String,
    val osVersion: String? = null,
    val deviceModel: String? = null,
    val appVersion: String,
    val versionCode: Int,
    val buildFlavor: String = "prod",
)

/**
 * Registers the local device in `user_devices` on launch/login/version-change,
 * throttled to at most once every 6 hours while foregrounded.
 */
class DeviceSessionRegistrar(
    private val client: PlaneCPresenceClient,
    private val nowMs: () -> Long,
) {
    private var lastRegisteredAtMs: Long = Long.MIN_VALUE
    private var lastVersionKey: String? = null

    suspend fun registerIfNeeded(
        meta: DeviceMetadata,
        force: Boolean = false,
    ): RpcResult<RecordDeviceSessionResponse>? {
        val versionKey = "${meta.deviceId}|${meta.appVersion}|${meta.versionCode}|${meta.buildFlavor}"
        val now = nowMs()
        val versionChanged = versionKey != lastVersionKey
        val intervalElapsed = lastRegisteredAtMs == Long.MIN_VALUE || (now - lastRegisteredAtMs) >= REFRESH_INTERVAL_MS

        if (!force && !versionChanged && !intervalElapsed) {
            return null
        }

        val res = client.recordDeviceSession(
            deviceId = meta.deviceId,
            deviceName = meta.deviceName,
            platform = meta.platform,
            osVersion = meta.osVersion,
            deviceModel = meta.deviceModel,
            appVersion = meta.appVersion,
            versionCode = meta.versionCode,
            buildFlavor = meta.buildFlavor,
        )
        if (res is RpcResult.Ok && res.data.status == "OK") {
            lastRegisteredAtMs = now
            lastVersionKey = versionKey
        }
        return res
    }

    fun reset() {
        lastRegisteredAtMs = Long.MIN_VALUE
        lastVersionKey = null
    }

    companion object {
        const val REFRESH_INTERVAL_MS: Long = 6L * 60L * 60L * 1000L
    }
}

package com.streamify.sync.v5

import com.streamify.supabase.v5.RpcResult
import com.streamify.supabase.v5.SupabaseRpcExecutor
import kotlinx.serialization.json.JsonObject

internal data class FakeRpcResponse(
    val httpStatus: Int,
    val body: String,
)

internal class FakeRpcExecutor(
    private val handler: suspend (rpcName: String, bodyJson: String) -> FakeRpcResponse,
) : SupabaseRpcExecutor() {
    override suspend fun <T> invoke(
        rpcName: String,
        bodyJson: String,
        deviceId: String?,
        decode: (JsonObject) -> T,
    ): RpcResult<T> {
        val res = handler(rpcName, bodyJson)
        return decodeRawResponse(res.httpStatus, res.body, deviceId ?: "test-device", decode)
    }
}

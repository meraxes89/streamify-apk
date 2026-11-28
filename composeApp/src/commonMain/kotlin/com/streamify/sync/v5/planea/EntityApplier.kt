package com.streamify.sync.v5.planea

import kotlinx.serialization.json.JsonObject

/**
 * Applies a single Plane A entity delta or bootstrap row into the local repository.
 * Always invoked inside [com.streamify.sync.v5.SyncWriteGuard.withRemoteApply].
 */
interface PlaneAEntityApplier {
    val entityType: String

    suspend fun applyRemote(
        op: String,
        entityKey: String,
        hlc: Long,
        modifiedBy: String,
        payload: JsonObject,
        bootstrapGenId: String? = null,
    )
}

class EntityApplierRegistry(
    appliers: List<PlaneAEntityApplier>,
) {
    private val byType = appliers.associateBy { it.entityType }

    suspend fun apply(
        entityType: String,
        op: String,
        entityKey: String,
        hlc: Long,
        modifiedBy: String,
        payload: JsonObject,
        bootstrapGenId: String? = null,
    ): Boolean {
        val applier = byType[entityType] ?: return false
        applier.applyRemote(op, entityKey, hlc, modifiedBy, payload, bootstrapGenId)
        return true
    }
}

package com.streamify.supabase

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OAuthFlowCoordinatorTest {
    @Test
    fun callbackCompletesEvenWhenCallerScopeCancels() = runBlocking {
        val appScope = CoroutineScope(SupervisorJob())
        val uiScope = CoroutineScope(SupervisorJob())
        val coordinator = OAuthFlowCoordinator(scope = appScope)

        coordinator.onBrowserFallbackLaunched()
        assertTrue(coordinator.isAuthenticating.value)

        val gate = CompletableDeferred<Unit>()
        var job: Job? = null
        uiScope.launch {
            job =
                coordinator.submitCallback {
                    gate.await()
                    Result.success(UserProfile(id = "u1", email = "u1@example.com", name = "User"))
                }
        }.join()

        // Simulates Compose LaunchedEffect cancelling immediately when intent is cleared.
        uiScope.cancel()
        gate.complete(Unit)
        job?.join()

        assertFalse(coordinator.isAuthenticating.value)
        assertNull(coordinator.authError.value)
        appScope.cancel()
    }

    @Test
    fun failedCallbackResetsAuthenticatingAndSurfacesError() = runBlocking {
        val appScope = CoroutineScope(SupervisorJob())
        val coordinator = OAuthFlowCoordinator(scope = appScope)

        coordinator.onBrowserFallbackLaunched()
        assertTrue(coordinator.isAuthenticating.value)

        val job =
            coordinator.submitCallback {
                Result.failure(Exception("Token exchange timed out"))
            }
        job.join()

        assertFalse(coordinator.isAuthenticating.value)
        assertEquals("Token exchange timed out", coordinator.authError.value)

        coordinator.clearError()
        assertNull(coordinator.authError.value)
        appScope.cancel()
    }
}

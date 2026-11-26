package kotlinx.coroutines

import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RunBlockingStubTest {

    @Test
    fun runBlockingReturnsValueForSynchronousBlock() {
        val value = runBlocking { 42 }
        assertEquals(42, value)
    }

    @Test
    fun runBlockingFailsFastWhenBlockSuspends() {
        assertFailsWith<UnsupportedOperationException> {
            runBlocking {
                suspendCoroutineUninterceptedOrReturn<String> { _: Continuation<String> ->
                    COROUTINE_SUSPENDED
                }
            }
        }
    }
}

package kotlinx.coroutines

import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

fun <T> runBlocking(
    context: CoroutineContext = EmptyCoroutineContext,
    block: suspend CoroutineScope.() -> T,
): T {
    var result: T? = null
    var exception: Throwable? = null
    var completed = false
    val continuation = object : kotlin.coroutines.Continuation<T> {
        override val context: CoroutineContext = context
        override fun resumeWith(res: Result<T>) {
            completed = true
            res.onSuccess { result = it }
            res.onFailure { exception = it }
        }
    }
    val scope = object : CoroutineScope {
        override val coroutineContext: CoroutineContext = context
    }
    block.startCoroutine(scope, continuation)
    exception?.let { throw it }
    if (!completed) {
        throw UnsupportedOperationException("runBlocking cannot suspend on wasmJs")
    }
    @Suppress("UNCHECKED_CAST")
    return result as T
}

val Dispatchers.IO: CoroutineDispatcher get() = Dispatchers.Default
val IO: CoroutineDispatcher get() = Dispatchers.Default

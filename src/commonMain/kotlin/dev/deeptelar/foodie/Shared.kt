package dev.deeptelar.foodie

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What [read] returns for a key, read once for the nodes that need it at the same time: the first
 * to ask reads it, and the others wait for that answer. A read that fails is not kept, so the
 * next one asks again, and a node whose reader was cancelled reads it itself.
 */
internal class Shared<K, V>(
    private val read: suspend (K) -> V,
) {
    private val mutex = Mutex()
    private val reads = mutableMapOf<K, CompletableDeferred<V>>()

    suspend fun get(key: K): V {
        val (answer, mine) = mutex.withLock { reads[key]?.let { it to false } ?: (CompletableDeferred<V>().also { reads[key] = it } to true) }
        if (!mine) {
            return try {
                answer.await()
            } catch (e: CancellationException) {
                // The node that read it was cancelled, not this one: this one reads it.
                currentCoroutineContext().ensureActive()
                get(key)
            }
        }
        return try {
            read(key).also { answer.complete(it) }
        } catch (e: Throwable) {
            mutex.withLock { reads.remove(key) }
            answer.completeExceptionally(e)
            throw e
        }
    }
}

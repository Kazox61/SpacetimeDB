package com.clockworklabs.spacetimedb_kotlin_sdk.shared_client

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout

/** One-shot start barrier: participants suspend instead of occupying worker threads. */
internal class CoroutineBarrier(parties: Int) {
    init {
        require(parties > 0)
    }

    private val remaining = AtomicInteger(parties)
    private val ready = CompletableDeferred<Unit>()

    suspend fun await() {
        val left = remaining.decrementAndGet()
        check(left >= 0) { "Barrier cannot be reused" }
        if (left == 0) ready.complete(Unit)
        withTimeout(30_000L) { ready.await() }
    }
}

package com.clockworklabs.spacetimedb_kotlin_sdk.shared_client

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CoroutineBarrierTest {
    @Test
    fun `seventeen participants complete on a single test thread`() = runTest {
        val barrier = CoroutineBarrier(17)
        var arrived = 0
        var completed = 0
        val jobs = List(17) {
            launch {
                arrived++
                barrier.await()
                assertEquals(17, arrived)
                completed++
            }
        }
        jobs.forEach { it.join() }
        assertEquals(17, completed)
    }

    @Test
    fun `missing participant times out rather than hanging`() = runTest {
        val barrier = CoroutineBarrier(2)
        assertFailsWith<TimeoutCancellationException> { barrier.await() }
    }
}

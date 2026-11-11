package com.clockworklabs.spacetimedb_kotlin_sdk.integration

import com.clockworklabs.spacetimedb_kotlin_sdk.shared_client.LogLevel
import com.clockworklabs.spacetimedb_kotlin_sdk.shared_client.Logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import module_bindings.db
import module_bindings.reducers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProtocolV3Test {

    @Test
    fun `connection negotiates protocol v3`() = runBlocking {
        val previousHandler = Logger.handler
        val previousLevel = Logger.level
        val logs = java.util.concurrent.ConcurrentLinkedQueue<String>()
        Logger.handler = { _, message -> logs.add(message) }
        Logger.level = LogLevel.DEBUG
        try {
            val client = connectToDb()
            assertTrue(
                logs.any { it.contains("Negotiated websocket protocol v3.bsatn.spacetimedb") },
                "Expected v3 to be negotiated; logs: $logs",
            )
            client.conn.disconnect()
        } finally {
            Logger.handler = previousHandler
            Logger.level = previousLevel
        }
    }

    @Test
    fun `burst of reducer calls arrives complete and in order`() = runBlocking {
        val client = connectToDb().subscribeAll()
        val tag = "v3-burst-${System.nanoTime()}"
        val count = 50
        val received = java.util.Collections.synchronizedList(mutableListOf<String>())
        val all = CompletableDeferred<Unit>()
        client.conn.db.note.onInsert { _, note ->
            if (note.tag == tag) {
                received.add(note.content)
                if (received.size == count) all.complete(Unit)
            }
        }

        // Queued back to back, so the send loop hands them to the transport together.
        repeat(count) { client.conn.reducers.addNote("note-$it", tag) }
        withTimeout(DEFAULT_TIMEOUT_MS) { all.await() }
        assertEquals((0 until count).map { "note-$it" }, received.toList())

        val ids = client.conn.db.note.all().filter { it.tag == tag }.map { it.id }.toSet()
        val deleted = CompletableDeferred<Unit>()
        val remaining = ids.toMutableSet()
        client.conn.db.note.onDelete { _, note ->
            synchronized(remaining) { if (remaining.remove(note.id) && remaining.isEmpty()) deleted.complete(Unit) }
        }
        ids.forEach { client.conn.reducers.deleteNote(it) }
        withTimeout(DEFAULT_TIMEOUT_MS) { deleted.await() }
        client.conn.disconnect()
    }
}

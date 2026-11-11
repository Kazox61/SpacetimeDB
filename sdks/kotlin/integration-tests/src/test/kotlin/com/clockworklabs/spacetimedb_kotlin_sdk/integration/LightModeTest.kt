package com.clockworklabs.spacetimedb_kotlin_sdk.integration

import com.clockworklabs.spacetimedb_kotlin_sdk.shared_client.DbConnection
import com.clockworklabs.spacetimedb_kotlin_sdk.shared_client.type.Identity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import module_bindings.db
import module_bindings.reducers
import module_bindings.withModuleBindings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies that light mode connections work correctly.
 * Light mode only asks the server to leave other clients' transaction details out of
 * table updates. It never skips initial subscription rows, and over protocol v2 the
 * server sends no such details to non-callers anyway.
 */
class LightModeTest {

    private suspend fun connectLightMode(): ConnectedClient {
        val identityDeferred = CompletableDeferred<Pair<Identity, String>>()

        val conn = DbConnection.Builder()
            .withHttpClient(createTestHttpClient())
            .withUri(HOST)
            .withDatabaseName(DB_NAME)
            .withLightMode(true)
            .withModuleBindings()
            .onConnect { _, identity, tok ->
                identityDeferred.complete(identity to tok)
            }
            .onConnectError { _, e ->
                identityDeferred.completeExceptionally(e)
            }
            .build()

        val (identity, tok) = withTimeout(DEFAULT_TIMEOUT_MS) { identityDeferred.await() }
        return ConnectedClient(conn = conn, identity = identity, token = tok)
    }

    @Test
    fun `connect in light mode and call reducer`() = runBlocking {
        val client = connectLightMode()

        // Subscribe, then check that updates still arrive
        val applied = CompletableDeferred<Unit>()
        client.conn.subscriptionBuilder()
            .onApplied { _ -> applied.complete(Unit) }
            .subscribe(listOf("SELECT * FROM message"))
        withTimeout(DEFAULT_TIMEOUT_MS) { applied.await() }

        // Send a message and verify we receive the insert callback
        val text = "light-mode-${System.nanoTime()}"
        val received = CompletableDeferred<String>()
        client.conn.db.message.onInsert { _, row ->
            if (row.text == text) received.complete(row.text)
        }
        client.conn.reducers.sendMessage(text)

        assertEquals(text, withTimeout(DEFAULT_TIMEOUT_MS) { received.await() })
        client.conn.disconnect()
    }

    @Test
    fun `light mode subscription still receives initial rows`() = runBlocking {
        val writer = connectToDb().subscribeAll()
        val content = "light-mode-initial-${System.nanoTime()}"
        val inserted = CompletableDeferred<ULong>()
        writer.conn.db.note.onInsert { _, note -> if (note.content == content) inserted.complete(note.id) }
        writer.conn.reducers.addNote(content, "light-mode")
        val noteId = withTimeout(DEFAULT_TIMEOUT_MS) { inserted.await() }

        val client = connectLightMode()
        val applied = CompletableDeferred<Unit>()
        client.conn.subscriptionBuilder()
            .onApplied { _ -> applied.complete(Unit) }
            .subscribe(listOf("SELECT * FROM note"))
        withTimeout(DEFAULT_TIMEOUT_MS) { applied.await() }
        assertTrue(client.conn.db.note.all().any { it.id == noteId }, "Light mode must still receive initial rows")
        client.conn.disconnect()

        // Wait for the delete to commit so no note outlives the test.
        val deleted = CompletableDeferred<Unit>()
        writer.conn.db.note.onDelete { _, note -> if (note.id == noteId) deleted.complete(Unit) }
        writer.conn.reducers.deleteNote(noteId)
        withTimeout(DEFAULT_TIMEOUT_MS) { deleted.await() }
        writer.conn.disconnect()
    }
}

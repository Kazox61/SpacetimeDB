package com.clockworklabs.spacetimedb_kotlin_sdk.integration

import com.clockworklabs.spacetimedb_kotlin_sdk.shared_client.DbConnection
import com.clockworklabs.spacetimedb_kotlin_sdk.shared_client.SessionBusyException
import com.clockworklabs.spacetimedb_kotlin_sdk.shared_client.type.ConnectionId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import module_bindings.db
import module_bindings.withModuleBindings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SessionIdTest {

    private class Attempt(val conn: DbConnection, val connected: CompletableDeferred<Unit>, val closed: CompletableDeferred<Throwable?>) {
        /** True once the handshake completes, false if the connection closes first. */
        suspend fun succeeded(): Boolean = select {
            connected.onAwait { true }
            closed.onAwait { false }
        }
    }

    private suspend fun connectWithSession(token: String, sessionId: ConnectionId): Attempt {
        val connected = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Throwable?>()
        val conn = DbConnection.Builder()
            .withHttpClient(createTestHttpClient())
            .withUri(HOST)
            .withDatabaseName(DB_NAME)
            .withToken(token)
            .withSessionId(sessionId)
            .withModuleBindings()
            .onConnect { _, _, _ -> connected.complete(Unit) }
            .onDisconnect { _, cause -> closed.complete(cause) }
            .onConnectError { _, cause -> closed.complete(cause) }
            .build()
        return Attempt(conn, connected, closed)
    }

    @Test
    fun `second connection of a live session is refused until the first is torn down`() = runBlocking {
        val token = connectToDb().also { it.conn.disconnect() }.token
        val sessionId = ConnectionId.random()

        val first = connectWithSession(token, sessionId)
        withTimeout(DEFAULT_TIMEOUT_MS) { first.connected.await() }

        val refused = connectWithSession(token, sessionId)
        val cause = withTimeout(DEFAULT_TIMEOUT_MS) { refused.closed.await() }
        assertIs<SessionBusyException>(cause)
        assertTrue(!refused.connected.isCompleted, "A refused connection must never complete its handshake")

        // The refusal makes the server stop the connection holding the session. The session frees
        // up once that connection's client_disconnected has run, so a retry may still be refused
        // briefly before it succeeds. (The server aborts the old connection without a close frame,
        // so its client is not told; in practice that client is the one that already gave up on it.)
        val replacement = withTimeout(DEFAULT_TIMEOUT_MS) {
            var attempt = connectWithSession(token, sessionId)
            while (!attempt.succeeded()) {
                assertIs<SessionBusyException>(attempt.closed.await())
                delay(100)
                attempt = connectWithSession(token, sessionId)
            }
            attempt
        }

        // client_disconnected for the old connection ran before client_connected for the new one,
        // so the module's online flag ends up true instead of being cleared by a late disconnect.
        val subscribed = CompletableDeferred<Unit>()
        replacement.conn.subscriptionBuilder()
            .onApplied { _ -> subscribed.complete(Unit) }
            .onError { _, err -> subscribed.completeExceptionally(RuntimeException("$err")) }
            .subscribe("SELECT * FROM user")
        withTimeout(DEFAULT_TIMEOUT_MS) { subscribed.await() }
        val identity = replacement.conn.identity
        assertEquals(true, replacement.conn.db.user.identity.find(checkNotNull(identity))?.online)

        replacement.conn.disconnect()
        first.conn.disconnect()
    }
}

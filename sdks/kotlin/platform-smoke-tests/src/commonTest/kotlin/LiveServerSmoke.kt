package com.clockworklabs.spacetimedb.smoketest

import com.clockworklabs.spacetimedb_kotlin_sdk.shared_client.DbConnection
import com.clockworklabs.spacetimedb_kotlin_sdk.shared_client.ConnectionSession
import com.clockworklabs.spacetimedb_kotlin_sdk.shared_client.ReconnectPolicy
import com.clockworklabs.spacetimedb_kotlin_sdk.shared_client.SessionState
import com.clockworklabs.spacetimedb_kotlin_sdk.shared_client.TokenStore
import io.ktor.client.HttpClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import module_bindings.db
import module_bindings.reducers
import module_bindings.withModuleBindings
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotSame
import kotlin.time.Duration.Companion.milliseconds

/** Runs on the device, using its real WebSocket engine and generated common bindings. */
suspend fun liveServerSmoke(httpClient: HttpClient, uri: String) {
    var connection: DbConnection? = null
    try {
        withTimeout(30_000L) {
            val connected = CompletableDeferred<Unit>()
            val conn = DbConnection.Builder()
                .withHttpClient(httpClient)
                .withUri(uri)
                .withDatabaseName("kotlin-integration-test")
                .withModuleBindings()
                .onConnect { _, _, _ -> connected.complete(Unit) }
                .onConnectError { _, error -> connected.completeExceptionally(error) }
                .build()
            connection = conn
            connected.await()

            val subscribed = CompletableDeferred<Unit>()
            conn.subscriptionBuilder()
                .onApplied { subscribed.complete(Unit) }
                .onError { _, error -> subscribed.completeExceptionally(IllegalStateException("$error")) }
                .subscribe(listOf("SELECT * FROM message"))
            subscribed.await()

            val text = "platform-smoke-${conn.identity}"
            val inserted = CompletableDeferred<ULong>()
            conn.db.message.onInsert { _, row ->
                if (row.text == text) inserted.complete(row.id)
            }
            conn.reducers.sendMessage(text)
            val id = inserted.await()
            assertEquals(text, conn.db.message.all().single { it.id == id }.text)

            val deleted = CompletableDeferred<Unit>()
            conn.db.message.onDelete { _, row ->
                if (row.id == id) deleted.complete(Unit)
            }
            conn.reducers.deleteMessage(id)
            deleted.await()
            assertTrue(conn.db.message.all().none { it.id == id })
        }
    } finally {
        connection?.disconnect()
        httpClient.close()
    }
}

/** Exercise fresh-session reconnect and token reuse over the platform's real network engine. */
suspend fun lifecycleSmoke(httpClient: HttpClient, uri: String) {
    try {
        withTimeout(30_000L) {
            coroutineScope {
                var savedToken: String? = null
                val store = object : TokenStore {
                    override suspend fun load(): String? = savedToken
                    override suspend fun save(token: String) { savedToken = token }
                }
                val foreground = MutableStateFlow(true)
                val session = ConnectionSession(
                    tokenStore = store,
                    policy = ReconnectPolicy(initialDelay = 100.milliseconds),
                    builder = {
                        DbConnection.Builder().withHttpClient(httpClient).withUri(uri)
                            .withDatabaseName("kotlin-integration-test").withModuleBindings()
                    },
                )
                val job = launch { session.run(foreground) }
                try {
                    val first = session.state.filterIsInstance<SessionState.Connected>().first().connection
                    val identity = first.identity
                    assertTrue(savedToken != null)
                    foreground.value = false
                    session.state.filterIsInstance<SessionState.Paused>().first()
                    assertTrue(!first.isActive)
                    foreground.value = true
                    val second = session.state.filterIsInstance<SessionState.Connected>().first().connection
                    assertNotSame(first, second)
                    assertEquals(identity, second.identity)
                    // Simulate loss of the active socket and verify automatic connection replacement.
                    second.disconnect()
                    val third = session.state.filterIsInstance<SessionState.Connected>()
                        .first { it.connection !== second }.connection
                    assertEquals(identity, third.identity)
                } finally {
                    job.cancelAndJoin()
                }
                assertTrue(session.state.value is SessionState.Stopped)
            }
        }
    } finally {
        httpClient.close()
    }
}

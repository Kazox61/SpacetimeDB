package com.clockworklabs.spacetimedb_kotlin_sdk.shared_client

import com.clockworklabs.spacetimedb_kotlin_sdk.shared_client.type.ConnectionId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.emptyFlow
import com.clockworklabs.spacetimedb_kotlin_sdk.shared_client.transport.Transport
import com.clockworklabs.spacetimedb_kotlin_sdk.shared_client.protocol.ClientMessage
import com.clockworklabs.spacetimedb_kotlin_sdk.shared_client.protocol.ServerMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionSessionTest {
    @Test
    fun `cancelling an in flight handshake releases the transport`() = runTest {
        var released = false
        val transport = object : Transport {
            override suspend fun connect(): Unit = awaitCancellation()
            override suspend fun send(message: ClientMessage) = Unit
            override fun incoming() = emptyFlow<ServerMessage>()
            override suspend fun disconnect() { released = true }
        }
        val connection = createConnectionWithTransport(transport)
        val job = launch { connection.connect() }
        runCurrent()
        job.cancelAndJoin()
        assertTrue(released)
        assertTrue(job.isCancelled)
        assertFalse(connection.isActive)
    }

    private class Store : TokenStore {
        var token: String? = "saved-token"
        var failLoad = false
        var failSave = false
        override suspend fun load(): String? {
            check(!failLoad) { "Credential storage unavailable" }
            return token
        }
        override suspend fun save(token: String) {
            check(!failSave) { "Credential write failed" }
            this.token = token
        }
    }

    private class Harness(
        val scope: TestScope,
        val store: Store = Store(),
        var handshake: Boolean = true,
        var failConnect: Boolean = false,
        /** The first [busyAttempts] connections are refused as if the session were still held. */
        var busyAttempts: Int = 0,
    ) {
        val tokens = mutableListOf<String?>()
        val sessionIds = mutableListOf<ConnectionId>()
        val transports = mutableListOf<FakeTransport>()
        val connections = mutableListOf<DbConnection>()
        var setups = 0
        fun session(policy: ReconnectPolicy = ReconnectPolicy()): ConnectionSession = ConnectionSession(
            connector = SessionConnector { token, sessionId, ready, closed, failed ->
                tokens.add(token)
                sessionIds.add(sessionId)
                val transport = FakeTransport(if (failConnect) IllegalStateException("Offline") else null)
                transports.add(transport)
                val conn = with(scope) {
                    buildTestConnection(
                        transport,
                        onConnect = { _, _, newToken -> ready(newToken) },
                        onDisconnect = { _, cause -> closed(cause) },
                        onConnectError = { _, cause -> failed(cause) },
                    )
                }
                connections.add(conn)
                if (busyAttempts > 0) {
                    busyAttempts--
                    transport.closeWithError(SessionBusyException())
                } else if (handshake && !failConnect) {
                    transport.sendToClient(initialConnectionMsg())
                }
                conn
            },
            tokenStore = store,
            policy = policy,
            onConnected = { setups++ },
        )
    }

    @Test
    fun `background disconnects and resume restores token on fresh connection`() = runTest {
        val h = Harness(this)
        val session = h.session()
        val foreground = MutableStateFlow(true)
        val job = backgroundScope.launch { session.run(foreground) }
        runCurrent()
        val first = assertIs<SessionState.Connected>(session.state.value).connection
        assertEquals(TEST_TOKEN, h.store.token)
        foreground.value = false
        runCurrent()
        assertIs<SessionState.Paused>(session.state.value)
        assertFalse(first.isActive)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, h.connections.size)
        foreground.value = true
        runCurrent()
        val second = assertIs<SessionState.Connected>(session.state.value).connection
        assertNotSame(first, second)
        assertEquals(listOf<String?>("saved-token", TEST_TOKEN), h.tokens)
        assertEquals(2, h.setups)
        job.cancelAndJoin()
        assertFalse(second.isActive)
        assertIs<SessionState.Stopped>(session.state.value)
    }

    @Test
    fun `server close retries after backoff without replaying operations`() = runTest {
        val h = Harness(this)
        val session = h.session()
        val job = backgroundScope.launch { session.run() }
        runCurrent()
        h.transports.single().closeFromServer()
        runCurrent()
        assertIs<SessionState.Retrying>(session.state.value)
        advanceTimeBy(999)
        runCurrent()
        assertEquals(1, h.connections.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, h.connections.size)
        assertEquals(2, h.setups)
        assertTrue(h.transports.last().sentMessages.isEmpty())
        job.cancelAndJoin()
    }

    @Test
    fun `every reconnect reuses the session id`() = runTest {
        val h = Harness(this)
        val session = h.session()
        val job = backgroundScope.launch { session.run() }
        runCurrent()
        h.transports.single().closeFromServer()
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, h.sessionIds.size)
        assertEquals(1, h.sessionIds.toSet().size)
        job.cancelAndJoin()
        // A separate session object is a separate logical session.
        val otherJob = backgroundScope.launch { h.session().run() }
        runCurrent()
        otherJob.cancelAndJoin()
        assertEquals(3, h.sessionIds.size)
        assertNotEquals(h.sessionIds.first(), h.sessionIds.last())
    }

    @Test
    fun `busy session retries after initial delay without spending retries`() = runTest {
        val h = Harness(this, busyAttempts = 2)
        val session = h.session(ReconnectPolicy(maxRetries = 0))
        val job = backgroundScope.launch { session.run() }
        runCurrent()
        val retrying = assertIs<SessionState.Retrying>(session.state.value)
        assertEquals(0, retrying.retry)
        assertIs<SessionBusyException>(retrying.cause)
        advanceTimeBy(1_000)
        runCurrent()
        assertIs<SessionState.Retrying>(session.state.value)
        advanceTimeBy(1_000)
        runCurrent()
        assertIs<SessionState.Connected>(session.state.value)
        assertEquals(3, h.connections.size)
        assertEquals(1, h.setups)
        job.cancelAndJoin()
    }

    @Test
    fun `failed connections stop after bounded retries`() = runTest {
        val h = Harness(this, failConnect = true)
        val session = h.session(ReconnectPolicy(maxRetries = 2))
        val job = backgroundScope.launch { session.run() }
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(3, h.connections.size)
        assertIs<SessionState.Failed>(session.state.value)
        job.join()
    }

    @Test
    fun `handshake timeout releases connection and terminates when retries disabled`() = runTest {
        val h = Harness(this, handshake = false)
        val session = h.session(ReconnectPolicy(maxRetries = 0, connectTimeout = 1.seconds))
        val job = backgroundScope.launch { session.run() }
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertIs<SessionState.Failed>(session.state.value)
        assertFalse(h.connections.single().isActive)
        job.join()
    }

    @Test
    fun `credential read failure never creates anonymous identity`() = runTest {
        val h = Harness(this)
        h.store.failLoad = true
        val session = h.session()
        session.run()
        assertTrue(h.connections.isEmpty())
        assertIs<SessionState.Failed>(session.state.value)
    }

    @Test
    fun `credential save failure closes connection without retrying`() = runTest {
        val h = Harness(this)
        h.store.failSave = true
        val session = h.session()
        val job = backgroundScope.launch { session.run() }
        runCurrent()
        assertIs<SessionState.Failed>(session.state.value)
        assertEquals(0, h.setups)
        assertFalse(h.connections.single().isActive)
        job.join()
    }

    @Test
    fun `background cancels pending retry`() = runTest {
        val h = Harness(this, failConnect = true)
        val foreground = MutableStateFlow(true)
        val session = h.session()
        val job = backgroundScope.launch { session.run(foreground) }
        runCurrent()
        foreground.value = false
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, h.connections.size)
        assertIs<SessionState.Paused>(session.state.value)
        job.cancelAndJoin()
    }

    @Test
    fun `backoff is capped and invalid policies are rejected`() {
        val policy = ReconnectPolicy(initialDelay = 1.seconds, maxDelay = 3.seconds)
        assertEquals(1.seconds, policy.delayFor(0))
        assertEquals(2.seconds, policy.delayFor(1))
        assertEquals(3.seconds, policy.delayFor(100))
        assertFailsWith<IllegalArgumentException> { ReconnectPolicy(maxRetries = -1) }
        assertFailsWith<IllegalArgumentException> { ReconnectPolicy(initialDelay = 0.seconds) }
    }

    @Test
    fun `secure mode rejects plaintext before creating transport`() = runTest {
        for (uri in listOf("http://localhost:3000", "ws://localhost:3000")) {
            val error = assertFailsWith<IllegalArgumentException> {
                DbConnection.Builder().withUri(uri).withRequireSecureTransport().build()
            }
            assertTrue(error.message.orEmpty().contains("Secure transport"))
        }
        for (uri in listOf("https://example.com", "wss://example.com")) {
            val error = assertFailsWith<IllegalArgumentException> {
                DbConnection.Builder().withUri(uri).withDatabaseName("test").withRequireSecureTransport().build()
            }
            assertTrue(error.message.orEmpty().contains("HttpClient"))
        }
    }
}

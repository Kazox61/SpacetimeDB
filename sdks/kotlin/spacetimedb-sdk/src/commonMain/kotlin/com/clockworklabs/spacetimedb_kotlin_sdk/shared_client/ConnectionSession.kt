package com.clockworklabs.spacetimedb_kotlin_sdk.shared_client

import com.clockworklabs.spacetimedb_kotlin_sdk.shared_client.type.ConnectionId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Application-owned credential storage. Use Keychain/Keystore-backed storage for durable tokens. */
public interface TokenStore {
    public suspend fun load(): String?
    public suspend fun save(token: String)
}

/** Bounded exponential backoff. A successful handshake resets the failure counter. */
public data class ReconnectPolicy(
    public val maxRetries: Int = 5,
    public val initialDelay: Duration = 1.seconds,
    public val maxDelay: Duration = 30.seconds,
    public val connectTimeout: Duration = 30.seconds,
) {
    init {
        require(maxRetries >= 0)
        require(initialDelay.isFinite() && initialDelay > Duration.ZERO)
        require(maxDelay.isFinite() && maxDelay >= initialDelay)
        require(connectTimeout.isFinite() && connectTimeout > Duration.ZERO)
    }

    internal fun delayFor(retry: Int): Duration {
        var result = initialDelay
        repeat(retry.coerceIn(0, 63)) { result = (result * 2).coerceAtMost(maxDelay) }
        return result
    }
}

/**
 * The server refused the connection because the session's previous connection is still live.
 * The server is now tearing that connection down, so a retry shortly after will succeed.
 */
public class SessionBusyException : Exception("Session is still held by a previous connection")

public sealed interface SessionState {
    public data object Stopped : SessionState
    public data object Paused : SessionState
    public data object Connecting : SessionState
    public data class Connected(public val connection: DbConnection) : SessionState
    public data class Retrying(public val retry: Int, public val cause: Throwable) : SessionState
    public data class Failed(public val cause: Throwable) : SessionState
}

internal fun interface SessionConnector {
    suspend fun connect(
        token: String?,
        sessionId: ConnectionId,
        connected: (String) -> Unit,
        disconnected: (Throwable?) -> Unit,
        failed: (Throwable) -> Unit,
    ): DbConnection
}

/**
 * Opt-in connection lifecycle. [run] owns one connection at a time and is cancelled with its caller.
 * Backgrounding cancels retries and disconnects; foregrounding creates a fresh connection using the
 * saved token. [onConnected] must recreate subscriptions and listeners for every new connection.
 * Every connection carries the same session id, so the server finishes tearing down the previous
 * connection (running `client_disconnected`) before it accepts the next one.
 * Reducers/procedures are NEVER replayed: their effects might already have committed on the server.
 * This module does not own or close the application's HttpClient, nor observe OS lifecycle itself.
 */
public class ConnectionSession internal constructor(
    private val connector: SessionConnector,
    private val tokenStore: TokenStore,
    private val policy: ReconnectPolicy,
    private val onConnected: suspend (DbConnection) -> Unit,
) {
    @OptIn(InternalSpacetimeApi::class)
    public constructor(
        tokenStore: TokenStore,
        policy: ReconnectPolicy = ReconnectPolicy(),
        builder: () -> DbConnection.Builder,
        onConnected: suspend (DbConnection) -> Unit = {},
    ) : this(
        SessionConnector { token, sessionId, connected, disconnected, failed ->
            builder().withToken(token).withSessionId(sessionId)
                .onConnect { _, _, newToken -> connected(newToken) }
                .onDisconnect { _, cause -> disconnected(cause) }
                .onConnectError { _, cause -> failed(cause) }
                .build()
        },
        tokenStore,
        policy,
        onConnected,
    )

    private val sessionId = ConnectionId.random()
    private val running = Mutex()
    private val mutableState = MutableStateFlow<SessionState>(SessionState.Stopped)
    public val state: StateFlow<SessionState> = mutableState.asStateFlow()

    /** Collect foreground changes until cancelled. Only one concurrent run is permitted. */
    public suspend fun run(foreground: Flow<Boolean> = flowOf(true)) {
        check(running.tryLock()) { "ConnectionSession is already running" }
        try {
            foreground.distinctUntilChanged().collectLatest { active ->
                if (active) maintainConnection() else mutableState.value = SessionState.Paused
            }
        } finally {
            if (mutableState.value !is SessionState.Failed) mutableState.value = SessionState.Stopped
            running.unlock()
        }
    }

    private suspend fun maintainConnection() {
        var failures = 0
        while (true) {
            val token = try {
                tokenStore.load()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // Never silently discard an unreadable token and reconnect as a new identity.
                mutableState.value = SessionState.Failed(failure)
                return
            }
            mutableState.value = SessionState.Connecting
            val ready = CompletableDeferred<String>()
            val closed = CompletableDeferred<Throwable?>()
            var connection: DbConnection? = null
            var fatal = false
            val failure = try {
                val newToken = withTimeout(policy.connectTimeout) {
                    connection = connector.connect(
                        token,
                        sessionId,
                        { ready.complete(it) },
                        { cause ->
                            closed.complete(cause)
                            ready.completeExceptionally(cause ?: IllegalStateException("Connection closed before authentication"))
                        },
                        { ready.completeExceptionally(it) },
                    )
                    ready.await()
                }
                val connected = checkNotNull(connection)
                // Persist credentials before exposing a connected session or calling application code.
                fatal = true
                tokenStore.save(newToken)
                onConnected(connected)
                fatal = false
                failures = 0
                mutableState.value = SessionState.Connected(connected)
                closed.await() ?: IllegalStateException("Server closed the connection")
            } catch (cancelled: CancellationException) {
                // A handshake timeout is retryable; lifecycle/caller cancellation is not.
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                cancelled
            } catch (failure: Exception) {
                failure
            } finally {
                withContext(NonCancellable) { connection?.disconnect() }
            }
            if (failure is SessionBusyException) {
                // Not a failed attempt: the server is releasing the session for us.
                mutableState.value = SessionState.Retrying(failures, failure)
                delay(policy.initialDelay)
                continue
            }
            if (fatal || failure is IllegalArgumentException || failures >= policy.maxRetries) {
                mutableState.value = SessionState.Failed(failure)
                return
            }
            failures++
            mutableState.value = SessionState.Retrying(failures, failure)
            delay(policy.delayFor(failures - 1))
        }
    }
}

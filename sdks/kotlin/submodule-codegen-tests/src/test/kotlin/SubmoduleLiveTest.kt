import com.clockworklabs.spacetimedb_kotlin_sdk.shared_client.DbConnection
import com.clockworklabs.spacetimedb_kotlin_sdk.shared_client.EventContext
import com.clockworklabs.spacetimedb_kotlin_sdk.shared_client.Status
import com.clockworklabs.spacetimedb_kotlin_sdk.shared_client.onFailure
import com.clockworklabs.spacetimedb_kotlin_sdk.shared_client.onSuccess
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import module_bindings.addQuery
import module_bindings.db
import module_bindings.myLib.EnvReadReducerReducer
import module_bindings.procedures
import module_bindings.reducers
import module_bindings.withModuleBindings
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Exercises submodule bindings against a server running the TypeScript fixture.
 * Run through `scripts/live_tests.py submodules`; skipped when no server is configured.
 */
class SubmoduleLiveTest {
    private val host: String? = System.getenv("SPACETIMEDB_HOST")
    private val database: String? = System.getenv("SPACETIMEDB_DB_NAME")

    @BeforeTest
    fun requireServer() {
        assumeTrue(host != null && database != null, "SPACETIMEDB_HOST and SPACETIMEDB_DB_NAME are not set")
    }

    private suspend fun connect(): DbConnection {
        val connected = CompletableDeferred<Unit>()
        val conn = DbConnection.Builder()
            .withHttpClient(HttpClient(OkHttp) { install(WebSockets) })
            .withUri(checkNotNull(host))
            .withDatabaseName(checkNotNull(database))
            .withModuleBindings()
            .onConnect { _, _, _ -> connected.complete(Unit) }
            .onConnectError { _, cause -> connected.completeExceptionally(cause) }
            .build()
        withTimeout(TIMEOUT_MS) { connected.await() }
        return conn
    }

    /** Subscribes through the typed builder, so the server parses the generated namespaced SQL. */
    private suspend fun DbConnection.subscribeToLibData() {
        val applied = CompletableDeferred<Unit>()
        subscriptionBuilder()
            .onApplied { _ -> applied.complete(Unit) }
            .onError { _, err -> applied.completeExceptionally(RuntimeException("$err")) }
            .addQuery { qb -> qb.myLib.libData() }
            .subscribe()
        withTimeout(TIMEOUT_MS) { applied.await() }
    }

    private fun marker(): String = "kotlin-${UUID.randomUUID()}"

    @Test
    fun `submodule reducer fills the submodule table and fires its callback`() = runBlocking {
        val conn = connect()
        conn.subscribeToLibData()
        val value = marker()
        val inserted = CompletableDeferred<Unit>()
        val reducerSeen = CompletableDeferred<Unit>()
        conn.db.myLib.libData.onInsert { _, row -> if (row.value == value) inserted.complete(Unit) }
        conn.reducers.myLib.onLibInsert { _, v -> if (v == value) reducerSeen.complete(Unit) }

        conn.reducers.myLib.libInsert(value)
        withTimeout(TIMEOUT_MS) { inserted.await() }
        withTimeout(TIMEOUT_MS) { reducerSeen.await() }
        assertTrue(conn.db.myLib.libData.all().any { it.value == value })
        conn.disconnect()
    }

    @Test
    fun `root reducer writing through the submodule reaches the client`() = runBlocking {
        val conn = connect()
        conn.subscribeToLibData()
        val value = marker()
        val inserted = CompletableDeferred<Unit>()
        conn.db.myLib.libData.onInsert { _, row -> if (row.value == value) inserted.complete(Unit) }

        conn.reducers.useSubmodule(value)
        withTimeout(TIMEOUT_MS) { inserted.await() }
        conn.disconnect()
    }

    @Test
    fun `submodule procedure returns its result`() = runBlocking {
        val conn = connect()
        conn.subscribeToLibData()
        val value = marker()
        val inserted = CompletableDeferred<Unit>()
        conn.db.myLib.libData.onInsert { _, row -> if (row.value == value) inserted.complete(Unit) }
        conn.reducers.myLib.libInsert(value)
        withTimeout(TIMEOUT_MS) { inserted.await() }

        val count = CompletableDeferred<ULong>()
        conn.procedures.myLib.libCount { _, result ->
            result.onSuccess { count.complete(it) }.onFailure { count.completeExceptionally(Exception("$it")) }
        }
        assertTrue(withTimeout(TIMEOUT_MS) { count.await() } >= 1uL)
        conn.disconnect()
    }

    @Test
    fun `failed submodule reducer without a handler reaches the root fallback`() = runBlocking {
        val conn = connect()
        val unhandled = CompletableDeferred<EventContext.Reducer<*>>()
        conn.reducers.onUnhandledReducerError { ctx ->
            if (ctx.reducerName == EnvReadReducerReducer.REDUCER_NAME) unhandled.complete(ctx)
        }

        // The host rejects environment access from inside the submodule namespace.
        conn.reducers.myLib.envReadReducer()
        val ctx = withTimeout(TIMEOUT_MS) { unhandled.await() }
        assertEquals("my_lib.env_read_reducer", ctx.reducerName)
        assertIs<Status.Failed>(ctx.status)
        conn.disconnect()
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}

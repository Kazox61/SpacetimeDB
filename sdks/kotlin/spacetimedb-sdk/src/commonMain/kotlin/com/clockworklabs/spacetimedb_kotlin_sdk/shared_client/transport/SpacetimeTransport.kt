package com.clockworklabs.spacetimedb_kotlin_sdk.shared_client.transport

import com.clockworklabs.spacetimedb_kotlin_sdk.shared_client.CompressionMode
import com.clockworklabs.spacetimedb_kotlin_sdk.shared_client.Logger
import com.clockworklabs.spacetimedb_kotlin_sdk.shared_client.SessionBusyException
import com.clockworklabs.spacetimedb_kotlin_sdk.shared_client.type.ConnectionId
import com.clockworklabs.spacetimedb_kotlin_sdk.shared_client.bsatn.BsatnWriter
import com.clockworklabs.spacetimedb_kotlin_sdk.shared_client.protocol.ClientMessage
import com.clockworklabs.spacetimedb_kotlin_sdk.shared_client.protocol.ServerMessage
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.appendPathSegments
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Transport abstraction for SpacetimeDB connections.
 * Allows injecting a fake transport in tests.
 */
internal interface Transport {
    suspend fun connect()
    suspend fun send(message: ClientMessage)
    /** Sends [messages] in order; a transport may coalesce them into fewer frames. */
    suspend fun sendAll(messages: List<ClientMessage>) {
        for (message in messages) send(message)
    }
    fun incoming(): Flow<ServerMessage>
    suspend fun disconnect()
}

/**
 * WebSocket transport for SpacetimeDB.
 * Handles connection, message encoding/decoding, and compression.
 */
internal class SpacetimeTransport(
    private val client: HttpClient,
    private val baseUrl: String,
    private val nameOrAddress: String,
    private val connectionId: ConnectionId,
    private val authToken: String? = null,
    private val compression: CompressionMode = CompressionMode.GZIP,
    private val lightMode: Boolean = false,
    private val confirmedReads: Boolean? = null,
    private val sessionId: ConnectionId? = null,
) : Transport {
    private val _session = atomic<DefaultWebSocketSession?>(null)
    private val _coalesceOutgoing = atomic(false)

    internal companion object {
        /** WebSocket sub-protocol identifier for BSATN v2. */
        const val WS_PROTOCOL: String = "v2.bsatn.spacetimedb"

        /** BSATN v3: the v2 messages, but one frame may carry several of them in either direction. */
        const val WS_PROTOCOL_V3: String = "v3.bsatn.spacetimedb"

        /** Upper bound for a coalesced outgoing frame, so one burst does not form a huge frame. */
        const val MAX_OUTGOING_FRAME_BYTES: Int = 256 * 1024

        /** Close code the server sends when another live connection still holds [sessionId]. */
        const val SESSION_BUSY_CLOSE_CODE: Short = 4000

        /** The `{"token": "…"}` body of `POST /v1/identity/websocket-token`. */
        private val WEBSOCKET_TOKEN = Regex("\"token\"\\s*:\\s*\"([^\"]+)\"")
    }



    /**
     * Connects to the SpacetimeDB WebSocket endpoint.
     * Passes the auth token as a Bearer Authorization header on the WebSocket connection. The
     * browser's WebSocket can't carry headers, so there the token is first traded for a short-lived
     * one that goes in the URL, as the TypeScript SDK does.
     */
    override suspend fun connect() {
        val session = networkFailuresAsExceptions {
            val wsUrl = buildWsUrl(if (browserWebSocket && authToken != null) websocketToken(authToken) else null)
            client.webSocketSession(wsUrl) {
                // Servers that predate v3 select v2.
                if (browserWebSocket) {
                    headers.append("Sec-WebSocket-Protocol", WS_PROTOCOL_V3)
                    headers.append("Sec-WebSocket-Protocol", WS_PROTOCOL)
                } else {
                    header("Sec-WebSocket-Protocol", "$WS_PROTOCOL_V3, $WS_PROTOCOL")
                }
                if (authToken != null) {
                    header("Authorization", "Bearer $authToken")
                }
            }
        }
        // Incoming frames decode the same way under both protocols, so only outgoing
        // coalescing depends on this. If the engine hides the negotiated protocol, sending
        // one message per frame is valid under v3 as well.
        val negotiated = session.call.response.headers["Sec-WebSocket-Protocol"]
        Logger.debug { "Negotiated websocket protocol ${negotiated ?: "(not reported)"}" }
        _coalesceOutgoing.value = negotiated == WS_PROTOCOL_V3
        _session.value = session
    }

    /**
     * Sends a [ClientMessage] over the WebSocket as a BSATN-encoded binary frame.
     */
    override suspend fun send(message: ClientMessage) {
        val writer = BsatnWriter()
        message.encode(writer)
        val encoded = writer.toByteArray()
        val ws = _session.value ?: error("Not connected")
        networkFailuresAsExceptions { ws.send(Frame.Binary(true, encoded)) }
    }

    override suspend fun sendAll(messages: List<ClientMessage>) {
        if (!_coalesceOutgoing.value || messages.size < 2) {
            for (message in messages) send(message)
            return
        }
        val ws = _session.value ?: error("Not connected")
        for (frame in encodeClientFrames(messages, MAX_OUTGOING_FRAME_BYTES)) {
            networkFailuresAsExceptions { ws.send(Frame.Binary(true, frame)) }
        }
    }

    /**
     * Returns a Flow of ServerMessages received from the WebSocket.
     * Handles decompression (prefix byte) then BSATN decoding.
     */
    override fun incoming(): Flow<ServerMessage> = flow {
        val ws = _session.value ?: error("Not connected")
        // On clean close, the for-loop exits normally (hasNext() returns false).
        // On abnormal close, hasNext() throws the original cause (e.g. IOException),
        // which propagates to DbConnection's error handling path.
        networkFailuresAsExceptions {
            for (frame in ws.incoming) {
                if (frame is Frame.Binary) {
                    for (message in decodeServerFrame(frame.readBytes())) emit(message)
                }
            }
        }
        if (ws.closeReason.await()?.code == SESSION_BUSY_CLOSE_CODE) throw SessionBusyException()
    }

    /** Closes the WebSocket session, if one is open. */
    override suspend fun disconnect() {
        val ws = _session.getAndSet(null)
        ws?.close()
    }

    /**
     * Ktor's browser engine reports network failures as [Error] (e.g. "Fail to fetch"), which isn't an
     * [Exception], so connection retries would never see them. In the browser they're rethrown as one.
     */
    private inline fun <T> networkFailuresAsExceptions(block: () -> T): T {
        if (!browserWebSocket) return block()
        try {
            return block()
        } catch (failure: Error) {
            throw BrowserNetworkException(failure)
        }
    }

    /** Trades [token] for a short-lived one the server accepts as the `token` URL parameter. */
    private suspend fun websocketToken(token: String): String {
        val base = Url(baseUrl)
        val url = URLBuilder(base).apply {
            protocol = if (base.protocol == URLProtocol.WSS || base.protocol == URLProtocol.HTTPS) URLProtocol.HTTPS else URLProtocol.HTTP
            appendPathSegments("v1", "identity", "websocket-token")
        }.build()
        val response = client.post(url) { header(HttpHeaders.Authorization, "Bearer $token") }
        val body = response.bodyAsText()
        check(response.status.isSuccess()) { "Exchanging the token for a WebSocket token failed: ${response.status}" }
        return WEBSOCKET_TOKEN.find(body)?.groupValues?.get(1) ?: error("No token in the websocket-token response")
    }

    private fun buildWsUrl(websocketToken: String?): String {
        val base = Url(baseUrl)
        return URLBuilder(base).apply {
            protocol = when (base.protocol) {
                URLProtocol.HTTPS -> URLProtocol.WSS
                URLProtocol.HTTP -> URLProtocol.WS
                URLProtocol.WSS -> URLProtocol.WSS
                URLProtocol.WS -> URLProtocol.WS
                else -> throw IllegalArgumentException(
                    "Unsupported protocol '${base.protocol.name}'. Use http://, https://, ws://, or wss://"
                )
            }
            appendPathSegments("v1", "database", nameOrAddress, "subscribe")
            parameters.append("connection_id", connectionId.toHexString())
            parameters.append("compression", compression.wireValue)
            if (lightMode) {
                parameters.append("light", "true")
            }
            if (confirmedReads != null) {
                parameters.append("confirmed", confirmedReads.toString())
            }
            if (sessionId != null) {
                parameters.append("session_id", sessionId.toHexString())
            }
            if (websocketToken != null) {
                parameters.append("token", websocketToken)
            }
        }.buildString()
    }
}

/** A network failure in the browser; see `networkFailuresAsExceptions`. */
internal class BrowserNetworkException(cause: Throwable) : Exception(cause.message ?: "Network failure", cause)

package com.clockworklabs.spacetimedb_kotlin_sdk.shared_client

import com.clockworklabs.spacetimedb_kotlin_sdk.shared_client.bsatn.BsatnWriter
import com.clockworklabs.spacetimedb_kotlin_sdk.shared_client.protocol.ClientMessage
import com.clockworklabs.spacetimedb_kotlin_sdk.shared_client.protocol.ServerMessage
import com.clockworklabs.spacetimedb_kotlin_sdk.shared_client.transport.decodeServerFrame
import com.clockworklabs.spacetimedb_kotlin_sdk.shared_client.transport.encodeClientFrames
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs

class WebsocketFramesTest {

    /** An uncompressed frame: the compression tag, then the given message bodies back to back. */
    private fun frame(vararg messages: ByteArray): ByteArray {
        val writer = BsatnWriter()
        writer.writeU8(0u)
        for (message in messages) writer.writeRawBytes(message)
        return writer.toByteArray()
    }

    private fun initialConnection(token: String): ByteArray {
        val writer = BsatnWriter()
        writer.writeSumTag(0u)
        writer.writeU256(BigInteger.ONE)
        writer.writeU128(BigInteger.ONE)
        writer.writeString(token)
        return writer.toByteArray()
    }

    private fun subscribeApplied(requestId: UInt): ByteArray {
        val writer = BsatnWriter()
        writer.writeSumTag(1u)
        writer.writeU32(requestId)
        writer.writeU32(7u)
        writer.writeArrayLen(0)
        return writer.toByteArray()
    }

    @Test
    fun `single message frame decodes as in v2`() {
        val messages = decodeServerFrame(frame(initialConnection("token")))
        assertEquals("token", assertIs<ServerMessage.InitialConnection>(messages.single()).token)
    }

    @Test
    fun `coalesced v3 frame yields every message in order`() {
        val messages = decodeServerFrame(frame(initialConnection("token"), subscribeApplied(1u), subscribeApplied(2u)))
        assertEquals(3, messages.size)
        assertIs<ServerMessage.InitialConnection>(messages[0])
        assertEquals(1u, assertIs<ServerMessage.SubscribeApplied>(messages[1]).requestId)
        assertEquals(2u, assertIs<ServerMessage.SubscribeApplied>(messages[2]).requestId)
    }

    @Test
    fun `empty frame is rejected`() {
        assertFails { decodeServerFrame(frame()) }
    }

    @Test
    fun `truncated trailing message is rejected rather than dropped`() {
        val partial = subscribeApplied(2u).copyOf(5)
        assertFails { decodeServerFrame(frame(subscribeApplied(1u), partial)) }
    }

    private fun call(requestId: UInt, argBytes: Int) =
        ClientMessage.CallReducer(requestId, 0u, "reducer", ByteArray(argBytes))

    @Test
    fun `client messages are concatenated into one frame`() {
        val messages = listOf(call(1u, 4), call(2u, 4))
        val frames = encodeClientFrames(messages, maxFrameBytes = 1024)
        assertContentEquals(
            ClientMessage.encodeToBytes(messages[0]) + ClientMessage.encodeToBytes(messages[1]),
            frames.single(),
        )
    }

    @Test
    fun `frames split at the size cap and keep order`() {
        val messages = listOf(call(1u, 40), call(2u, 40), call(3u, 40))
        val size = ClientMessage.encodeToBytes(messages[0]).size
        val frames = encodeClientFrames(messages, maxFrameBytes = size * 2)
        assertEquals(2, frames.size)
        assertContentEquals(ClientMessage.encodeToBytes(messages[0]) + ClientMessage.encodeToBytes(messages[1]), frames[0])
        assertContentEquals(ClientMessage.encodeToBytes(messages[2]), frames[1])
    }

    @Test
    fun `a message larger than the cap still gets its own frame`() {
        val messages = listOf(call(1u, 4), call(2u, 400), call(3u, 4))
        val frames = encodeClientFrames(messages, maxFrameBytes = 64)
        assertEquals(3, frames.size)
        assertContentEquals(ClientMessage.encodeToBytes(messages[1]), frames[1])
    }
}

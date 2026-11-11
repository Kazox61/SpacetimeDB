package com.clockworklabs.spacetimedb_kotlin_sdk.shared_client.transport

import com.clockworklabs.spacetimedb_kotlin_sdk.shared_client.bsatn.BsatnReader
import com.clockworklabs.spacetimedb_kotlin_sdk.shared_client.bsatn.BsatnWriter
import com.clockworklabs.spacetimedb_kotlin_sdk.shared_client.protocol.ClientMessage
import com.clockworklabs.spacetimedb_kotlin_sdk.shared_client.protocol.ServerMessage
import com.clockworklabs.spacetimedb_kotlin_sdk.shared_client.protocol.decompressMessage

/**
 * Decodes one binary server frame: a compression tag, then one or more BSATN server messages.
 *
 * Protocol v2 sends exactly one message per frame and v3 may coalesce several, so reading
 * until the frame is exhausted handles both without knowing which one was negotiated.
 */
internal fun decodeServerFrame(raw: ByteArray): List<ServerMessage> {
    val payload = decompressMessage(raw)
    val reader = BsatnReader(payload.data, offset = payload.offset)
    check(reader.remaining > 0) { "Server frame contains no messages" }
    val messages = mutableListOf<ServerMessage>()
    while (reader.remaining > 0) messages.add(ServerMessage.decode(reader))
    return messages
}

/**
 * Encodes client messages as v3 frames: each frame concatenates as many messages, in order,
 * as fit in [maxFrameBytes]. A single message larger than the cap gets a frame of its own.
 */
internal fun encodeClientFrames(messages: List<ClientMessage>, maxFrameBytes: Int): List<ByteArray> {
    val frames = mutableListOf<ByteArray>()
    var frame = BsatnWriter()
    var frameBytes = 0
    for (message in messages) {
        val encoded = ClientMessage.encodeToBytes(message)
        if (frameBytes > 0 && frameBytes + encoded.size > maxFrameBytes) {
            frames.add(frame.toByteArray())
            frame = BsatnWriter()
            frameBytes = 0
        }
        frame.writeRawBytes(encoded)
        frameBytes += encoded.size
    }
    if (frameBytes > 0) frames.add(frame.toByteArray())
    return frames
}

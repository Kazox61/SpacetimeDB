package com.clockworklabs.spacetimedb_kotlin_sdk.shared_client.transport

/**
 * True in the browser, whose WebSocket can't carry request headers: subprotocols go as separate
 * values (Ktor passes each one to the WebSocket constructor).
 */
internal expect val browserWebSocket: Boolean

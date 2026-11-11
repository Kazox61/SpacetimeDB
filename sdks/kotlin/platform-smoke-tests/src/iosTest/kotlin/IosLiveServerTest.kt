package com.clockworklabs.spacetimedb.smoketest

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

class IosLiveServerTest {
    @Test
    fun foregroundResumeAndReconnectPreserveIdentity() = runBlocking {
        lifecycleSmoke(HttpClient(Darwin) { install(WebSockets) }, "ws://127.0.0.1:57875")
    }

    @Test
    fun connectionSubscriptionAndReducerRoundtrip() = runBlocking {
        liveServerSmoke(HttpClient(Darwin) { install(WebSockets) }, "ws://127.0.0.1:57875")
    }
}

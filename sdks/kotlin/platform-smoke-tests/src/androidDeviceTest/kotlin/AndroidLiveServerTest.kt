package com.clockworklabs.spacetimedb.smoketest

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

class AndroidLiveServerTest {
    @Test
    fun foregroundResumeAndReconnectPreserveIdentity() = runBlocking {
        lifecycleSmoke(HttpClient(OkHttp) { install(WebSockets) }, "ws://10.0.2.2:57875")
    }

    @Test
    fun connectionSubscriptionAndReducerRoundtrip() = runBlocking {
        // Android Emulator's host-loopback alias, not the emulator's own loopback.
        liveServerSmoke(HttpClient(OkHttp) { install(WebSockets) }, "ws://10.0.2.2:57875")
    }
}

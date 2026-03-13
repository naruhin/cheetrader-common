package com.cheetrader.common.testutil

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

open class MockExchangeServer {
    val server = MockWebServer()

    fun start() {
        server.start()
    }

    fun stop() {
        server.shutdown()
    }

    fun baseUrl(): String = server.url("/").toString().removeSuffix("/")

    fun enqueue(body: String, code: Int = 200) {
        server.enqueue(
            MockResponse()
                .setResponseCode(code)
                .setBody(body)
                .addHeader("Content-Type", "application/json")
        )
    }

    fun enqueueMultiple(vararg bodies: String) {
        bodies.forEach { enqueue(it) }
    }

    fun takeRequest(): RecordedRequest = server.takeRequest()

    fun requestCount(): Int = server.requestCount
}

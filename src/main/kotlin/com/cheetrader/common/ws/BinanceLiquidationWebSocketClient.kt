package com.cheetrader.common.ws

import com.cheetrader.common.logging.ExchangeLogger
import com.cheetrader.common.logging.NoOpLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Clock
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Consumes Binance USDⓈ-M Futures all-market forced-liquidation stream `!forceOrder@arr`
 * over the JDK [java.net.http.WebSocket] and delivers deduped [LiquidationEvent]s.
 *
 * Single low-volume global stream (no sharding). Reconnect with [ExponentialBackoff].
 * Dedup by `(symbol, tradeTime, price, qty)` — bounded. See skill `add-websocket-consumer`.
 *
 * SHADOW-only in current usage: events feed analytics/logging, never orders/risk.
 */
class BinanceLiquidationWebSocketClient(
    baseWsUrl: String = "wss://fstream.binance.com",
    private val listener: LiquidationEventListener,
    private val logger: ExchangeLogger = NoOpLogger,
    private val clock: Clock = Clock.systemUTC(),
    private val freshnessThresholdMs: Long = 120_000,
) : ExchangeWebSocketClient {

    private val endpoint = URI.create("$baseWsUrl/ws/$STREAM")
    private val httpClient: HttpClient = HttpClient.newHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    private val running = AtomicBoolean(false)
    private val reconnectPending = AtomicBoolean(false)
    private val lastMessageAt = AtomicLong(0)
    private val backoff = ExponentialBackoff()

    private val reconnectExecutor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "binance-liq-ws-reconnect").apply { isDaemon = true }
    }

    @Volatile
    private var ws: WebSocket? = null

    // Bounded, access-order-agnostic dedup set (insertion-order eviction).
    private val seen = object : LinkedHashMap<String, Unit>(DEDUP_CAP * 2, 0.75f, false) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Unit>): Boolean = size > DEDUP_CAP
    }

    override fun start() {
        if (running.compareAndSet(false, true)) {
            logger.info { "binance-liq-ws starting endpoint=$endpoint" }
            connect()
        }
    }

    override fun stop() {
        if (running.compareAndSet(true, false)) {
            try {
                ws?.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown")
            } catch (_: Exception) {
            }
            reconnectExecutor.shutdownNow()
            logger.info { "binance-liq-ws stopped" }
        }
    }

    override fun isHealthy(): Boolean {
        val last = lastMessageAt.get()
        if (last == 0L) return false
        return clock.millis() - last < freshnessThresholdMs
    }

    private fun connect() {
        if (!running.get()) return
        try {
            httpClient.newWebSocketBuilder()
                .buildAsync(endpoint, WsListener())
                .whenComplete { socket, err ->
                    if (err != null) {
                        logger.warn { "binance-liq-ws connect failed: ${err.message ?: err.javaClass.simpleName}" }
                        scheduleReconnect()
                    } else {
                        ws = socket
                    }
                }
        } catch (e: Exception) {
            logger.warn { "binance-liq-ws connect exception: ${e.message ?: e.javaClass.simpleName}" }
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (!running.get()) return
        if (!reconnectPending.compareAndSet(false, true)) return
        val delay = synchronized(this) { backoff.next() }
        logger.info { "binance-liq-ws reconnect in ${delay}ms" }
        try {
            reconnectExecutor.schedule({
                reconnectPending.set(false)
                connect()
            }, delay, TimeUnit.MILLISECONDS)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Executor shut down during stop(); nothing to do.
        }
    }

    /** Full inbound pipe (parse + dedup + deliver). Internal for test injection without a socket. */
    internal fun handleMessage(raw: String) {
        lastMessageAt.set(clock.millis())
        val event = parse(raw) ?: return
        val key = "${event.symbol}|${event.tradeTimeMs}|${event.price}|${event.quantity}"
        if (!markSeen(key)) return
        try {
            listener.onLiquidation(event)
        } catch (e: Exception) {
            logger.warn { "binance-liq-ws listener threw: ${e.message ?: e.javaClass.simpleName}" }
        }
    }

    /** Parse one `!forceOrder@arr` message into a [LiquidationEvent], or null if malformed. */
    internal fun parse(raw: String): LiquidationEvent? = try {
        val root = json.parseToJsonElement(raw).jsonObject
        // Combined-stream envelope wraps the event under "data"; raw /ws/ delivers it bare.
        val evt = (root["data"]?.jsonObject ?: root)
        val o = evt["o"]?.jsonObject ?: return null
        val symbol = o["s"]?.jsonPrimitive?.content ?: return null
        val side = o["S"]?.jsonPrimitive?.content ?: return null
        val price = o["ap"]?.jsonPrimitive?.doubleOrNull ?: o["p"]?.jsonPrimitive?.doubleOrNull ?: return null
        val qty = o["z"]?.jsonPrimitive?.doubleOrNull ?: o["q"]?.jsonPrimitive?.doubleOrNull ?: return null
        val tradeTime = o["T"]?.jsonPrimitive?.longOrNull ?: evt["E"]?.jsonPrimitive?.longOrNull ?: clock.millis()
        val eventTime = evt["E"]?.jsonPrimitive?.longOrNull ?: tradeTime
        LiquidationEvent(
            symbol = symbol,
            side = side,
            price = price,
            quantity = qty,
            notionalUsd = price * qty,
            tradeTimeMs = tradeTime,
            eventTimeMs = eventTime,
        )
    } catch (e: Exception) {
        logger.debug { "binance-liq-ws parse skip: ${e.message ?: e.javaClass.simpleName}" }
        null
    }

    private fun markSeen(key: String): Boolean = synchronized(seen) {
        if (seen.containsKey(key)) false else {
            seen[key] = Unit
            true
        }
    }

    private inner class WsListener : WebSocket.Listener {
        private val buffer = StringBuilder()

        override fun onOpen(webSocket: WebSocket) {
            logger.info { "binance-liq-ws connected endpoint=$endpoint" }
            synchronized(this@BinanceLiquidationWebSocketClient) { backoff.reset() }
            webSocket.request(1)
        }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            buffer.append(data)
            if (last) {
                val msg = buffer.toString()
                buffer.setLength(0)
                handleMessage(msg)
            }
            webSocket.request(1)
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            logger.warn { "binance-liq-ws error: ${error.message ?: error.javaClass.simpleName}" }
            scheduleReconnect()
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            logger.warn { "binance-liq-ws closed code=$statusCode reason=$reason" }
            scheduleReconnect()
            return null
        }
    }

    private companion object {
        const val STREAM = "!forceOrder@arr"
        const val DEDUP_CAP = 4_000
    }
}

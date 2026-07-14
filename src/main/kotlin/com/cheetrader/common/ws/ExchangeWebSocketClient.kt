package com.cheetrader.common.ws

/**
 * Common contract for a self-managing exchange WebSocket consumer.
 *
 * Invariants (see skill `add-websocket-consumer`):
 * - lives in `cheetrader-common`, one client per exchange behind this interface;
 * - mandatory reconnect with exponential back-off + jitter;
 * - dedup by domain identity (network can replay);
 * - [isHealthy] reflects staleness (time since last message).
 */
interface ExchangeWebSocketClient {
    /** Open the connection and start delivering events. Idempotent. */
    fun start()

    /** Graceful close; stops reconnection. Idempotent. */
    fun stop()

    /**
     * True while messages arrive within the freshness window.
     *
     * NOTE: for sparse streams (e.g. liquidations in calm markets) this can legitimately
     * be false without indicating a fault. Treat as a hint, not a hard alarm.
     */
    fun isHealthy(): Boolean
}

/** Listener for forced-liquidation events. */
fun interface LiquidationEventListener {
    fun onLiquidation(event: LiquidationEvent)
}

package com.cheetrader.common.testutil

import com.cheetrader.common.model.*

object TestSignals {
    fun longBtc(
        tp: Double? = 70000.0,
        sl: Double? = 58000.0,
        entry: Double = 65000.0,
        metadata: Map<String, String> = emptyMap()
    ) = Signal(
        id = "test-long-btc",
        symbol = "BTCUSDT",
        type = SignalType.LONG,
        entryPrice = entry,
        takeProfit = tp,
        stopLoss = sl,
        timeframe = Timeframe.H1,
        orderType = OrderType.MARKET,
        strategyName = "TestStrategy",
        timestamp = System.currentTimeMillis(),
        eventType = SignalEventType.OPENED,
        metadata = metadata
    )

    fun shortEth(
        tp: Double? = 3000.0,
        sl: Double? = 3800.0,
        entry: Double = 3500.0,
        metadata: Map<String, String> = emptyMap()
    ) = Signal(
        id = "test-short-eth",
        symbol = "ETHUSDT",
        type = SignalType.SHORT,
        entryPrice = entry,
        takeProfit = tp,
        stopLoss = sl,
        timeframe = Timeframe.H1,
        orderType = OrderType.MARKET,
        strategyName = "TestStrategy",
        timestamp = System.currentTimeMillis(),
        eventType = SignalEventType.OPENED,
        metadata = metadata
    )

    fun withTrailingStop(
        deviation: Double = 0.01,
        triggerPrice: Double? = null
    ): Map<String, String> {
        val meta = mutableMapOf(
            "trailingActive" to "true",
            "trailingDeviation" to deviation.toString()
        )
        triggerPrice?.let { meta["triggerPrice"] = it.toString() }
        return meta
    }
}

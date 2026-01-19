package com.cheetrader.common.events

import java.time.Instant

data class SignalDto(
    val signalId: String,
    val symbol: String,
    val type: SignalType,
    val entryPrice: Double,
    val takeProfit: Double? = null,
    val stopLoss: Double? = null,
    val timeframe: Timeframe,
    val orderType: OrderType = OrderType.MARKET,
    val strategyName: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    val createdAt: Instant = Instant.now()
)

enum class SignalType {
    LONG,
    SHORT
}

enum class OrderType {
    MARKET,
    LIMIT
}

enum class Timeframe {
    m1, m5, m15, m30,
    h1, h4,
    d1, w1;

    companion object {
        fun fromString(value: String): Timeframe {
            return when (value.lowercase()) {
                "1m", "m1", "1min" -> m1
                "5m", "m5", "5min" -> m5
                "15m", "m15", "15min" -> m15
                "30m", "m30", "30min" -> m30
                "1h", "h1", "1hour" -> h1
                "4h", "h4", "4hour" -> h4
                "1d", "d1", "1day" -> d1
                "1w", "w1", "1week" -> w1
                else -> throw IllegalArgumentException("Unknown timeframe: $value")
            }
        }
    }
}

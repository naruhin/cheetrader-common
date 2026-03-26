package com.cheetrader.common.exchange

import com.cheetrader.common.model.OrderExecution
import com.cheetrader.common.model.Signal
import com.cheetrader.common.model.formatPricePlain

/**
 * Interface for exchange operations
 */
interface ExchangeService {

    /**
     * Exchange name
     */
    val name: String

    /**
     * Test connection and validate API keys
     */
    suspend fun testConnection(): Result<Boolean>

    /**
     * Get account balance (USDT)
     */
    suspend fun getBalance(): Result<Double>

    /**
     * Execute trading signal
     */
    suspend fun executeSignal(signal: Signal, balancePercent: Double): Result<OrderExecution>

    /**
     * Get number of open positions
     */
    suspend fun getOpenPositionsCount(): Result<Int>

    /**
     * Close position for a symbol
     */
    suspend fun closePosition(symbol: String): Result<OrderExecution>

    /**
     * Close all open positions
     */
    suspend fun closeAllPositions(): Result<Unit>

    /**
     * Cancel all orders for a symbol
     */
    suspend fun cancelAllOrders(symbol: String): Result<Unit>

    /**
     * Get current market price for a symbol
     */
    suspend fun getMarketPrice(symbol: String): Result<Double>

    /**
     * Get open positions with P&L data from the exchange.
     * Returns detailed position info including unrealized P&L, leverage, and margin.
     */
    suspend fun getOpenPositions(): Result<List<ExchangePosition>>

    /**
     * Get recently closed P&L records from the exchange.
     * Used to sync dashboard when client was offline while trades closed.
     * Default implementation returns empty list.
     */
    suspend fun getClosedPnl(limit: Int = 50): Result<List<ClosedPnlRecord>> =
        Result.success(emptyList())
}

/**
 * Exchange position with P&L data as reported by the exchange
 */
data class ExchangePosition(
    val symbol: String,
    val side: String,               // "LONG" or "SHORT"
    val size: Double,               // position size (contracts/coins)
    val entryPrice: Double,
    val markPrice: Double,
    val unrealizedPnl: Double,      // in USDT
    val leverage: Int,
    val margin: Double,             // initial margin (USDT)
    val liquidationPrice: Double?
)

/**
 * Closed P&L record from the exchange (realized profit/loss)
 */
data class ClosedPnlRecord(
    val symbol: String,
    val side: String,               // "LONG" or "SHORT"
    val entryPrice: Double,
    val exitPrice: Double,
    val quantity: Double,
    val closedPnl: Double,          // realized P&L in USDT
    val closedAt: Long              // timestamp millis
)

/**
 * Exchange order result data
 */
data class ExchangeOrderResult(
    val orderId: String,
    val symbol: String,
    val side: String,
    val quantity: Double,
    val price: Double?,
    val status: String,
    val message: String? = null
)

/**
 * Trailing stop parameters extracted from signal metadata
 */
data class TrailingStopParams(
    val deviation: Double,      // e.g. 0.01 = 1%
    val triggerPrice: Double?   // activation price (null = activate immediately)
)

/**
 * Extract trailing stop parameters from signal metadata
 */
fun extractTrailingParams(metadata: Map<String, String>): TrailingStopParams? {
    // Note: do NOT check "trailingActive" here — it's a server-side runtime state
    // (always "false" at signal creation). The exchange uses activationPrice/triggerPrice
    // to handle activation natively.
    val deviation = metadata["trailingDeviation"]?.toDoubleOrNull() ?: return null
    if (deviation <= 0) return null
    val trigger = metadata["triggerPrice"]?.toDoubleOrNull()
    return TrailingStopParams(deviation, trigger)
}

/**
 * Multi-stage take-profit parameters extracted from signal metadata.
 * When present, the position should be closed in parts at each TP level.
 */
data class MultiTpParams(
    val tp1: Double,       // TP1 price (from signal.takeProfit)
    val tp2: Double,       // TP2 price
    val tp3: Double?,      // TP3 price (optional)
    val tp1Pct: Double,    // % of position closed at TP1 (e.g. 40.0)
    val tp2Pct: Double,    // % of position closed at TP2 (e.g. 35.0)
    // remaining (100 - tp1Pct - tp2Pct)% closed at TP3
)

/**
 * Extract multi-TP parameters from signal metadata.
 * Returns null if tp2 is not present (single-TP mode).
 */
fun extractMultiTpParams(metadata: Map<String, String>, originalTp: Double?): MultiTpParams? {
    val tp1 = originalTp ?: return null
    val tp2 = metadata["tp2"]?.toDoubleOrNull() ?: return null
    val tp3 = metadata["tp3"]?.toDoubleOrNull()
    val tp1Pct = metadata["tp1Pct"]?.toDoubleOrNull() ?: 40.0
    val tp2Pct = metadata["tp2Pct"]?.toDoubleOrNull() ?: 35.0
    return MultiTpParams(tp1, tp2, tp3, tp1Pct, tp2Pct)
}

/**
 * Format price without scientific notation
 */
fun formatPrice(price: Double): String = formatPricePlain(price)

/**
 * Retry a conditional order (SL/TP/trailing) up to [maxRetries] times with [delayMs] between attempts.
 * Used after the main order succeeds — these orders are critical for risk management.
 */
suspend fun retryConditionalOrder(
    maxRetries: Int = 3,
    delayMs: Long = 1000,
    action: suspend () -> Result<String>
): Result<String> {
    var lastResult: Result<String> = Result.failure(Exception("No attempts made"))
    repeat(maxRetries) { attempt ->
        lastResult = action()
        if (lastResult.isSuccess) return lastResult
        if (attempt < maxRetries - 1) {
            kotlinx.coroutines.delay(delayMs)
        }
    }
    return lastResult
}

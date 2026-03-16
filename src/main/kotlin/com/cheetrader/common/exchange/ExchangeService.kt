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
}

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
    val active = metadata["trailingActive"]?.toBooleanStrictOrNull() ?: false
    if (!active) return null
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

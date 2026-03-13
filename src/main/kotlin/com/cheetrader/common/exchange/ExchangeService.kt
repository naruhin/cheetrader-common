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
 * Format price without scientific notation
 */
fun formatPrice(price: Double): String = formatPricePlain(price)

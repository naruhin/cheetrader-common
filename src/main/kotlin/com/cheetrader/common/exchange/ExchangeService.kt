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

    /**
     * Fetch recent fills (executions) for [symbol] since [sinceMs] inclusive.
     *
     * Package D — client-authoritative close attribution. After a position
     * disappears, the client calls this to find which conditional order
     * (SL / TP / trailing) actually filled; [ExchangeFill.orderId] is compared
     * against the slOrderId / tpOrderIds / trailingOrderId captured at
     * placement time. Matching lets the UI label the close with the real
     * reason instead of blindly trusting the server-sent event (which is
     * computed against Binance kline close and can be wrong for users on
     * BingX/Bybit/OKX).
     *
     * Default implementation returns an empty list; clients then fall back to
     * the server-sent hint.
     *
     * @param symbol Canonical symbol (adapter normalises per exchange).
     * @param sinceMs Unix millis lower bound (inclusive). Typically
     *        `trade.openedAt - 60_000` for safety overlap.
     * @param limit Max records; each exchange clamps internally.
     */
    suspend fun getRecentFills(
        symbol: String,
        sinceMs: Long,
        limit: Int = 50
    ): Result<List<ExchangeFill>> = Result.success(emptyList())

    /**
     * Fetch the exchange's authoritative realized PnL for the most recently
     * closed position of [symbol]. Mirrors what the exchange UI shows in its
     * positions-history view — the final, fee-and-funding-inclusive number.
     *
     * Use this in preference to recomputing PnL from fills when reconciling
     * a just-closed position. Per-fill `realizedPnl` (e.g. OKX `fillPnl`,
     * Binance `realizedPnl`) is GROSS on most exchanges — summing it without
     * subtracting fees produces an inflated number that diverges from the UI.
     *
     * Default returns null. Adapters override where the endpoint exists:
     *   - OKX `/api/v5/account/positions-history`  ✓
     *   - Binance — TODO (income endpoint or position adjustment delta)
     *   - BingX — TODO
     *   - Bybit — TODO (closed-pnl endpoint)
     *
     * @param symbol canonical symbol (adapter normalises per exchange)
     * @param withinMs only return records closed within this window from now
     */
    suspend fun getMostRecentClosedPositionPnl(
        symbol: String,
        withinMs: Long = 300_000L
    ): Result<AuthoritativeClosedPnl?> = Result.success(null)
}

/**
 * A single trade execution (fill) as reported by the exchange.
 *
 * This is a normalised view across BingX / Bybit / OKX / Binance — each
 * exchange names and structures its fill records differently. Fields the
 * client cares about: [orderId] for matching against stored conditional
 * order IDs, [side] + [quantity] for sanity-checking that this fill
 * actually reduced the position.
 *
 * @property orderId Exchange order id that triggered this fill. **Empty
 *   string means the exchange didn't surface one** — treat as no-match.
 * @property side "BUY" or "SELL" (normalised upper-case).
 * @property isReduceOnly `true` if this fill is known to have reduced a
 *   position, `false` if it opened/added, `null` if the exchange doesn't
 *   expose the flag on its fills endpoint (BingX). Clients using the value
 *   for filtering should treat `null` as "unknown — don't exclude".
 */
data class ExchangeFill(
    val orderId: String,
    val side: String,
    val price: Double,
    val quantity: Double,
    val time: Long,
    val realizedPnl: Double? = null,
    val isReduceOnly: Boolean? = null
)

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
 * Authoritative realized PnL for a recently-closed position, sourced from
 * the exchange's own position-history record. The reported number is what
 * the exchange itself recorded — fees, funding, and any other adjustments
 * already folded in — so it matches the value shown in the exchange UI.
 *
 * Returned by [ExchangeService.getMostRecentClosedPositionPnl]. Prefer this
 * over recomputing from per-fill PnL when reconciling a manual or auto
 * close, since per-fill PnL is gross on most exchanges and produces an
 * inflated total.
 *
 * @property realizedPnl Net realized PnL in USDT — exchange's final figure.
 * @property pnlRatio PnL as ratio (e.g. 0.0674 = 6.74%). Null if not exposed.
 * @property grossPnl Gross PnL before fees, when separately reported.
 * @property fees Total fees signed (negative for outflow). Null if unknown.
 * @property closedAt Unix millis when the position record closed.
 */
data class AuthoritativeClosedPnl(
    val symbol: String,
    val realizedPnl: Double,
    val pnlRatio: Double? = null,
    val grossPnl: Double? = null,
    val fees: Double? = null,
    val closedAt: Long
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

package com.cheetrader.common.ws

/**
 * A single forced-liquidation event from an exchange liquidation stream
 * (Binance `!forceOrder@arr`).
 *
 * IMPORTANT semantics: [side] is the side of the *liquidation order* the exchange
 * places to close the underperforming position. A `SELL` liquidation order closes a
 * LONG position; a `BUY` liquidation order closes a SHORT. Use [liquidatedSide] when
 * reasoning about which crowd got wiped out.
 */
data class LiquidationEvent(
    /** Exchange symbol, e.g. `BTCUSDT` (no dash). */
    val symbol: String,
    /** Side of the liquidation ORDER: `BUY` or `SELL`. */
    val side: String,
    /** Fill/average price of the liquidation. */
    val price: Double,
    /** Filled accumulated quantity (base asset). */
    val quantity: Double,
    /** Notional in quote currency (USD-ish): [price] * [quantity]. */
    val notionalUsd: Double,
    /** Order trade time (ms epoch), field `T`. */
    val tradeTimeMs: Long,
    /** Event push time (ms epoch), field `E`. */
    val eventTimeMs: Long,
) {
    /** Side of the *liquidated position*: SELL order => a LONG was liquidated, BUY => a SHORT. */
    val liquidatedSide: String
        get() = if (side.equals("SELL", ignoreCase = true)) "LONG" else "SHORT"
}

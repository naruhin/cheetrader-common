package com.cheetrader.common.exchange.binance

import java.math.BigDecimal

object BinanceConstants {
    const val ORDER_TYPE_MARKET = "MARKET"
    const val ORDER_TYPE_STOP_MARKET = "STOP_MARKET"
    const val ORDER_TYPE_TAKE_PROFIT_MARKET = "TAKE_PROFIT_MARKET"
    const val ORDER_TYPE_TRAILING_STOP_MARKET = "TRAILING_STOP_MARKET"

    const val SIDE_BUY = "BUY"
    const val SIDE_SELL = "SELL"

    const val POSITION_SIDE_LONG = "LONG"
    const val POSITION_SIDE_SHORT = "SHORT"
    const val POSITION_SIDE_BOTH = "BOTH"

    const val MARGIN_TYPE_CROSS = "CROSSED"
    const val MARGIN_TYPE_ISOLATED = "ISOLATED"

    const val WORKING_TYPE_MARK_PRICE = "MARK_PRICE"

    object Endpoints {
        const val SERVER_TIME = "/fapi/v1/time"
        const val GET_BALANCE = "/fapi/v2/balance"
        const val PLACE_ORDER = "/fapi/v1/order"
        const val ALGO_ORDER = "/fapi/v1/algoOrder"
        const val CANCEL_ALL_ORDERS = "/fapi/v1/allOpenOrders"
        const val CANCEL_ALL_ALGO_ORDERS = "/fapi/v1/algo/orders"
        const val POSITION_RISK = "/fapi/v2/positionRisk"
        const val SET_LEVERAGE = "/fapi/v1/leverage"
        const val SET_MARGIN_TYPE = "/fapi/v1/marginType"
        const val POSITION_SIDE_DUAL = "/fapi/v1/positionSide/dual"
        const val TICKER_PRICE = "/fapi/v1/ticker/price"
    }
}

data class BinanceOrderData(
    val orderId: String?,
    val symbol: String?,
    val side: String?,
    val positionSide: String?,
    val quantity: BigDecimal?,
    val averagePrice: BigDecimal?
)

open class BinanceException(message: String) : RuntimeException(message)
class BinanceApiException(val code: Int, message: String) : BinanceException("[$code] $message")

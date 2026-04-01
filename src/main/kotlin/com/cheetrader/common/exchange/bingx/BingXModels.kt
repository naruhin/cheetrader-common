package com.cheetrader.common.exchange.bingx

import java.math.BigDecimal

/**
 * BingX API Constants
 */
object BingXConstants {
    // Order types
    const val ORDER_TYPE_MARKET = "MARKET"
    const val ORDER_TYPE_LIMIT = "LIMIT"
    const val ORDER_TYPE_STOP_MARKET = "STOP_MARKET"
    const val ORDER_TYPE_STOP = "STOP"
    const val ORDER_TYPE_TAKE_PROFIT_MARKET = "TAKE_PROFIT_MARKET"
    const val ORDER_TYPE_TAKE_PROFIT = "TAKE_PROFIT"
    const val ORDER_TYPE_TRAILING_STOP_MARKET = "TRAILING_STOP_MARKET"

    // Sides
    const val SIDE_BUY = "BUY"
    const val SIDE_SELL = "SELL"

    // Position sides
    const val POSITION_SIDE_LONG = "LONG"
    const val POSITION_SIDE_SHORT = "SHORT"

    // Margin types
    const val MARGIN_TYPE_CROSS = "CROSSED"
    const val MARGIN_TYPE_ISOLATED = "ISOLATED"

    // Working types (for TP/SL)
    const val WORKING_TYPE_MARK_PRICE = "MARK_PRICE"
    const val WORKING_TYPE_CONTRACT_PRICE = "CONTRACT_PRICE"

    // API Endpoints
    object Endpoints {
        const val SERVER_TIME = "/openApi/swap/v2/server/time"
        const val TRADE_ORDER = "/openApi/swap/v2/trade/order"
        const val CANCEL_ALL_ORDERS = "/openApi/swap/v2/trade/allOpenOrders"
        const val CLOSE_ALL_POSITIONS = "/openApi/swap/v2/trade/closeAllPositions"
        const val SET_LEVERAGE = "/openApi/swap/v2/trade/leverage"
        const val SET_MARGIN_TYPE = "/openApi/swap/v2/trade/marginType"
        const val GET_POSITIONS = "/openApi/swap/v2/user/positions"
        const val GET_BALANCE = "/openApi/swap/v3/user/balance"
    }

    // Error codes
    object ErrorCodes {
        const val SUCCESS = 0
        const val TIMESTAMP_ERROR = 80001
        const val INSUFFICIENT_BALANCE = 100001
        const val ORDER_NOT_EXIST = 100400
        const val MARGIN_TYPE_NO_CHANGE = 110409
        const val INVALID_ACTIVATION_PRICE = 110416
    }
}

/**
 * Take Profit configuration
 */
data class TakeProfitConfig(
    val stopPrice: BigDecimal,
    val type: String = BingXConstants.ORDER_TYPE_TAKE_PROFIT_MARKET,
    val workingType: String = BingXConstants.WORKING_TYPE_MARK_PRICE,
    val price: BigDecimal? = null
)

/**
 * Stop Loss configuration
 */
data class StopLossConfig(
    val stopPrice: BigDecimal,
    val type: String = BingXConstants.ORDER_TYPE_STOP_MARKET,
    val workingType: String = BingXConstants.WORKING_TYPE_MARK_PRICE,
    val price: BigDecimal? = null
)

/**
 * Order request
 */
data class BingXOrderRequest(
    val symbol: String,
    val side: String,
    val positionSide: String,
    val type: String = BingXConstants.ORDER_TYPE_MARKET,
    val quantity: BigDecimal,
    val price: BigDecimal? = null,
    val takeProfit: TakeProfitConfig? = null,
    val stopLoss: StopLossConfig? = null,
    val clientOrderId: String? = null,
    val reduceOnly: Boolean = false
)

/**
 * BingX API response wrapper
 */
data class BingXResponse<T>(
    val code: Int?,
    val msg: String?,
    val data: T?
)

/**
 * Order data
 */
data class BingXOrderData(
    val orderId: String?,
    val symbol: String?,
    val side: String?,
    val positionSide: String?,
    val type: String?,
    val quantity: String?,
    val price: String?,
    val status: String?
)

/**
 * Balance data
 */
data class BingXBalanceData(
    val balance: String?,
    val equity: String?,
    val availableMargin: String?
)

/**
 * BingX Exceptions
 */
open class BingXException(message: String) : RuntimeException(message)
class BingXApiException(val code: Int, message: String) : BingXException("[$code] $message")

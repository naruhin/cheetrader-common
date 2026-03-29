package com.cheetrader.common.exchange.okx

object OkxConstants {
    const val SIDE_BUY = "buy"
    const val SIDE_SELL = "sell"
    const val ORDER_TYPE_MARKET = "market"

    const val MARGIN_MODE_CROSS = "cross"
    const val MARGIN_MODE_ISOLATED = "isolated"

    const val POS_MODE_LONG_SHORT = "long_short_mode"
    const val POS_SIDE_LONG = "long"
    const val POS_SIDE_SHORT = "short"

    object Endpoints {
        const val ACCOUNT_CONFIG = "/api/v5/account/config"
        const val ACCOUNT_BALANCE = "/api/v5/account/balance"
        const val ACCOUNT_POSITIONS = "/api/v5/account/positions"
        const val MARKET_TICKER = "/api/v5/market/ticker"
        const val PUBLIC_INSTRUMENTS = "/api/v5/public/instruments"
        const val SET_LEVERAGE = "/api/v5/account/set-leverage"
        const val PLACE_ORDER = "/api/v5/trade/order"
        const val CLOSE_POSITION = "/api/v5/trade/close-position"
        const val ORDERS_PENDING = "/api/v5/trade/orders-pending"
        const val CANCEL_BATCH = "/api/v5/trade/cancel-batch-orders"
        const val ORDER_ALGO = "/api/v5/trade/order-algo"
        const val CANCEL_ALGOS = "/api/v5/trade/cancel-algos"
        const val ORDERS_ALGO_PENDING = "/api/v5/trade/orders-algo-pending"
    }
}

open class OkxException(message: String) : RuntimeException(message)
class OkxApiException(val code: Int, message: String) : OkxException("[$code] $message")

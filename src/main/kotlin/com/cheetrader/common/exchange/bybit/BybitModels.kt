package com.cheetrader.common.exchange.bybit

object BybitConstants {
    const val CATEGORY_LINEAR = "linear"

    const val SIDE_BUY = "Buy"
    const val SIDE_SELL = "Sell"

    const val ORDER_TYPE_MARKET = "Market"
    const val TIME_IN_FORCE_IOC = "IOC"

    const val TPSL_MODE_FULL = "Full"
    const val TRIGGER_BY_MARK_PRICE = "MarkPrice"

    object Endpoints {
        const val SERVER_TIME = "/v5/market/time"
        const val WALLET_BALANCE = "/v5/account/wallet-balance"
        const val SET_LEVERAGE = "/v5/position/set-leverage"
        const val ACCOUNT_INFO = "/v5/account/info"
        const val POSITION_LIST = "/v5/position/list"
        const val ORDER_CREATE = "/v5/order/create"
        const val TRADING_STOP = "/v5/position/trading-stop"
        const val CANCEL_ALL_ORDERS = "/v5/order/cancel-all"
    }
}

open class BybitException(message: String) : RuntimeException(message)
class BybitApiException(val code: Int, message: String) : BybitException("[$code] $message")

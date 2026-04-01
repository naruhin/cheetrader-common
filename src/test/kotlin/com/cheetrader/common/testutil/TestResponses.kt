package com.cheetrader.common.testutil

object BinanceResponses {
    fun serverTime(time: Long = System.currentTimeMillis()) =
        """{"serverTime":$time}"""

    fun balance(available: Double = 1000.0) =
        """[{"asset":"USDT","balance":"${available + 100}","availableBalance":"$available"}]"""

    fun emptyBalance() = """[{"asset":"BTC","balance":"0.5","availableBalance":"0.5"}]"""

    fun positionSideDual(dual: Boolean = false) =
        """{"dualSidePosition":$dual}"""

    fun marginTypeSuccess() = """{"code":200,"msg":"success"}"""
    fun marginTypeAlreadySet() = """{"code":-4046,"msg":"No need to change margin type."}"""

    fun leverageSuccess() = """{"leverage":20,"maxNotionalValue":"250000","symbol":"BTCUSDT"}"""

    fun exchangeInfo() = """{"symbols":[{"symbol":"BTCUSDT","filters":[{"filterType":"LOT_SIZE","stepSize":"0.001"}]},{"symbol":"ETHUSDT","filters":[{"filterType":"LOT_SIZE","stepSize":"0.01"}]}]}"""

    fun orderSuccess(orderId: String = "123456", avgPrice: String = "65000.0", qty: String = "0.010") =
        """{"orderId":"$orderId","symbol":"BTCUSDT","side":"BUY","positionSide":"BOTH","avgPrice":"$avgPrice","executedQty":"$qty","status":"FILLED"}"""

    fun algoOrderSuccess(algoId: String = "algo-789") =
        """{"algoId":"$algoId"}"""

    fun tickerPrice(price: Double = 65000.0) =
        """{"symbol":"BTCUSDT","price":"$price"}"""

    fun positionRisk(symbol: String = "BTCUSDT", amt: Double = 0.010, side: String = "BOTH") =
        """[{"symbol":"$symbol","positionAmt":"$amt","positionSide":"$side"}]"""

    fun positionRiskEmpty() = """[]"""

    fun cancelSuccess() = """{"code":200,"msg":"success"}"""

    fun apiError(code: Int = -1000, msg: String = "Unknown error") =
        """{"code":$code,"msg":"$msg"}"""
}

object BybitResponses {
    fun serverTime(timeSecond: Long = System.currentTimeMillis() / 1000) =
        """{"retCode":0,"retMsg":"OK","result":{"timeSecond":"$timeSecond","timeNano":"${timeSecond}000000000"}}"""

    fun balance(available: Double = 1000.0) =
        """{"retCode":0,"retMsg":"OK","result":{"list":[{"coin":[{"coin":"USDT","availableToWithdraw":"$available","walletBalance":"${available + 100}"}]}]}}"""

    fun emptyBalance() =
        """{"retCode":0,"retMsg":"OK","result":{"list":[{"coin":[]}]}}"""

    fun positionList(symbol: String = "BTCUSDT", size: Double = 0.010, side: String = "Buy", idx: Int = 0) =
        """{"retCode":0,"retMsg":"OK","result":{"list":[{"symbol":"$symbol","size":"$size","side":"$side","positionIdx":$idx}]}}"""

    fun positionListEmpty() =
        """{"retCode":0,"retMsg":"OK","result":{"list":[]}}"""

    fun positionMode(mode: Int = 0) =
        """{"retCode":0,"retMsg":"OK","result":{"mode":$mode}}"""

    fun orderSuccess(orderId: String = "bybit-123") =
        """{"retCode":0,"retMsg":"OK","result":{"orderId":"$orderId"}}"""

    fun leverageSuccess() =
        """{"retCode":0,"retMsg":"OK","result":{}}"""

    fun ticker(price: Double = 65000.0) =
        """{"retCode":0,"retMsg":"OK","result":{"list":[{"lastPrice":"$price","markPrice":"$price"}]}}"""

    fun tradingStopSuccess() =
        """{"retCode":0,"retMsg":"OK","result":{}}"""

    fun cancelAllSuccess() =
        """{"retCode":0,"retMsg":"OK","result":{"list":[]}}"""

    fun apiError(code: Int = 10001, msg: String = "Unknown error") =
        """{"retCode":$code,"retMsg":"$msg"}"""
}

object OkxResponses {
    fun accountConfig(posMode: String = "long_short_mode", acctLv: Int = 2) =
        """{"code":"0","msg":"","data":[{"posMode":"$posMode","acctLv":"$acctLv"}]}"""

    fun balance(available: Double = 1000.0) =
        """{"code":"0","msg":"","data":[{"details":[{"ccy":"USDT","availEq":"$available","availBal":"$available","cashBal":"${available + 100}"}],"totalEq":"${available + 200}"}]}"""

    fun emptyBalance() =
        """{"code":"0","msg":"","data":[{"details":[],"totalEq":"0"}]}"""

    fun instrument(instId: String = "BTC-USDT-SWAP", ctVal: String = "0.01", lotSz: String = "1", minSz: String = "1") =
        """{"code":"0","msg":"","data":[{"instId":"$instId","ctVal":"$ctVal","lotSz":"$lotSz","minSz":"$minSz"}]}"""

    fun orderSuccess(ordId: String = "okx-order-123") =
        """{"code":"0","msg":"","data":[{"sCode":"0","sMsg":"","ordId":"$ordId"}]}"""

    fun orderRejected(sCode: String = "51000", sMsg: String = "Order rejected") =
        """{"code":"0","msg":"","data":[{"sCode":"$sCode","sMsg":"$sMsg","ordId":""}]}"""

    fun algoSuccess(algoId: String = "okx-algo-123") =
        """{"code":"0","msg":"","data":[{"sCode":"0","sMsg":"","algoId":"$algoId"}]}"""

    fun positions(instId: String = "BTC-USDT-SWAP", pos: Double = 1.0, posSide: String = "long") =
        """{"code":"0","msg":"","data":[{"instId":"$instId","pos":"$pos","posSide":"$posSide"}]}"""

    fun positionsEmpty() =
        """{"code":"0","msg":"","data":[]}"""

    fun ticker(price: Double = 65000.0) =
        """{"code":"0","msg":"","data":[{"last":"$price","markPx":"$price"}]}"""

    fun pendingOrders(vararg ordIds: String) =
        """{"code":"0","msg":"","data":[${ordIds.joinToString(",") { """{"ordId":"$it"}""" }}]}"""

    fun pendingOrdersEmpty() =
        """{"code":"0","msg":"","data":[]}"""

    fun cancelBatchSuccess() =
        """{"code":"0","msg":"","data":[{"sCode":"0","sMsg":""}]}"""

    fun leverageSuccess() =
        """{"code":"0","msg":"","data":[{"lever":"20"}]}"""

    fun closePositionSuccess() =
        """{"code":"0","msg":"","data":[{"instId":"BTC-USDT-SWAP","posSide":"long"}]}"""

    fun apiError(code: Int = 50000, msg: String = "Unknown error") =
        """{"code":"$code","msg":"$msg","data":[]}"""
}

object BingXResponses {
    fun serverTime(time: Long = System.currentTimeMillis()) =
        """{"serverTime":$time}"""

    fun balance(available: Double = 1000.0) =
        """{"code":0,"msg":"","data":[{"balance":"$available","equity":"${available + 100}","availableMargin":"$available"}]}"""

    fun emptyBalance() =
        """{"code":0,"msg":"","data":[]}"""

    fun orderSuccess(orderId: String = "bingx-123", avgPrice: String = "65000.0", qty: String = "0.010") =
        """{"code":0,"msg":"","data":{"order":{"orderId":"$orderId","symbol":"BTC-USDT","side":"BUY","positionSide":"LONG","type":"MARKET","origQty":"$qty","avgPrice":"$avgPrice","status":"FILLED"}}}"""

    fun positions(symbol: String = "BTC-USDT", amt: Double = 0.010, positionSide: String = "LONG") =
        """{"code":0,"msg":"","data":[{"symbol":"$symbol","positionAmt":"$amt","positionSide":"$positionSide"}]}"""

    fun positionsEmpty() =
        """{"code":0,"msg":"","data":[]}"""

    fun marketPrice(price: Double = 65000.0) =
        """{"code":0,"msg":"","data":{"price":"$price"}}"""

    fun closeSuccess() =
        """{"code":0,"msg":"","data":{}}"""

    fun cancelAllSuccess() =
        """{"code":0,"msg":"","data":{}}"""

    fun marginTypeSuccess() =
        """{"code":0,"msg":"","data":{}}"""

    fun leverageSuccess() =
        """{"code":0,"msg":"","data":{}}"""

    fun trailingStopSuccess(orderId: String = "trailing-123") =
        """{"code":0,"msg":"","data":{"orderId":"$orderId"}}"""

    fun timestampError() =
        """{"code":80001,"msg":"Timestamp for this request is outside of the recvWindow."}"""

    fun apiError(code: Int = -1, msg: String = "Unknown error") =
        """{"code":$code,"msg":"$msg"}"""
}

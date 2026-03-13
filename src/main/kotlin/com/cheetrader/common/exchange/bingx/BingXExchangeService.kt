package com.cheetrader.common.exchange.bingx

import com.cheetrader.common.exchange.ExchangeService
import com.cheetrader.common.exchange.extractTrailingParams
import com.cheetrader.common.exchange.formatPrice
import com.cheetrader.common.logging.ExchangeLogger
import com.cheetrader.common.logging.NoOpLogger
import com.cheetrader.common.model.ExecutionStatus
import com.cheetrader.common.model.OrderExecution
import com.cheetrader.common.model.Signal
import com.cheetrader.common.model.SignalType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * BingX Exchange Service
 *
 * Implementation of trading operations for BingX Perpetual Futures
 */
class BingXExchangeService(
    apiKey: String,
    secretKey: String,
    testnet: Boolean = false,
    private val defaultLeverage: Int = 20,
    private val marginType: String = BingXConstants.MARGIN_TYPE_CROSS,
    private val logger: ExchangeLogger = NoOpLogger,
    baseUrlOverride: String? = null
) : ExchangeService {

    override val name = "BingX"

    private val baseUrl = baseUrlOverride ?: if (testnet) {
        "https://open-api-vst.bingx.com"
    } else {
        "https://open-api.bingx.com"
    }

    private val httpClient = BingXHttpClient(apiKey, secretKey, baseUrl, logger)
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun testConnection(): Result<Boolean> {
        return try {
            httpClient.syncTime()
            val balance = getBalance()
            if (balance.isSuccess) {
                logger.info { "BingX connection OK. Balance: ${balance.getOrNull()}" }
                Result.success(true)
            } else {
                Result.failure(balance.exceptionOrNull() ?: Exception("Connection failed"))
            }
        } catch (e: Exception) {
            logger.error(e) { "BingX connection test failed" }
            Result.failure(e)
        }
    }

    override suspend fun getBalance(): Result<Double> {
        return try {
            httpClient.syncTime()

            val response = httpClient.executeSignedGet(BingXConstants.Endpoints.GET_BALANCE)
                ?: return Result.failure(BingXException("No response from API"))

            val jsonResponse = json.parseToJsonElement(response).jsonObject
            val code = jsonResponse["code"]?.jsonPrimitive?.intOrNull

            if (code == BingXConstants.ErrorCodes.SUCCESS) {
                val data = jsonResponse["data"]?.jsonArray?.firstOrNull()?.jsonObject
                val balance = data?.get("balance")?.jsonPrimitive?.content?.toDoubleOrNull()
                    ?: return Result.failure(BingXException("Cannot parse balance"))

                Result.success(balance)
            } else {
                val msg = jsonResponse["msg"]?.jsonPrimitive?.content ?: "Unknown error"
                Result.failure(BingXApiException(code ?: -1, msg))
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to get balance" }
            Result.failure(e)
        }
    }

    override suspend fun executeSignal(signal: Signal, balancePercent: Double): Result<OrderExecution> {
        return try {
            httpClient.syncTime()

            val balanceResult = getBalance()
            if (balanceResult.isFailure) {
                return Result.failure(balanceResult.exceptionOrNull()!!)
            }
            val balance = balanceResult.getOrThrow()

            val symbol = convertSymbol(signal.symbol)
            val isBuy = signal.type == SignalType.LONG
            val side = if (isBuy) BingXConstants.SIDE_BUY else BingXConstants.SIDE_SELL
            val positionSide = if (isBuy) BingXConstants.POSITION_SIDE_LONG else BingXConstants.POSITION_SIDE_SHORT

            setupPosition(symbol, positionSide)

            val tradeAmount = balance * (balancePercent / 100.0) * defaultLeverage
            val quantity = calculateQuantity(tradeAmount, signal.entryPrice, symbol)

            // Sanitize TP/SL
            val referencePrice = getMarketPrice(symbol).getOrNull() ?: signal.entryPrice
            val (safeTp, safeSl) = sanitizeTpSl(
                isBuy = isBuy,
                referencePrice = referencePrice,
                takeProfit = signal.takeProfit,
                stopLoss = signal.stopLoss
            )

            val order = BingXOrderRequest(
                symbol = symbol,
                side = side,
                positionSide = positionSide,
                type = BingXConstants.ORDER_TYPE_MARKET,
                quantity = quantity,
                takeProfit = safeTp?.let { TakeProfitConfig(BigDecimal.valueOf(it)) },
                stopLoss = safeSl?.let { StopLossConfig(BigDecimal.valueOf(it)) }
            )

            val result = placeOrder(order)

            if (result.isSuccess) {
                val orderData = result.getOrThrow()
                val conditionalErrors = mutableListOf<String>()

                // Trailing stop from metadata
                val trailingParams = extractTrailingParams(signal.metadata)
                if (trailingParams != null) {
                    val trailingResult = placeTrailingStopOrder(
                        symbol = symbol,
                        side = if (isBuy) BingXConstants.SIDE_SELL else BingXConstants.SIDE_BUY,
                        positionSide = positionSide,
                        callbackRate = trailingParams.deviation * 100, // API expects percentage
                        activationPrice = trailingParams.triggerPrice
                    )
                    if (trailingResult.isFailure) {
                        conditionalErrors.add("Trailing: ${trailingResult.exceptionOrNull()?.message}")
                    }
                }

                val status = if (conditionalErrors.isNotEmpty()) {
                    logger.warn { "BingX conditional order errors: $conditionalErrors" }
                    ExecutionStatus.PARTIAL
                } else {
                    ExecutionStatus.SUCCESS
                }

                Result.success(OrderExecution(
                    signalId = signal.id,
                    status = status,
                    exchangeOrderId = orderData.orderId,
                    executedPrice = orderData.price?.toDoubleOrNull(),
                    executedQuantity = orderData.quantity?.toDoubleOrNull(),
                    errorMessage = conditionalErrors.takeIf { it.isNotEmpty() }?.joinToString("; ")
                ))
            } else {
                Result.success(OrderExecution(
                    signalId = signal.id,
                    status = ExecutionStatus.FAILED,
                    errorMessage = result.exceptionOrNull()?.message
                ))
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to execute signal ${signal.id}" }
            Result.success(OrderExecution(
                signalId = signal.id,
                status = ExecutionStatus.FAILED,
                errorMessage = e.message
            ))
        }
    }

    override suspend fun closePosition(symbol: String): Result<OrderExecution> {
        return try {
            httpClient.syncTime()

            val bingxSymbol = convertSymbol(symbol)
            val params = mutableMapOf(
                "symbol" to bingxSymbol
            )

            val response = httpClient.executeSignedPost(BingXConstants.Endpoints.CLOSE_ALL_POSITIONS, params)
                ?: return Result.failure(BingXException("No response from API"))

            val jsonResponse = json.parseToJsonElement(response).jsonObject
            val code = jsonResponse["code"]?.jsonPrimitive?.intOrNull

            if (code == BingXConstants.ErrorCodes.SUCCESS) {
                Result.success(OrderExecution(
                    signalId = "close-$symbol",
                    status = ExecutionStatus.SUCCESS
                ))
            } else {
                val msg = jsonResponse["msg"]?.jsonPrimitive?.content ?: "Unknown error"
                Result.failure(BingXApiException(code ?: -1, msg))
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to close position $symbol" }
            Result.failure(e)
        }
    }

    override suspend fun closeAllPositions(): Result<Unit> {
        return try {
            httpClient.syncTime()

            val positionsResponse = httpClient.executeSignedGet(BingXConstants.Endpoints.GET_POSITIONS)
                ?: return Result.failure(BingXException("No response from API"))

            val jsonResponse = json.parseToJsonElement(positionsResponse).jsonObject
            val code = jsonResponse["code"]?.jsonPrimitive?.intOrNull

            if (code == BingXConstants.ErrorCodes.SUCCESS) {
                val positions = jsonResponse["data"]?.jsonArray ?: JsonArray(emptyList())

                for (position in positions) {
                    val posObj = position.jsonObject
                    val sym = posObj["symbol"]?.jsonPrimitive?.content ?: continue
                    val positionAmt = posObj["positionAmt"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0

                    if (positionAmt != 0.0) {
                        closePosition(sym)
                    }
                }

                Result.success(Unit)
            } else {
                val msg = jsonResponse["msg"]?.jsonPrimitive?.content ?: "Unknown error"
                Result.failure(BingXApiException(code ?: -1, msg))
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to close all positions" }
            Result.failure(e)
        }
    }

    override suspend fun cancelAllOrders(symbol: String): Result<Unit> {
        return try {
            httpClient.syncTime()

            val bingxSymbol = convertSymbol(symbol)
            val params = mutableMapOf(
                "symbol" to bingxSymbol
            )

            val response = httpClient.executeSignedDelete(BingXConstants.Endpoints.CANCEL_ALL_ORDERS, params)
                ?: return Result.failure(BingXException("No response from API"))

            val jsonResponse = json.parseToJsonElement(response).jsonObject
            val code = jsonResponse["code"]?.jsonPrimitive?.intOrNull

            if (code == BingXConstants.ErrorCodes.SUCCESS) {
                Result.success(Unit)
            } else {
                val msg = jsonResponse["msg"]?.jsonPrimitive?.content ?: "Unknown error"
                Result.failure(BingXApiException(code ?: -1, msg))
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to cancel orders for $symbol" }
            Result.failure(e)
        }
    }

    override suspend fun getOpenPositionsCount(): Result<Int> {
        return try {
            httpClient.syncTime()
            val positionsResponse = httpClient.executeSignedGet(BingXConstants.Endpoints.GET_POSITIONS)
                ?: return Result.failure(BingXException("No response from API"))

            val jsonResponse = json.parseToJsonElement(positionsResponse).jsonObject
            val code = jsonResponse["code"]?.jsonPrimitive?.intOrNull

            if (code == BingXConstants.ErrorCodes.SUCCESS) {
                val positions = jsonResponse["data"]?.jsonArray ?: JsonArray(emptyList())
                val count = positions.count { position ->
                    val posObj = position.jsonObject
                    val positionAmt = posObj["positionAmt"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
                    positionAmt != 0.0
                }
                Result.success(count)
            } else {
                val msg = jsonResponse["msg"]?.jsonPrimitive?.content ?: "Unknown error"
                Result.failure(BingXApiException(code ?: -1, msg))
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to get positions" }
            Result.failure(e)
        }
    }

    // ===== Private Methods =====

    private suspend fun placeOrder(order: BingXOrderRequest): Result<BingXOrderData> {
        val maxRetries = 3

        repeat(maxRetries) { attempt ->
            try {
                val params = buildOrderParams(order)
                val response = httpClient.executeSignedPost(BingXConstants.Endpoints.TRADE_ORDER, params)
                    ?: return Result.failure(BingXException("No response from API"))

                logger.debug { "Order response: $response" }

                val jsonResponse = json.parseToJsonElement(response).jsonObject
                val code = jsonResponse["code"]?.jsonPrimitive?.intOrNull

                when (code) {
                    BingXConstants.ErrorCodes.SUCCESS -> {
                        val data = jsonResponse["data"]?.jsonObject
                        val orderData = BingXOrderData(
                            orderId = data?.get("orderId")?.jsonPrimitive?.content,
                            symbol = data?.get("symbol")?.jsonPrimitive?.content,
                            side = data?.get("side")?.jsonPrimitive?.content,
                            positionSide = data?.get("positionSide")?.jsonPrimitive?.content,
                            type = data?.get("type")?.jsonPrimitive?.content,
                            quantity = data?.get("origQty")?.jsonPrimitive?.content,
                            price = data?.get("avgPrice")?.jsonPrimitive?.content,
                            status = data?.get("status")?.jsonPrimitive?.content
                        )
                        logger.info { "Order placed: ${orderData.orderId}" }
                        return Result.success(orderData)
                    }
                    BingXConstants.ErrorCodes.TIMESTAMP_ERROR -> {
                        logger.warn { "Attempt ${attempt + 1}: Timestamp error, syncing time..." }
                        httpClient.syncTime()
                        Thread.sleep(1000)
                    }
                    else -> {
                        val msg = jsonResponse["msg"]?.jsonPrimitive?.content ?: "Unknown error"
                        return Result.failure(BingXApiException(code ?: -1, msg))
                    }
                }
            } catch (e: Exception) {
                logger.error(e) { "Attempt ${attempt + 1}: Exception placing order" }
                if (attempt < maxRetries - 1) Thread.sleep(1000)
            }
        }

        return Result.failure(BingXException("Max retries exceeded"))
    }

    private suspend fun placeTrailingStopOrder(
        symbol: String,
        side: String,
        positionSide: String,
        callbackRate: Double,
        activationPrice: Double?
    ): Result<String> {
        // BingX callbackRate: percentage value (e.g. 1.0 for 1%)
        val clampedRate = callbackRate.coerceIn(0.1, 5.0)

        val params = mutableMapOf(
            "symbol" to symbol,
            "side" to side,
            "positionSide" to positionSide,
            "type" to BingXConstants.ORDER_TYPE_TRAILING_STOP_MARKET,
            "quantity" to "0", // close entire position
            "callbackRate" to formatPrice(clampedRate),
            "workingType" to BingXConstants.WORKING_TYPE_MARK_PRICE
        )

        activationPrice?.let { params["activationPrice"] = formatPrice(it) }

        val response = httpClient.executeSignedPost(BingXConstants.Endpoints.TRADE_ORDER, params)
            ?: return Result.failure(BingXException("No response from API"))

        val jsonResponse = json.parseToJsonElement(response).jsonObject
        val code = jsonResponse["code"]?.jsonPrimitive?.intOrNull

        if (code == BingXConstants.ErrorCodes.SUCCESS) {
            val orderId = jsonResponse["data"]?.jsonObject?.get("orderId")?.jsonPrimitive?.content ?: ""
            logger.info { "BingX trailing stop placed: orderId=$orderId callbackRate=$clampedRate%" }
            return Result.success(orderId)
        } else {
            val msg = jsonResponse["msg"]?.jsonPrimitive?.content ?: "Unknown error"
            logger.error { "BingX trailing stop failed: [$code] $msg" }
            return Result.failure(BingXApiException(code ?: -1, msg))
        }
    }

    private fun buildOrderParams(order: BingXOrderRequest): MutableMap<String, String> {
        val params = mutableMapOf(
            "symbol" to order.symbol,
            "side" to order.side,
            "positionSide" to order.positionSide,
            "type" to order.type,
            "quantity" to order.quantity.toPlainString()
        )

        order.price?.let { params["price"] = it.toPlainString() }
        order.clientOrderId?.let { params["clientOrderId"] = it }
        if (order.reduceOnly) params["reduceOnly"] = "true"

        // TP/SL as URL-encoded JSON
        order.takeProfit?.let { tp ->
            val tpJson = buildTpSlJson(tp.type, tp.stopPrice, tp.workingType, tp.price)
            params["takeProfit"] = urlEncode(tpJson)
        }

        order.stopLoss?.let { sl ->
            val slJson = buildTpSlJson(sl.type, sl.stopPrice, sl.workingType, sl.price)
            params["stopLoss"] = urlEncode(slJson)
        }

        return params
    }

    private fun buildTpSlJson(
        type: String,
        stopPrice: BigDecimal,
        workingType: String,
        price: BigDecimal? = null
    ): String {
        val formattedStopPrice = stopPrice.stripTrailingZeros().toPlainString()
        return if (price != null) {
            val formattedPrice = price.stripTrailingZeros().toPlainString()
            """{"type":"$type","stopPrice":$formattedStopPrice,"price":$formattedPrice,"workingType":"$workingType"}"""
        } else {
            """{"type":"$type","stopPrice":$formattedStopPrice,"workingType":"$workingType"}"""
        }
    }

    private suspend fun setupPosition(symbol: String, positionSide: String) {
        try {
            val marginParams = mutableMapOf(
                "symbol" to symbol,
                "marginType" to marginType
            )
            httpClient.executeSignedPost(BingXConstants.Endpoints.SET_MARGIN_TYPE, marginParams)

            val leverageParams = mutableMapOf(
                "symbol" to symbol,
                "side" to positionSide,
                "leverage" to defaultLeverage.toString()
            )
            httpClient.executeSignedPost(BingXConstants.Endpoints.SET_LEVERAGE, leverageParams)

            logger.debug { "Position setup done for $symbol: $marginType, ${defaultLeverage}x" }
        } catch (e: Exception) {
            logger.warn { "Position setup warning: ${e.message}" }
        }
    }

    override suspend fun getMarketPrice(symbol: String): Result<Double> {
        return try {
            val response = httpClient.executeSignedGet(
                "/openApi/swap/v2/quote/price",
                mutableMapOf("symbol" to symbol)
            ) ?: return Result.failure(BingXException("No response from API"))

            val jsonResponse = json.parseToJsonElement(response).jsonObject
            val code = jsonResponse["code"]?.jsonPrimitive?.intOrNull
            if (code == BingXConstants.ErrorCodes.SUCCESS) {
                val price = jsonResponse["data"]?.jsonObject?.get("price")?.jsonPrimitive?.content?.toDoubleOrNull()
                    ?: return Result.failure(BingXException("Cannot parse price"))
                Result.success(price)
            } else {
                val msg = jsonResponse["msg"]?.jsonPrimitive?.content ?: "Unknown error"
                Result.failure(BingXApiException(code ?: -1, msg))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    internal fun sanitizeTpSl(
        isBuy: Boolean,
        referencePrice: Double,
        takeProfit: Double?,
        stopLoss: Double?
    ): Pair<Double?, Double?> {
        if (referencePrice <= 0) {
            return takeProfit to stopLoss
        }

        val safeTp = takeProfit?.takeIf { tp ->
            if (isBuy) tp > referencePrice else tp < referencePrice
        }
        val safeSl = stopLoss?.takeIf { sl ->
            if (isBuy) sl < referencePrice else sl > referencePrice
        }

        if (takeProfit != null && safeTp == null) {
            logger.warn { "BingX TP ignored (invalid vs price $referencePrice): $takeProfit" }
        }
        if (stopLoss != null && safeSl == null) {
            logger.warn { "BingX SL ignored (invalid vs price $referencePrice): $stopLoss" }
        }

        return safeTp to safeSl
    }

    internal fun convertSymbol(symbol: String): String {
        if (symbol.contains("-")) return symbol

        val quote = listOf("USDT", "USDC", "BUSD").find { symbol.endsWith(it) }
        return if (quote != null) {
            val base = symbol.removeSuffix(quote)
            "$base-$quote"
        } else {
            symbol
        }
    }

    internal fun calculateQuantity(amountUsdt: Double, price: Double, symbol: String): BigDecimal {
        val rawQuantity = amountUsdt / price

        val precision = when {
            symbol.startsWith("BTC") -> 3
            symbol.startsWith("ETH") -> 2
            else -> 0
        }

        return BigDecimal.valueOf(rawQuantity)
            .setScale(precision, RoundingMode.DOWN)
    }

    private fun urlEncode(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.toString())
    }
}

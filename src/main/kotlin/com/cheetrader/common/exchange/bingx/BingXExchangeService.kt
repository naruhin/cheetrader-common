package com.cheetrader.common.exchange.bingx

import com.cheetrader.common.exchange.ExchangePosition
import com.cheetrader.common.exchange.ExchangeService
import com.cheetrader.common.exchange.extractMultiTpParams
import com.cheetrader.common.exchange.extractTrailingParams
import com.cheetrader.common.exchange.retryConditionalOrder
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
import kotlinx.coroutines.delay
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

            // Check for multi-stage TP
            val multiTp = extractMultiTpParams(signal.metadata, safeTp)

            val order = if (multiTp != null) {
                // Multi-TP: don't embed TP in main order, place separately after
                BingXOrderRequest(
                    symbol = symbol,
                    side = side,
                    positionSide = positionSide,
                    type = BingXConstants.ORDER_TYPE_MARKET,
                    quantity = quantity,
                    takeProfit = null,
                    stopLoss = safeSl?.let { StopLossConfig(BigDecimal.valueOf(it)) }
                )
            } else {
                // Single TP: embed in main order (existing behavior)
                BingXOrderRequest(
                    symbol = symbol,
                    side = side,
                    positionSide = positionSide,
                    type = BingXConstants.ORDER_TYPE_MARKET,
                    quantity = quantity,
                    takeProfit = safeTp?.let { TakeProfitConfig(BigDecimal.valueOf(it)) },
                    stopLoss = safeSl?.let { StopLossConfig(BigDecimal.valueOf(it)) }
                )
            }

            val result = placeOrder(order)

            if (result.isSuccess) {
                val orderData = result.getOrThrow()
                val conditionalErrors = mutableListOf<String>()
                val closeSide = if (isBuy) BingXConstants.SIDE_SELL else BingXConstants.SIDE_BUY

                // Multi-stage take-profit orders
                if (multiTp != null) {
                    val precision = quantity.scale()
                    val tp1Qty = quantity.multiply(BigDecimal.valueOf(multiTp.tp1Pct / 100.0))
                        .setScale(precision, java.math.RoundingMode.DOWN)
                    val tp2Qty = quantity.multiply(BigDecimal.valueOf(multiTp.tp2Pct / 100.0))
                        .setScale(precision, java.math.RoundingMode.DOWN)
                    val tp3Qty = quantity.subtract(tp1Qty).subtract(tp2Qty)

                    if (tp1Qty > BigDecimal.ZERO && tp2Qty > BigDecimal.ZERO) {
                        val tp1Result = placeTpAlgoOrder(symbol, closeSide, positionSide, multiTp.tp1, tp1Qty)
                        if (tp1Result.isFailure) conditionalErrors.add("TP1: ${tp1Result.exceptionOrNull()?.message}")

                        val tp2Result = placeTpAlgoOrder(symbol, closeSide, positionSide, multiTp.tp2, tp2Qty)
                        if (tp2Result.isFailure) conditionalErrors.add("TP2: ${tp2Result.exceptionOrNull()?.message}")

                        if (multiTp.tp3 != null && tp3Qty > BigDecimal.ZERO) {
                            val tp3Result = placeTpAlgoOrder(symbol, closeSide, positionSide, multiTp.tp3, tp3Qty)
                            if (tp3Result.isFailure) conditionalErrors.add("TP3: ${tp3Result.exceptionOrNull()?.message}")
                        }
                    } else {
                        // Fallback: quantities too small, place single TP
                        val tpResult = placeTpAlgoOrder(symbol, closeSide, positionSide, multiTp.tp1, quantity)
                        if (tpResult.isFailure) conditionalErrors.add("TP: ${tpResult.exceptionOrNull()?.message}")
                    }

                    // TODO: Break-even after TP1/TP2 requires a PositionMonitorService that watches
                    // for TP fill events and modifies the SL order. Metadata keys "breakEvenAfterTp1"
                    // and "breakEvenAfterTp2" are available in signal.metadata for this purpose.

                    // TODO: Early exit based on oppositeScore requires continuous candle-by-candle
                    // evaluation. Metadata keys "oppositeScore"/"earlyExitScoreThreshold" in
                    // signal.metadata are snapshots at signal time.
                }

                // Trailing stop from metadata
                val trailingParams = extractTrailingParams(signal.metadata)
                if (trailingParams != null) {
                    val trailingSide = if (isBuy) BingXConstants.SIDE_SELL else BingXConstants.SIDE_BUY
                    val trailingResult = placeTrailingStopWithRecalc(
                        symbol = symbol,
                        side = trailingSide,
                        positionSide = positionSide,
                        quantity = quantity,
                        priceRate = trailingParams.deviation * 100,
                        activationPrice = trailingParams.triggerPrice,
                        isBuy = isBuy
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
        return getOpenPositions().map { it.size }
    }

    override suspend fun getOpenPositions(): Result<List<ExchangePosition>> {
        return try {
            httpClient.syncTime()
            val positionsResponse = httpClient.executeSignedGet(BingXConstants.Endpoints.GET_POSITIONS)
                ?: return Result.failure(BingXException("No response from API"))

            val jsonResponse = json.parseToJsonElement(positionsResponse).jsonObject
            val code = jsonResponse["code"]?.jsonPrimitive?.intOrNull

            if (code == BingXConstants.ErrorCodes.SUCCESS) {
                val positions = jsonResponse["data"]?.jsonArray ?: JsonArray(emptyList())
                val result = positions.mapNotNull { position ->
                    val posObj = position.jsonObject
                    val positionAmt = posObj["positionAmt"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
                    if (positionAmt == 0.0) return@mapNotNull null

                    val side = posObj["positionSide"]?.jsonPrimitive?.content
                        ?: if (positionAmt > 0) "LONG" else "SHORT"

                    ExchangePosition(
                        symbol = posObj["symbol"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                        side = side,
                        size = kotlin.math.abs(positionAmt),
                        entryPrice = posObj["avgPrice"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                        markPrice = posObj["markPrice"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                        unrealizedPnl = posObj["unrealizedProfit"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                        leverage = posObj["leverage"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1,
                        margin = posObj["initialMargin"]?.jsonPrimitive?.content?.toDoubleOrNull()
                            ?: posObj["margin"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                        liquidationPrice = posObj["liquidationPrice"]?.jsonPrimitive?.content?.toDoubleOrNull()
                    )
                }
                Result.success(result)
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
                        // BingX V2 API nests the order object under data.order; fall back to flat data for compatibility
                        val orderObj = data?.get("order")?.jsonObject ?: data
                        val orderData = BingXOrderData(
                            orderId = orderObj?.get("orderId")?.jsonPrimitive?.content,
                            symbol = orderObj?.get("symbol")?.jsonPrimitive?.content,
                            side = orderObj?.get("side")?.jsonPrimitive?.content,
                            positionSide = orderObj?.get("positionSide")?.jsonPrimitive?.content,
                            type = orderObj?.get("type")?.jsonPrimitive?.content,
                            quantity = orderObj?.get("origQty")?.jsonPrimitive?.content
                                ?: orderObj?.get("executedQty")?.jsonPrimitive?.content,
                            price = orderObj?.get("avgPrice")?.jsonPrimitive?.content,
                            status = orderObj?.get("status")?.jsonPrimitive?.content
                        )
                        logger.info { "Order placed: ${orderData.orderId}" }
                        return Result.success(orderData)
                    }
                    BingXConstants.ErrorCodes.TIMESTAMP_ERROR -> {
                        logger.warn { "Attempt ${attempt + 1}: Timestamp error, syncing time..." }
                        httpClient.syncTime()
                        delay(1000)
                    }
                    else -> {
                        val msg = jsonResponse["msg"]?.jsonPrimitive?.content ?: "Unknown error"
                        return Result.failure(BingXApiException(code ?: -1, msg))
                    }
                }
            } catch (e: Exception) {
                logger.error(e) { "Attempt ${attempt + 1}: Exception placing order" }
                if (attempt < maxRetries - 1) delay(1000)
            }
        }

        return Result.failure(BingXException("Max retries exceeded"))
    }

    private suspend fun placeTpAlgoOrder(
        symbol: String,
        side: String,
        positionSide: String,
        stopPrice: Double,
        quantity: BigDecimal
    ): Result<String> {
        val params = mutableMapOf(
            "symbol" to symbol,
            "side" to side,
            "positionSide" to positionSide,
            "type" to BingXConstants.ORDER_TYPE_TAKE_PROFIT_MARKET,
            "quantity" to quantity.toPlainString(),
            "stopPrice" to formatPrice(stopPrice),
            "workingType" to BingXConstants.WORKING_TYPE_MARK_PRICE
        )

        val response = httpClient.executeSignedPost(BingXConstants.Endpoints.TRADE_ORDER, params)
            ?: return Result.failure(BingXException("No response from API"))

        val jsonResponse = json.parseToJsonElement(response).jsonObject
        val code = jsonResponse["code"]?.jsonPrimitive?.intOrNull

        if (code == BingXConstants.ErrorCodes.SUCCESS) {
            val orderId = jsonResponse["data"]?.jsonObject?.get("orderId")?.jsonPrimitive?.content ?: ""
            logger.info { "BingX TP algo placed: orderId=$orderId stopPrice=$stopPrice qty=$quantity" }
            return Result.success(orderId)
        } else {
            val msg = jsonResponse["msg"]?.jsonPrimitive?.content ?: "Unknown error"
            logger.error { "BingX TP algo failed: [$code] $msg" }
            return Result.failure(BingXApiException(code ?: -1, msg))
        }
    }

    private suspend fun placeTrailingStopOrder(
        symbol: String,
        side: String,
        positionSide: String,
        quantity: BigDecimal,
        priceRate: Double,        // percentage value: 1.0 = 1% (BingX valid range: 0.1–1.0)
        activationPrice: Double?
    ): Result<String> {
        if (priceRate > 1.0) {
            logger.warn { "BingX priceRate $priceRate exceeds max 1.0 (${priceRate}%), clamping to 1.0 (1%)" }
        }
        val clampedRate = priceRate.coerceIn(0.1, 1.0)

        val params = mutableMapOf(
            "symbol" to symbol,
            "side" to side,
            "positionSide" to positionSide,
            "type" to BingXConstants.ORDER_TYPE_TRAILING_STOP_MARKET,
            "quantity" to quantity.toPlainString(),
            "priceRate" to formatPrice(clampedRate),
            "workingType" to BingXConstants.WORKING_TYPE_MARK_PRICE
        )

        activationPrice?.let { params["activationPrice"] = formatPrice(it) }

        val response = httpClient.executeSignedPost(BingXConstants.Endpoints.TRADE_ORDER, params)
            ?: return Result.failure(BingXException("No response from API"))

        val jsonResponse = json.parseToJsonElement(response).jsonObject
        val code = jsonResponse["code"]?.jsonPrimitive?.intOrNull

        if (code == BingXConstants.ErrorCodes.SUCCESS) {
            val data = jsonResponse["data"]?.jsonObject
            val orderObj = data?.get("order")?.jsonObject ?: data
            val orderId = orderObj?.get("orderId")?.jsonPrimitive?.content ?: ""
            logger.info { "BingX trailing stop placed: orderId=$orderId priceRate=${clampedRate * 100}% (raw=$clampedRate)" }
            return Result.success(orderId)
        } else {
            val msg = jsonResponse["msg"]?.jsonPrimitive?.content ?: "Unknown error"
            logger.error { "BingX trailing stop failed: [$code] $msg" }
            return Result.failure(BingXApiException(code ?: -1, msg))
        }
    }

    /**
     * Places trailing stop with smart retry: on 110416 (activation price already breached),
     * recalculates activationPrice from current market price. On second failure — retries without activationPrice.
     */
    private suspend fun placeTrailingStopWithRecalc(
        symbol: String,
        side: String,
        positionSide: String,
        quantity: BigDecimal,
        priceRate: Double,
        activationPrice: Double?,
        isBuy: Boolean
    ): Result<String> {
        // Attempt 1: original activationPrice
        val result1 = placeTrailingStopOrder(symbol, side, positionSide, quantity, priceRate, activationPrice)
        if (result1.isSuccess) return result1

        val ex1 = result1.exceptionOrNull()
        if (ex1 !is BingXApiException || ex1.code != BingXConstants.ErrorCodes.INVALID_ACTIVATION_PRICE) {
            return result1 // not a price breach error — don't retry
        }

        // 110416 can mean "callback rate too high" (not just activation price) — check message
        if (ex1.message?.contains("callback rate", ignoreCase = true) == true) {
            logger.warn { "BingX trailing stop rejected: callback rate too high for $symbol. Not retrying." }
            return result1
        }

        // Attempt 2: recalculate activationPrice from current market price
        logger.warn { "Trailing activation price breached, recalculating from market price..." }
        kotlinx.coroutines.delay(500)
        val marketPrice = getMarketPrice(symbol).getOrNull()
        if (marketPrice != null) {
            val offset = marketPrice * 0.001 // 0.1% offset in profitable direction
            val recalculated = if (isBuy) marketPrice + offset else marketPrice - offset
            val result2 = placeTrailingStopOrder(symbol, side, positionSide, quantity, priceRate, recalculated)
            if (result2.isSuccess) return result2

            val ex2 = result2.exceptionOrNull()
            if (ex2 !is BingXApiException || ex2.code != BingXConstants.ErrorCodes.INVALID_ACTIVATION_PRICE) {
                return result2
            }
        }

        // Attempt 3: no activationPrice — activate immediately
        logger.warn { "Trailing recalc still rejected, placing without activationPrice (immediate activation)" }
        kotlinx.coroutines.delay(500)
        return placeTrailingStopOrder(symbol, side, positionSide, quantity, priceRate, null)
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

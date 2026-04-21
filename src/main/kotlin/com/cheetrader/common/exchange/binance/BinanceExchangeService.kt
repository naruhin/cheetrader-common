package com.cheetrader.common.exchange.binance

import com.cheetrader.common.exchange.ClosedPnlRecord
import com.cheetrader.common.exchange.ExchangePosition
import com.cheetrader.common.exchange.ExchangeService
import com.cheetrader.common.exchange.extractMultiTpParams
import com.cheetrader.common.exchange.extractTrailingParams
import com.cheetrader.common.exchange.formatPrice
import com.cheetrader.common.exchange.retryConditionalOrder
import com.cheetrader.common.logging.ExchangeLogger
import com.cheetrader.common.logging.NoOpLogger
import com.cheetrader.common.model.ExecutionStatus
import com.cheetrader.common.model.OrderExecution
import com.cheetrader.common.model.Signal
import com.cheetrader.common.model.SignalType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

class BinanceExchangeService(
    apiKey: String,
    secretKey: String,
    testnet: Boolean = false,
    private val defaultLeverage: Int = 20,
    private val marginType: String = BinanceConstants.MARGIN_TYPE_CROSS,
    private val logger: ExchangeLogger = NoOpLogger,
    baseUrlOverride: String? = null
) : ExchangeService {

    override val name = "Binance"

    private val baseUrl = baseUrlOverride ?: if (testnet) {
        "https://testnet.binancefuture.com"
    } else {
        "https://fapi.binance.com"
    }

    private val httpClient = BinanceHttpClient(apiKey, secretKey, baseUrl, logger)
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var precisionCache: Map<String, Int> = emptyMap()

    override suspend fun testConnection(): Result<Boolean> {
        return try {
            httpClient.syncTime()
            loadExchangeInfo()
            val balance = getBalance()
            if (balance.isSuccess) {
                logger.info { "Binance connection OK. Balance: ${balance.getOrNull()}" }
                Result.success(true)
            } else {
                Result.failure(balance.exceptionOrNull() ?: Exception("Connection failed"))
            }
        } catch (e: Exception) {
            logger.error(e) { "Binance connection test failed" }
            Result.failure(e)
        }
    }

    override suspend fun getBalance(): Result<Double> {
        return try {
            httpClient.syncTime()
            val response = httpClient.executeSignedGet(BinanceConstants.Endpoints.GET_BALANCE)
                ?: return Result.failure(BinanceException("No response from API"))

            val element = json.parseToJsonElement(response)
            val array = element as? JsonArray
            if (array != null) {
                val usdt = array.firstOrNull {
                    it.jsonObject["asset"]?.jsonPrimitive?.content == "USDT"
                }?.jsonObject

                val balance = usdt?.get("availableBalance")?.jsonPrimitive?.content?.toDoubleOrNull()
                    ?: usdt?.get("balance")?.jsonPrimitive?.content?.toDoubleOrNull()
                    ?: return Result.failure(BinanceException("Cannot parse balance"))

                return Result.success(balance)
            }

            val error = parseError(response)
            if (error != null) {
                Result.failure(error)
            } else {
                Result.failure(BinanceException("Unexpected balance response"))
            }
        } catch (e: Exception) {
            logger.error(e) { "Binance get balance failed" }
            Result.failure(e)
        }
    }

    override suspend fun executeSignal(signal: Signal, balancePercent: Double): Result<OrderExecution> {
        return try {
            httpClient.syncTime()
            if (precisionCache.isEmpty()) loadExchangeInfo()

            val balanceResult = getBalance()
            if (balanceResult.isFailure) {
                return Result.failure(balanceResult.exceptionOrNull()!!)
            }
            val balance = balanceResult.getOrThrow()

            val symbol = convertSymbol(signal.symbol)
            val isBuy = signal.type == SignalType.LONG
            val side = if (isBuy) BinanceConstants.SIDE_BUY else BinanceConstants.SIDE_SELL

            val dualSide = getPositionSideMode()
            val positionSide = if (dualSide) {
                if (isBuy) BinanceConstants.POSITION_SIDE_LONG else BinanceConstants.POSITION_SIDE_SHORT
            } else {
                null
            }

            setupPosition(symbol)

            val tradeAmount = balance * (balancePercent / 100.0) * defaultLeverage
            val quantity = calculateQuantity(tradeAmount, signal.entryPrice, symbol)
            if (quantity <= BigDecimal.ZERO) {
                return Result.success(
                    OrderExecution(
                        signalId = signal.id,
                        status = ExecutionStatus.FAILED,
                        errorMessage = "Quantity is zero"
                    )
                )
            }

            val referencePrice = getMarketPrice(symbol).getOrNull() ?: signal.entryPrice
            val (safeTp, safeSl) = sanitizeTpSl(
                isBuy = isBuy,
                referencePrice = referencePrice,
                takeProfit = signal.takeProfit,
                stopLoss = signal.stopLoss
            )

            if (signal.stopLoss != null && safeSl == null) {
                return Result.success(OrderExecution(
                    signalId = signal.id,
                    status = ExecutionStatus.FAILED,
                    errorMessage = "SL ${signal.stopLoss} already breached (price=$referencePrice) — order rejected"
                ))
            }

            val orderResult = placeOrder(
                symbol = symbol,
                side = side,
                positionSide = positionSide,
                quantity = quantity,
                reduceOnly = false
            )

            if (orderResult.isSuccess) {
                val orderData = orderResult.getOrThrow()
                val closeSide = if (isBuy) BinanceConstants.SIDE_SELL else BinanceConstants.SIDE_BUY
                val conditionalErrors = mutableListOf<String>()

                val positionQty = orderData.quantity ?: quantity

                // Multi-stage or single take-profit
                val multiTp = extractMultiTpParams(signal.metadata, safeTp)
                if (multiTp != null) {
                    val precision = positionQty.scale()
                    val tp1Qty = positionQty.multiply(java.math.BigDecimal.valueOf(multiTp.tp1Pct / 100.0))
                        .setScale(precision, java.math.RoundingMode.DOWN)
                    val tp2Qty = positionQty.multiply(java.math.BigDecimal.valueOf(multiTp.tp2Pct / 100.0))
                        .setScale(precision, java.math.RoundingMode.DOWN)
                    val tp3Qty = positionQty.subtract(tp1Qty).subtract(tp2Qty)

                    // Guard: if any partial qty is zero after rounding, fallback to single TP
                    if (tp1Qty > java.math.BigDecimal.ZERO && tp2Qty > java.math.BigDecimal.ZERO) {
                        // TP1
                        val tp1Params = buildAlgoOrderParams(
                            symbol, closeSide, positionSide,
                            BinanceConstants.ORDER_TYPE_TAKE_PROFIT_MARKET, multiTp.tp1, tp1Qty,
                            referencePrice
                        )
                        val tp1Result = placeAlgoOrder("TP1", tp1Params)
                        if (tp1Result.isFailure) {
                            conditionalErrors.add("TP1: ${tp1Result.exceptionOrNull()?.message}")
                        }

                        // TP2
                        val tp2Params = buildAlgoOrderParams(
                            symbol, closeSide, positionSide,
                            BinanceConstants.ORDER_TYPE_TAKE_PROFIT_MARKET, multiTp.tp2, tp2Qty,
                            referencePrice
                        )
                        val tp2Result = placeAlgoOrder("TP2", tp2Params)
                        if (tp2Result.isFailure) {
                            conditionalErrors.add("TP2: ${tp2Result.exceptionOrNull()?.message}")
                        }

                        // TP3 (if present and quantity > 0)
                        if (multiTp.tp3 != null && tp3Qty > java.math.BigDecimal.ZERO) {
                            val tp3Params = buildAlgoOrderParams(
                                symbol, closeSide, positionSide,
                                BinanceConstants.ORDER_TYPE_TAKE_PROFIT_MARKET, multiTp.tp3, tp3Qty,
                                referencePrice
                            )
                            val tp3Result = placeAlgoOrder("TP3", tp3Params)
                            if (tp3Result.isFailure) {
                                conditionalErrors.add("TP3: ${tp3Result.exceptionOrNull()?.message}")
                            }
                        }
                    } else {
                        // Fallback: quantities too small for splitting, use single TP
                        val params = buildAlgoOrderParams(
                            symbol, closeSide, positionSide,
                            BinanceConstants.ORDER_TYPE_TAKE_PROFIT_MARKET, multiTp.tp1, positionQty,
                            referencePrice
                        )
                        val result = placeAlgoOrder("TP", params)
                        if (result.isFailure) {
                            conditionalErrors.add("TP: ${result.exceptionOrNull()?.message}")
                        }
                    }

                    // TODO: Break-even after TP1/TP2 requires a PositionMonitorService that watches
                    // for TP fill events and modifies the SL order. Metadata keys "breakEvenAfterTp1"
                    // and "breakEvenAfterTp2" are available in signal.metadata for this purpose.

                    // TODO: Early exit based on oppositeScore requires continuous candle-by-candle
                    // evaluation. Metadata keys "oppositeScore"/"earlyExitScoreThreshold" in
                    // signal.metadata are snapshots at signal time. Real early exit needs a
                    // PositionMonitorService.
                } else {
                    // Single TP (existing behavior)
                    safeTp?.let { tp ->
                        val params = buildAlgoOrderParams(
                            symbol, closeSide, positionSide,
                            BinanceConstants.ORDER_TYPE_TAKE_PROFIT_MARKET, tp, positionQty,
                            referencePrice
                        )
                        val result = placeAlgoOrder("TP", params)
                        if (result.isFailure) {
                            conditionalErrors.add("TP: ${result.exceptionOrNull()?.message}")
                        }
                    }
                }

                safeSl?.let { sl ->
                    val result = retryConditionalOrder {
                        val params = buildAlgoOrderParams(
                            symbol, closeSide, positionSide,
                            BinanceConstants.ORDER_TYPE_STOP_MARKET, sl, positionQty,
                            referencePrice
                        )
                        placeAlgoOrder("SL", params)
                    }
                    if (result.isFailure) {
                        conditionalErrors.add("SL: ${result.exceptionOrNull()?.message}")
                    }
                }

                val trailingParams = extractTrailingParams(signal.metadata)
                trailingParams?.let { tr ->
                    val result = placeTrailingStopWithRecalc(
                        symbol = symbol,
                        side = closeSide,
                        positionSide = positionSide,
                        quantity = positionQty,
                        callbackRate = tr.deviation * 100,
                        activatePrice = tr.triggerPrice ?: referencePrice,
                        referencePrice = referencePrice,
                        isBuy = isBuy
                    )
                    if (result.isFailure) {
                        conditionalErrors.add("Trailing: ${result.exceptionOrNull()?.message}")
                    }
                }

                val status = if (conditionalErrors.isNotEmpty()) {
                    logger.warn { "Binance conditional order errors: $conditionalErrors" }
                    ExecutionStatus.PARTIAL
                } else {
                    ExecutionStatus.SUCCESS
                }

                Result.success(
                    OrderExecution(
                        signalId = signal.id,
                        status = status,
                        exchangeOrderId = orderData.orderId,
                        // Filter out placeholder "0" values — some Binance responses
                        // return avgPrice=0 for market orders before the fill settles.
                        // Surfacing 0.0 as the executed price breaks PnL math downstream.
                        executedPrice = orderData.averagePrice?.toDouble()?.takeIf { it > 0.0 },
                        executedQuantity = orderData.quantity?.toDouble()?.takeIf { it > 0.0 },
                        errorMessage = conditionalErrors.takeIf { it.isNotEmpty() }?.joinToString("; ")
                    )
                )
            } else {
                // Propagate the real exception instead of hiding it inside a
                // Result.success(FAILED) wrapper (the "silent success" anti-pattern
                // fixed in Package A for all exchange adapters).
                Result.failure(orderResult.exceptionOrNull()
                    ?: BinanceException("Order placement failed (no error detail)"))
            }
        } catch (e: Exception) {
            logger.error(e) { "Binance execute signal failed: ${signal.id}" }
            Result.failure(e)
        }
    }

    override suspend fun getOpenPositionsCount(): Result<Int> {
        return getOpenPositions().map { it.size }
    }

    override suspend fun getOpenPositions(): Result<List<ExchangePosition>> {
        return try {
            httpClient.syncTime()
            val response = httpClient.executeSignedGet(BinanceConstants.Endpoints.POSITION_RISK)
                ?: return Result.failure(BinanceException("No response from API"))

            val array = json.parseToJsonElement(response) as? JsonArray
                ?: return Result.success(emptyList())

            val result = array.mapNotNull { pos ->
                val obj = pos.jsonObject
                val positionAmt = obj["positionAmt"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
                if (positionAmt == 0.0) return@mapNotNull null

                val side = obj["positionSide"]?.jsonPrimitive?.content?.let {
                    if (it == "BOTH") null else it
                } ?: if (positionAmt > 0) "LONG" else "SHORT"

                ExchangePosition(
                    symbol = obj["symbol"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                    side = side,
                    size = kotlin.math.abs(positionAmt),
                    entryPrice = obj["entryPrice"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                    markPrice = obj["markPrice"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                    unrealizedPnl = obj["unRealizedProfit"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                    leverage = obj["leverage"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1,
                    margin = obj["isolatedMargin"]?.jsonPrimitive?.content?.toDoubleOrNull()
                        ?: obj["initialMargin"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                    liquidationPrice = obj["liquidationPrice"]?.jsonPrimitive?.content?.toDoubleOrNull()
                )
            }
            Result.success(result)
        } catch (e: Exception) {
            logger.error(e) { "Binance get positions failed" }
            Result.failure(e)
        }
    }

    override suspend fun closePosition(symbol: String): Result<OrderExecution> {
        return try {
            httpClient.syncTime()
            val targetSymbol = convertSymbol(symbol)
            val positions = getPositions(targetSymbol)

            if (positions.isEmpty()) {
                return Result.success(
                    OrderExecution(
                        signalId = "close-$symbol",
                        status = ExecutionStatus.SUCCESS
                    )
                )
            }

            val errors = mutableListOf<String>()
            positions.forEach { position ->
                val result = placeOrder(
                    symbol = targetSymbol,
                    side = position.closeSide,
                    positionSide = position.positionSide,
                    quantity = position.quantity,
                    reduceOnly = true
                )
                if (result.isFailure) {
                    errors.add("${targetSymbol}[${position.positionSide}]: ${result.exceptionOrNull()?.message}")
                }
            }

            if (errors.isNotEmpty()) {
                logger.error { "Binance close position partial failures: $errors" }
                Result.success(
                    OrderExecution(
                        signalId = "close-$symbol",
                        status = ExecutionStatus.PARTIAL,
                        errorMessage = "Failed to close ${errors.size}/${positions.size} positions"
                    )
                )
            } else {
                Result.success(
                    OrderExecution(
                        signalId = "close-$symbol",
                        status = ExecutionStatus.SUCCESS
                    )
                )
            }
        } catch (e: Exception) {
            logger.error(e) { "Binance close position failed: $symbol" }
            Result.failure(e)
        }
    }

    override suspend fun closeAllPositions(): Result<Unit> {
        return try {
            httpClient.syncTime()
            val positions = getPositions(null)
            val errors = mutableListOf<String>()
            positions.forEach { position ->
                val result = placeOrder(
                    symbol = position.symbol,
                    side = position.closeSide,
                    positionSide = position.positionSide,
                    quantity = position.quantity,
                    reduceOnly = true
                )
                if (result.isFailure) {
                    errors.add("${position.symbol}[${position.positionSide}]: ${result.exceptionOrNull()?.message}")
                }
            }
            if (errors.isNotEmpty()) {
                logger.error { "Binance close all positions partial failures: $errors" }
                Result.failure(BinanceException("Failed to close ${errors.size}/${positions.size} positions"))
            } else {
                Result.success(Unit)
            }
        } catch (e: Exception) {
            logger.error(e) { "Binance close all positions failed" }
            Result.failure(e)
        }
    }

    override suspend fun cancelAllOrders(symbol: String): Result<Unit> {
        return try {
            httpClient.syncTime()
            val targetSymbol = convertSymbol(symbol)
            val params = mutableMapOf("symbol" to targetSymbol)

            // Cancel regular orders
            val response = httpClient.executeSignedDelete(BinanceConstants.Endpoints.CANCEL_ALL_ORDERS, params)
                ?: return Result.failure(BinanceException("No response from API"))
            val error = parseError(response)
            if (error != null) {
                return Result.failure(error)
            }

            // Cancel algo orders (TP, SL, trailing stop)
            try {
                val algoResponse = httpClient.executeSignedDelete(
                    BinanceConstants.Endpoints.CANCEL_ALL_ALGO_ORDERS,
                    mutableMapOf("symbol" to targetSymbol)
                )
                if (algoResponse != null) {
                    val algoError = parseError(algoResponse)
                    if (algoError != null) {
                        logger.warn { "Binance cancel algo orders warning: ${algoError.message}" }
                    }
                }
            } catch (e: Exception) {
                logger.warn { "Binance cancel algo orders warning: ${e.message}" }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            logger.error(e) { "Binance cancel all orders failed: $symbol" }
            Result.failure(e)
        }
    }

    private suspend fun placeOrder(
        symbol: String,
        side: String,
        positionSide: String?,
        quantity: BigDecimal,
        reduceOnly: Boolean
    ): Result<BinanceOrderData> {
        val params = mutableMapOf(
            "symbol" to symbol,
            "side" to side,
            "type" to BinanceConstants.ORDER_TYPE_MARKET,
            "quantity" to quantity.toPlainString(),
            "newOrderRespType" to "RESULT"
        )

        if (positionSide != null) {
            params["positionSide"] = positionSide
        } else {
            params["reduceOnly"] = reduceOnly.toString()
        }

        val response = httpClient.executeSignedPost(BinanceConstants.Endpoints.PLACE_ORDER, params)
            ?: return Result.failure(BinanceException("No response from API"))

        val error = parseError(response)
        if (error != null) {
            return Result.failure(error)
        }

        val obj = json.parseToJsonElement(response).jsonObject
        val orderId = obj["orderId"]?.jsonPrimitive?.content
        val avgPrice = obj["avgPrice"]?.jsonPrimitive?.content?.toBigDecimalOrNull()
        val executedQty = obj["executedQty"]?.jsonPrimitive?.content?.toBigDecimalOrNull()

        return Result.success(
            BinanceOrderData(
                orderId = orderId,
                symbol = obj["symbol"]?.jsonPrimitive?.content,
                side = obj["side"]?.jsonPrimitive?.content,
                positionSide = obj["positionSide"]?.jsonPrimitive?.content,
                quantity = executedQty,
                averagePrice = avgPrice
            )
        )
    }

    private fun roundTriggerPrice(price: Double, referencePrice: Double): String {
        val refStr = BigDecimal.valueOf(referencePrice).stripTrailingZeros().toPlainString()
        val decimalPlaces = if ('.' in refStr) refStr.length - refStr.indexOf('.') - 1 else 0
        return BigDecimal.valueOf(price).setScale(decimalPlaces, RoundingMode.HALF_UP).toPlainString()
    }

    private fun buildAlgoOrderParams(
        symbol: String,
        side: String,
        positionSide: String?,
        orderType: String,
        triggerPrice: Double,
        quantity: BigDecimal,
        referencePrice: Double
    ): MutableMap<String, String> {
        val params = mutableMapOf(
            "symbol" to symbol,
            "side" to side,
            "algoType" to "CONDITIONAL",
            "type" to orderType,
            "triggerPrice" to roundTriggerPrice(triggerPrice, referencePrice),
            "quantity" to quantity.toPlainString(),
            "workingType" to BinanceConstants.WORKING_TYPE_MARK_PRICE
        )
        if (positionSide != null) params["positionSide"] = positionSide
        else params["reduceOnly"] = "true"
        return params
    }

    private suspend fun placeAlgoOrder(
        label: String,
        params: MutableMap<String, String>
    ): Result<String> {
        val response = httpClient.executeSignedPost(BinanceConstants.Endpoints.ALGO_ORDER, params)
            ?: return Result.failure(BinanceException("No response from API"))

        val error = parseError(response)
        if (error != null) {
            logger.error { "Binance algo $label failed: ${error.message}" }
            return Result.failure(error)
        }

        return try {
            val obj = json.parseToJsonElement(response).jsonObject
            val algoId = obj["algoId"]?.jsonPrimitive?.content
                ?: obj["orderId"]?.jsonPrimitive?.content
                ?: ""
            logger.info { "Binance algo $label placed: algoId=$algoId" }
            Result.success(algoId)
        } catch (e: Exception) {
            logger.error(e) { "Binance algo $label response parse error. Response: $response" }
            Result.failure(e)
        }
    }

    private suspend fun placeTrailingStopOrder(
        symbol: String,
        side: String,
        positionSide: String?,
        quantity: BigDecimal,
        callbackRate: Double,
        activatePrice: Double,
        referencePrice: Double
    ): Result<String> {
        // Binance requires callbackRate between 0.1 and 5.0, with max 1 decimal place
        val clampedRate = callbackRate.coerceIn(0.1, 5.0)
        val roundedRate = BigDecimal.valueOf(clampedRate)
            .setScale(1, RoundingMode.HALF_UP)
            .toPlainString()
        val params = mutableMapOf(
            "symbol" to symbol,
            "side" to side,
            "algoType" to "CONDITIONAL",
            "type" to BinanceConstants.ORDER_TYPE_TRAILING_STOP_MARKET,
            "quantity" to quantity.toPlainString(),
            "callbackRate" to roundedRate,
            "activationPrice" to roundTriggerPrice(activatePrice, referencePrice),
            "workingType" to BinanceConstants.WORKING_TYPE_MARK_PRICE
        )
        if (positionSide != null) params["positionSide"] = positionSide
        else params["reduceOnly"] = "true"

        return placeAlgoOrder("trailing stop (cb=$roundedRate)", params)
    }

    private suspend fun placeTrailingStopWithRecalc(
        symbol: String,
        side: String,
        positionSide: String?,
        quantity: BigDecimal,
        callbackRate: Double,
        activatePrice: Double,
        referencePrice: Double,
        isBuy: Boolean
    ): Result<String> {
        // Attempt 1: original activatePrice
        val result1 = placeTrailingStopOrder(symbol, side, positionSide, quantity, callbackRate, activatePrice, referencePrice)
        if (result1.isSuccess) return result1

        // Attempt 2: recalculate from current market price
        logger.warn { "Binance trailing failed, recalculating activatePrice from market price..." }
        kotlinx.coroutines.delay(500)
        val marketPrice = getMarketPrice(symbol).getOrNull()
        if (marketPrice != null) {
            val offset = marketPrice * 0.001
            val recalculated = if (isBuy) marketPrice + offset else marketPrice - offset
            val result2 = placeTrailingStopOrder(symbol, side, positionSide, quantity, callbackRate, recalculated, marketPrice)
            if (result2.isSuccess) return result2
        }

        // Attempt 3: use current market price directly (nearest valid activation)
        logger.warn { "Binance trailing recalc still rejected, using market price as activation" }
        kotlinx.coroutines.delay(500)
        val fallbackPrice = marketPrice ?: referencePrice
        return placeTrailingStopOrder(symbol, side, positionSide, quantity, callbackRate, fallbackPrice, fallbackPrice)
    }

    suspend fun loadExchangeInfo() {
        try {
            val response = httpClient.executeGet(BinanceConstants.Endpoints.EXCHANGE_INFO)
                ?: return
            val obj = json.parseToJsonElement(response).jsonObject
            val symbols = obj["symbols"]?.jsonArray ?: return
            val map = mutableMapOf<String, Int>()
            for (sym in symbols) {
                val symObj = sym.jsonObject
                val symbol = symObj["symbol"]?.jsonPrimitive?.content ?: continue
                val filters = symObj["filters"]?.jsonArray ?: continue
                val lotSize = filters.firstOrNull {
                    it.jsonObject["filterType"]?.jsonPrimitive?.content == "LOT_SIZE"
                }?.jsonObject ?: continue
                val stepSize = lotSize["stepSize"]?.jsonPrimitive?.content ?: continue
                map[symbol] = stepSizeToPrecision(stepSize)
            }
            precisionCache = map
            logger.info { "Binance exchange info loaded: ${map.size} symbols" }
        } catch (e: Exception) {
            logger.warn { "Binance exchange info load failed: ${e.message}" }
        }
    }

    private fun stepSizeToPrecision(stepSize: String): Int {
        val cleaned = stepSize.trimEnd('0')
        return if ('.' in cleaned) cleaned.length - cleaned.indexOf('.') - 1 else 0
    }

    private suspend fun setupPosition(symbol: String) {
        try {
            val marginParams = mutableMapOf(
                "symbol" to symbol,
                "marginType" to marginType
            )
            val marginResponse = httpClient.executeSignedPost(BinanceConstants.Endpoints.SET_MARGIN_TYPE, marginParams)
            if (marginResponse != null) {
                val marginError = parseError(marginResponse)
                if (marginError != null && marginError.code != -4046) {
                    logger.warn { "Binance margin type warning: ${marginError.message}" }
                }
            }

            val leverageParams = mutableMapOf(
                "symbol" to symbol,
                "leverage" to defaultLeverage.toString()
            )
            httpClient.executeSignedPost(BinanceConstants.Endpoints.SET_LEVERAGE, leverageParams)
        } catch (e: Exception) {
            logger.warn { "Binance position setup warning: ${e.message}" }
        }
    }

    private suspend fun getPositionSideMode(): Boolean {
        return try {
            val response = httpClient.executeSignedGet(BinanceConstants.Endpoints.POSITION_SIDE_DUAL)
                ?: return false
            val obj = json.parseToJsonElement(response).jsonObject
            obj["dualSidePosition"]?.jsonPrimitive?.booleanOrNull ?: false
        } catch (e: Exception) {
            false
        }
    }

    private data class OpenPosition(
        val symbol: String,
        val quantity: BigDecimal,
        val closeSide: String,
        val positionSide: String?
    )

    private suspend fun getPositions(symbol: String?): List<OpenPosition> {
        val response = httpClient.executeSignedGet(BinanceConstants.Endpoints.POSITION_RISK)
            ?: return emptyList()

        val element = json.parseToJsonElement(response)
        val array = element as? JsonArray ?: return emptyList()

        val positions = mutableListOf<OpenPosition>()
        for (pos in array) {
            val obj = pos.jsonObject
            val posSymbol = obj["symbol"]?.jsonPrimitive?.content ?: continue
            if (symbol != null && posSymbol != symbol) continue

            val positionAmt = obj["positionAmt"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
            if (positionAmt == 0.0) continue

            val closeSide = if (positionAmt > 0) BinanceConstants.SIDE_SELL else BinanceConstants.SIDE_BUY
            val positionSide = obj["positionSide"]?.jsonPrimitive?.content
            val qty = BigDecimal.valueOf(abs(positionAmt))

            positions.add(
                OpenPosition(
                    symbol = posSymbol,
                    quantity = qty,
                    closeSide = closeSide,
                    positionSide = positionSide?.takeIf { it != BinanceConstants.POSITION_SIDE_BOTH }
                )
            )
        }

        return positions
    }

    override suspend fun getMarketPrice(symbol: String): Result<Double> {
        return try {
            val response = httpClient.executeGet(
                BinanceConstants.Endpoints.TICKER_PRICE,
                mapOf("symbol" to symbol)
            ) ?: return Result.failure(BinanceException("No response from API"))

            val obj = json.parseToJsonElement(response).jsonObject
            val price = obj["price"]?.jsonPrimitive?.content?.toDoubleOrNull()
                ?: return Result.failure(BinanceException("Cannot parse price"))
            Result.success(price)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getClosedPnl(limit: Int): Result<List<ClosedPnlRecord>> {
        return try {
            httpClient.syncTime()
            val params = mutableMapOf(
                "incomeType" to "REALIZED_PNL",
                "limit" to limit.coerceIn(1, 1000).toString()
            )
            val response = httpClient.executeSignedGet("/fapi/v1/income", params)
                ?: return Result.success(emptyList())

            val array = json.parseToJsonElement(response) as? JsonArray
                ?: return Result.success(emptyList())

            val records = array.mapNotNull { item ->
                val obj = item.jsonObject
                val income = obj["income"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@mapNotNull null
                val symbol = obj["symbol"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val time = obj["time"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                ClosedPnlRecord(
                    symbol = symbol,
                    side = "",  // Binance income API doesn't include side
                    entryPrice = 0.0,
                    exitPrice = 0.0,
                    quantity = 0.0,
                    closedPnl = income,
                    closedAt = time
                )
            }
            Result.success(records)
        } catch (e: Exception) {
            logger.error(e) { "Binance get closed PnL failed" }
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
            logger.warn { "Binance TP ignored (invalid vs price $referencePrice): $takeProfit" }
        }
        return safeTp to safeSl
    }

    internal fun calculateQuantity(amountUsdt: Double, price: Double, symbol: String): BigDecimal {
        val rawQuantity = amountUsdt / price
        val precision = getQuantityPrecision(symbol)

        return BigDecimal.valueOf(rawQuantity)
            .setScale(precision, RoundingMode.DOWN)
    }

    internal fun getQuantityPrecision(symbol: String): Int {
        precisionCache[symbol.uppercase()]?.let { return it }
        val s = symbol.uppercase()
        return when {
            s.startsWith("BTC") -> 3
            s.startsWith("ETH") -> 3
            s.startsWith("BNB") || s.startsWith("SOL") || s.startsWith("LTC") -> 2
            s.startsWith("XRP") || s.startsWith("ADA") || s.startsWith("DOT") ||
            s.startsWith("AVAX") || s.startsWith("LINK") || s.startsWith("NEAR") ||
            s.startsWith("ATOM") || s.startsWith("FIL") || s.startsWith("APT") ||
            s.startsWith("ARB") || s.startsWith("OP") || s.startsWith("SUI") ||
            s.startsWith("MATIC") || s.startsWith("POL") || s.startsWith("ETC") ||
            s.startsWith("UNI") || s.startsWith("AAVE") || s.startsWith("INJ") -> 1
            s.startsWith("DOGE") || s.startsWith("SHIB") || s.startsWith("PEPE") ||
            s.startsWith("FLOKI") || s.startsWith("1000") || s.startsWith("LUNC") ||
            s.startsWith("TRX") || s.startsWith("BONK") -> 0
            else -> 0
        }
    }

    internal fun convertSymbol(symbol: String): String {
        var cleaned = symbol.uppercase().replace("_", "-")
        if (cleaned.endsWith("-SWAP")) {
            cleaned = cleaned.removeSuffix("-SWAP")
        }
        return cleaned.replace("-", "")
    }

    private fun parseError(response: String): BinanceApiException? {
        return try {
            val obj = json.parseToJsonElement(response).jsonObject
            val code = obj["code"]?.jsonPrimitive?.intOrNull
            val msg = obj["msg"]?.jsonPrimitive?.content
            if (code != null && code < 0) {
                BinanceApiException(code, msg ?: "Unknown error")
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}

package com.cheetrader.common.exchange.bybit

import com.cheetrader.common.exchange.ClosedPnlRecord
import com.cheetrader.common.exchange.ExchangePosition
import com.cheetrader.common.exchange.ExchangeService
import com.cheetrader.common.exchange.extractMultiTpParams
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

class BybitExchangeService(
    private val apiKey: String,
    private val secretKey: String,
    testnet: Boolean = false,
    private val defaultLeverage: Int = 20,
    private val logger: ExchangeLogger = NoOpLogger,
    baseUrlOverride: String? = null
) : ExchangeService {

    override val name = "Bybit"

    private val baseUrlCandidates = if (baseUrlOverride != null) {
        listOf(baseUrlOverride)
    } else if (testnet) {
        listOf("https://api-demo.bybit.com", "https://api-testnet.bybit.com")
    } else {
        listOf("https://api.bybit.com")
    }

    @Volatile
    private var activeBaseUrl = baseUrlCandidates.first()

    @Volatile
    private var httpClient = BybitHttpClient(apiKey, secretKey, activeBaseUrl, logger)
    private val json = Json { ignoreUnknownKeys = true }

    private var cachedPositionMode: Int? = null
    private var positionModeCachedAt: Long = 0
    private val positionModeTtlMs: Long = 5 * 60 * 1000 // 5 minutes

    override suspend fun testConnection(): Result<Boolean> {
        return tryWithBaseUrls {
            httpClient.syncTime()
            val balance = getBalanceInternal()
            if (balance.isFailure) {
                return@tryWithBaseUrls Result.failure(
                    balance.exceptionOrNull() ?: Exception("Connection failed")
                )
            }

            // Also verify position endpoint works (different permissions)
            val positionResult = getOpenPositions()
            if (positionResult.isFailure) {
                val err = positionResult.exceptionOrNull()
                logger.error { "Bybit position endpoint failed: ${err?.message}" }
                return@tryWithBaseUrls Result.failure(
                    BybitException("Balance OK but position endpoint failed: ${err?.message}. " +
                            "Check API key permissions (Trade/Position required).")
                )
            }

            logger.info { "Bybit connection OK. Balance: ${balance.getOrNull()}" }
            Result.success(true)
        }
    }

    override suspend fun getBalance(): Result<Double> {
        return tryWithBaseUrls { getBalanceInternal() }
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
            val side = if (isBuy) BybitConstants.SIDE_BUY else BybitConstants.SIDE_SELL

            setLeverage(symbol)

            val positionMode = getPositionMode()
            val desiredIdx = if (positionMode == 3) {
                if (isBuy) 1 else 2
            } else {
                0
            }

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

            // Check for multi-stage TP
            val multiTp = extractMultiTpParams(signal.metadata, safeTp)
            val trailingParams = extractTrailingParams(signal.metadata)

            val orderTp = if (multiTp != null) null else safeTp

            var usedIdx = desiredIdx
            var orderResult = placeOrder(
                symbol = symbol,
                side = side,
                quantity = quantity,
                positionIdx = desiredIdx,
                reduceOnly = false,
                takeProfit = orderTp,
                stopLoss = safeSl,
                trailingStop = trailingParams?.let { formatPrice(it.deviation * referencePrice) }
            )

            if (orderResult.isFailure && desiredIdx != 0) {
                usedIdx = 0
                orderResult = placeOrder(
                    symbol = symbol,
                    side = side,
                    quantity = quantity,
                    positionIdx = 0,
                    reduceOnly = false,
                    takeProfit = orderTp,
                    stopLoss = safeSl,
                    trailingStop = trailingParams?.let { formatPrice(it.deviation * referencePrice) }
                )
            }

            if (orderResult.isSuccess) {
                val orderId = orderResult.getOrThrow()
                val conditionalErrors = mutableListOf<String>()

                // Multi-stage take-profit orders
                if (multiTp != null) {
                    val closeSide = if (isBuy) BybitConstants.SIDE_SELL else BybitConstants.SIDE_BUY
                    val precision = quantity.scale()
                    val tp1Qty = quantity.multiply(BigDecimal.valueOf(multiTp.tp1Pct / 100.0))
                        .setScale(precision, RoundingMode.DOWN)
                    val tp2Qty = quantity.multiply(BigDecimal.valueOf(multiTp.tp2Pct / 100.0))
                        .setScale(precision, RoundingMode.DOWN)
                    val tp3Qty = quantity.subtract(tp1Qty).subtract(tp2Qty)

                    if (tp1Qty > BigDecimal.ZERO && tp2Qty > BigDecimal.ZERO) {
                        val tp1Result = placeTpAlgoOrder(symbol, closeSide, usedIdx, isBuy, multiTp.tp1, tp1Qty)
                        if (tp1Result.isFailure) conditionalErrors.add("TP1: ${tp1Result.exceptionOrNull()?.message}")

                        val tp2Result = placeTpAlgoOrder(symbol, closeSide, usedIdx, isBuy, multiTp.tp2, tp2Qty)
                        if (tp2Result.isFailure) conditionalErrors.add("TP2: ${tp2Result.exceptionOrNull()?.message}")

                        if (multiTp.tp3 != null && tp3Qty > BigDecimal.ZERO) {
                            val tp3Result = placeTpAlgoOrder(symbol, closeSide, usedIdx, isBuy, multiTp.tp3, tp3Qty)
                            if (tp3Result.isFailure) conditionalErrors.add("TP3: ${tp3Result.exceptionOrNull()?.message}")
                        }
                    } else {
                        // Fallback: quantities too small for splitting, use single TP
                        val tpResult = placeTpAlgoOrder(symbol, closeSide, usedIdx, isBuy, multiTp.tp1, quantity)
                        if (tpResult.isFailure) conditionalErrors.add("TP: ${tpResult.exceptionOrNull()?.message}")
                    }
                }

                val status = if (conditionalErrors.isNotEmpty()) {
                    logger.warn { "Bybit conditional order errors: $conditionalErrors" }
                    ExecutionStatus.PARTIAL
                } else {
                    ExecutionStatus.SUCCESS
                }

                Result.success(
                    OrderExecution(
                        signalId = signal.id,
                        status = status,
                        exchangeOrderId = orderId,
                        errorMessage = conditionalErrors.takeIf { it.isNotEmpty() }?.joinToString("; ")
                    )
                )
            } else {
                Result.success(
                    OrderExecution(
                        signalId = signal.id,
                        status = ExecutionStatus.FAILED,
                        errorMessage = orderResult.exceptionOrNull()?.message
                    )
                )
            }
        } catch (e: Exception) {
            logger.error(e) { "Bybit execute signal failed: ${signal.id}" }
            Result.success(
                OrderExecution(
                    signalId = signal.id,
                    status = ExecutionStatus.FAILED,
                    errorMessage = e.message
                )
            )
        }
    }

    private suspend fun getBalanceInternal(): Result<Double> {
        return try {
            httpClient.syncTime()
            val balance = fetchBalance("UNIFIED")
            if (balance != null) {
                return Result.success(balance)
            }

            val fallback = fetchBalance("CONTRACT")
            if (fallback != null) {
                Result.success(fallback)
            } else {
                Result.failure(BybitException("Cannot parse balance"))
            }
        } catch (e: Exception) {
            logger.error(e) { "Bybit get balance failed" }
            Result.failure(e)
        }
    }

    private suspend fun <T> tryWithBaseUrls(action: suspend () -> Result<T>): Result<T> {
        if (baseUrlCandidates.size == 1) {
            return action()
        }

        var lastFailure: Throwable? = null
        for (url in baseUrlCandidates) {
            switchBaseUrl(url)
            val result = action()
            if (result.isSuccess) {
                return result
            }
            lastFailure = result.exceptionOrNull()
        }

        return Result.failure(lastFailure ?: BybitException("All Bybit endpoints failed"))
    }

    private fun switchBaseUrl(newUrl: String) {
        if (activeBaseUrl == newUrl) return
        activeBaseUrl = newUrl
        httpClient = BybitHttpClient(apiKey, secretKey, activeBaseUrl, logger)
        cachedPositionMode = null
    }

    override suspend fun closePosition(symbol: String): Result<OrderExecution> {
        return tryWithBaseUrls { closePositionInternal(symbol) }
    }

    private suspend fun closePositionInternal(symbol: String): Result<OrderExecution> {
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
                    quantity = position.size,
                    positionIdx = position.positionIdx,
                    reduceOnly = true,
                    takeProfit = null,
                    stopLoss = null
                )
                if (result.isFailure) {
                    errors.add("${position.symbol}[idx=${position.positionIdx}]: ${result.exceptionOrNull()?.message}")
                }
            }

            if (errors.isNotEmpty()) {
                logger.error { "Bybit close position partial failures: $errors" }
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
            logger.error(e) { "Bybit close position failed: $symbol" }
            Result.failure(e)
        }
    }

    override suspend fun closeAllPositions(): Result<Unit> {
        return tryWithBaseUrls { closeAllPositionsInternal() }
    }

    private suspend fun closeAllPositionsInternal(): Result<Unit> {
        return try {
            httpClient.syncTime()
            val positions = getPositions(null)
            val errors = mutableListOf<String>()
            positions.forEach { position ->
                val result = placeOrder(
                    symbol = position.symbol,
                    side = position.closeSide,
                    quantity = position.size,
                    positionIdx = position.positionIdx,
                    reduceOnly = true,
                    takeProfit = null,
                    stopLoss = null
                )
                if (result.isFailure) {
                    errors.add("${position.symbol}[idx=${position.positionIdx}]: ${result.exceptionOrNull()?.message}")
                }
            }
            if (errors.isNotEmpty()) {
                logger.error { "Bybit close all positions partial failures: $errors" }
                Result.failure(BybitException("Failed to close ${errors.size}/${positions.size} positions"))
            } else {
                Result.success(Unit)
            }
        } catch (e: Exception) {
            logger.error(e) { "Bybit close all positions failed" }
            Result.failure(e)
        }
    }

    override suspend fun cancelAllOrders(symbol: String): Result<Unit> {
        return tryWithBaseUrls { cancelAllOrdersInternal(symbol) }
    }

    private suspend fun cancelAllOrdersInternal(symbol: String): Result<Unit> {
        return try {
            httpClient.syncTime()
            val targetSymbol = convertSymbol(symbol)
            val body = buildJsonBody(
                mapOf(
                    "category" to BybitConstants.CATEGORY_LINEAR,
                    "symbol" to targetSymbol
                )
            )
            val response = httpClient.executeSignedPost(BybitConstants.Endpoints.CANCEL_ALL_ORDERS, body)
                ?: return Result.failure(BybitException("No response from API"))

            val error = parseError(response)
            if (error != null) {
                Result.failure(error)
            } else {
                Result.success(Unit)
            }
        } catch (e: Exception) {
            logger.error(e) { "Bybit cancel all orders failed: $symbol" }
            Result.failure(e)
        }
    }

    override suspend fun getOpenPositionsCount(): Result<Int> {
        return getOpenPositions().map { it.size }
    }

    override suspend fun getOpenPositions(): Result<List<ExchangePosition>> {
        return tryWithBaseUrls {
            try {
                httpClient.syncTime()
                val params = mutableMapOf(
                    "category" to BybitConstants.CATEGORY_LINEAR,
                    "settleCoin" to "USDT"
                )
                val response = httpClient.executeSignedGet(BybitConstants.Endpoints.POSITION_LIST, params)
                    ?: return@tryWithBaseUrls Result.failure(BybitException("No response from position list API"))

                val error = parseError(response)
                if (error != null) return@tryWithBaseUrls Result.failure(error)

                val obj = json.parseToJsonElement(response).jsonObject
                val list = obj["result"]?.jsonObject?.get("list")?.jsonArray ?: JsonArray(emptyList())
                val result = list.mapNotNull { item ->
                    val pos = item.jsonObject
                    val size = pos["size"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
                    if (size == 0.0) return@mapNotNull null

                    val side = pos["side"]?.jsonPrimitive?.content ?: return@mapNotNull null

                    ExchangePosition(
                        symbol = pos["symbol"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                        side = if (side == BybitConstants.SIDE_BUY) "LONG" else "SHORT",
                        size = kotlin.math.abs(size),
                        entryPrice = pos["avgPrice"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                        markPrice = pos["markPrice"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                        unrealizedPnl = pos["unrealisedPnl"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                        leverage = pos["leverage"]?.jsonPrimitive?.content?.toDoubleOrNull()?.toInt() ?: 1,
                        margin = pos["positionIM"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                        liquidationPrice = pos["liqPrice"]?.jsonPrimitive?.content?.toDoubleOrNull()
                    )
                }
                Result.success(result)
            } catch (e: Exception) {
                logger.error(e) { "Bybit get positions failed" }
                Result.failure(e)
            }
        }
    }

    private suspend fun fetchBalance(accountType: String): Double? {
        val response = httpClient.executeSignedGet(
            BybitConstants.Endpoints.WALLET_BALANCE,
            mapOf(
                "accountType" to accountType,
                "coin" to "USDT"
            )
        ) ?: return null

        val error = parseError(response)
        if (error != null) {
            return null
        }

        val obj = json.parseToJsonElement(response).jsonObject
        val list = obj["result"]?.jsonObject?.get("list")?.jsonArray ?: JsonArray(emptyList())
        val first = list.firstOrNull()?.jsonObject ?: return null
        val coins = first["coin"]?.jsonArray ?: JsonArray(emptyList())

        val usdt = coins.firstOrNull {
            it.jsonObject["coin"]?.jsonPrimitive?.content == "USDT"
        }?.jsonObject ?: return null

        val available = usdt["availableToWithdraw"]?.jsonPrimitive?.content?.toDoubleOrNull()
        val wallet = usdt["walletBalance"]?.jsonPrimitive?.content?.toDoubleOrNull()
        return available ?: wallet
    }

    private suspend fun setLeverage(symbol: String) {
        try {
            val body = buildJsonBody(
                mapOf(
                    "category" to BybitConstants.CATEGORY_LINEAR,
                    "symbol" to symbol,
                    "buyLeverage" to defaultLeverage.toString(),
                    "sellLeverage" to defaultLeverage.toString()
                )
            )
            httpClient.executeSignedPost(BybitConstants.Endpoints.SET_LEVERAGE, body)
        } catch (e: Exception) {
            logger.warn { "Bybit leverage warning: ${e.message}" }
        }
    }

    private suspend fun getPositionMode(): Int {
        val cached = cachedPositionMode
        if (cached != null && (System.currentTimeMillis() - positionModeCachedAt) < positionModeTtlMs) {
            return cached
        }

        return try {
            val response = httpClient.executeSignedGet(
                BybitConstants.Endpoints.POSITION_LIST,
                mapOf("category" to BybitConstants.CATEGORY_LINEAR, "settleCoin" to "USDT", "limit" to "10")
            ) ?: return detectFromPositionModeEndpoint()

            val error = parseError(response)
            if (error != null) {
                return detectFromPositionModeEndpoint()
            }

            val obj = json.parseToJsonElement(response).jsonObject
            val list = obj["result"]?.jsonObject?.get("list")?.jsonArray ?: JsonArray(emptyList())

            val isHedge = list.any { item ->
                val idx = item.jsonObject["positionIdx"]?.jsonPrimitive?.intOrNull ?: 0
                idx != 0
            }

            val mode = if (isHedge) 3 else 0
            cachedPositionMode = mode
            positionModeCachedAt = System.currentTimeMillis()
            mode
        } catch (_: Exception) {
            0
        }
    }

    private suspend fun detectFromPositionModeEndpoint(): Int {
        return try {
            val response = httpClient.executeSignedGet(
                BybitConstants.Endpoints.ACCOUNT_INFO,
                emptyMap()
            ) ?: return 0

            val error = parseError(response)
            if (error != null) return 0

            val obj = json.parseToJsonElement(response).jsonObject
            val result = obj["result"]?.jsonObject
            // unifiedMarginStatus: 1=regular, 3=unified, 4=UTA Pro
            // positionMode: 0=one-way, 3=hedge
            val mode = result?.get("positionMode")?.jsonPrimitive?.intOrNull ?: 0

            cachedPositionMode = mode
            positionModeCachedAt = System.currentTimeMillis()
            mode
        } catch (_: Exception) {
            0
        }
    }

    private data class BybitPosition(
        val symbol: String,
        val size: BigDecimal,
        val closeSide: String,
        val positionIdx: Int
    )

    private suspend fun getPositions(symbol: String?): List<BybitPosition> {
        val params = mutableMapOf(
            "category" to BybitConstants.CATEGORY_LINEAR,
            "settleCoin" to "USDT"
        )
        symbol?.let { params["symbol"] = it }

        val response = httpClient.executeSignedGet(BybitConstants.Endpoints.POSITION_LIST, params)
            ?: return emptyList()

        val error = parseError(response)
        if (error != null) {
            return emptyList()
        }

        val obj = json.parseToJsonElement(response).jsonObject
        val list = obj["result"]?.jsonObject?.get("list")?.jsonArray ?: JsonArray(emptyList())
        val positions = mutableListOf<BybitPosition>()

        for (item in list) {
            val pos = item.jsonObject
            val posSymbol = pos["symbol"]?.jsonPrimitive?.content ?: continue
            val size = pos["size"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
            if (size == 0.0) continue

            val side = pos["side"]?.jsonPrimitive?.content ?: continue
            val closeSide = if (side == BybitConstants.SIDE_BUY) BybitConstants.SIDE_SELL else BybitConstants.SIDE_BUY
            val positionIdx = pos["positionIdx"]?.jsonPrimitive?.intOrNull ?: 0

            positions.add(
                BybitPosition(
                    symbol = posSymbol,
                    size = BigDecimal.valueOf(abs(size)),
                    closeSide = closeSide,
                    positionIdx = positionIdx
                )
            )
        }

        return positions
    }

    private suspend fun placeTpAlgoOrder(
        symbol: String,
        side: String,
        positionIdx: Int,
        isBuy: Boolean,
        triggerPrice: Double,
        quantity: BigDecimal
    ): Result<String> {
        // triggerDirection: 1 = price rises to trigger, 2 = price falls to trigger
        // For LONG TP: price rises → triggerDirection=1
        // For SHORT TP: price falls → triggerDirection=2
        val triggerDirection = if (isBuy) "1" else "2"
        val params = mutableMapOf(
            "category" to BybitConstants.CATEGORY_LINEAR,
            "symbol" to symbol,
            "side" to side,
            "orderType" to BybitConstants.ORDER_TYPE_MARKET,
            "qty" to quantity.toPlainString(),
            "timeInForce" to BybitConstants.TIME_IN_FORCE_IOC,
            "reduceOnly" to "true",
            "positionIdx" to positionIdx.toString(),
            "triggerPrice" to formatPrice(triggerPrice),
            "triggerDirection" to triggerDirection,
            "triggerBy" to BybitConstants.TRIGGER_BY_MARK_PRICE
        )

        val response = httpClient.executeSignedPost(BybitConstants.Endpoints.ORDER_CREATE, buildJsonBody(params))
            ?: return Result.failure(BybitException("No response from API"))

        val error = parseError(response)
        if (error != null) {
            logger.error { "Bybit TP algo failed: ${error.message}" }
            return Result.failure(error)
        }

        val obj = json.parseToJsonElement(response).jsonObject
        val orderId = obj["result"]?.jsonObject?.get("orderId")?.jsonPrimitive?.content ?: ""
        logger.info { "Bybit TP algo placed: orderId=$orderId triggerPrice=$triggerPrice qty=$quantity" }
        return Result.success(orderId)
    }

    private suspend fun placeOrder(
        symbol: String,
        side: String,
        quantity: BigDecimal,
        positionIdx: Int,
        reduceOnly: Boolean,
        takeProfit: Double?,
        stopLoss: Double?,
        trailingStop: String? = null
    ): Result<String> {
        val params = mutableMapOf(
            "category" to BybitConstants.CATEGORY_LINEAR,
            "symbol" to symbol,
            "side" to side,
            "orderType" to BybitConstants.ORDER_TYPE_MARKET,
            "qty" to quantity.toPlainString(),
            "timeInForce" to BybitConstants.TIME_IN_FORCE_IOC,
            "reduceOnly" to reduceOnly.toString(),
            "positionIdx" to positionIdx.toString()
        )

        if (!reduceOnly) {
            if (takeProfit != null || stopLoss != null) {
                params["tpslMode"] = BybitConstants.TPSL_MODE_FULL
            }
            takeProfit?.let {
                params["takeProfit"] = formatPrice(it)
                params["tpTriggerBy"] = BybitConstants.TRIGGER_BY_MARK_PRICE
            }
            stopLoss?.let {
                params["stopLoss"] = formatPrice(it)
                params["slTriggerBy"] = BybitConstants.TRIGGER_BY_MARK_PRICE
            }
            trailingStop?.let {
                params["trailingStop"] = it
            }
        }

        val response = httpClient.executeSignedPost(BybitConstants.Endpoints.ORDER_CREATE, buildJsonBody(params))
            ?: return Result.failure(BybitException("No response from API"))

        val error = parseError(response)
        if (error != null) {
            return Result.failure(error)
        }

        val obj = json.parseToJsonElement(response).jsonObject
        val orderId = obj["result"]?.jsonObject?.get("orderId")?.jsonPrimitive?.content
        return Result.success(orderId ?: "")
    }

    override suspend fun getMarketPrice(symbol: String): Result<Double> {
        return try {
            val response = httpClient.executePublicGet(
                "/v5/market/tickers",
                mapOf("category" to BybitConstants.CATEGORY_LINEAR, "symbol" to symbol)
            ) ?: return Result.failure(BybitException("No response from API"))

            val obj = json.parseToJsonElement(response).jsonObject
            val list = obj["result"]?.jsonObject?.get("list")?.jsonArray ?: JsonArray(emptyList())
            val first = list.firstOrNull()?.jsonObject
                ?: return Result.failure(BybitException("Empty ticker list"))
            val price = first["lastPrice"]?.jsonPrimitive?.content?.toDoubleOrNull()
                ?: first["markPrice"]?.jsonPrimitive?.content?.toDoubleOrNull()
                ?: return Result.failure(BybitException("Cannot parse price"))
            Result.success(price)
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
            logger.warn { "Bybit TP ignored (invalid vs price $referencePrice): $takeProfit" }
        }
        if (stopLoss != null && safeSl == null) {
            logger.warn { "Bybit SL ignored (invalid vs price $referencePrice): $stopLoss" }
        }

        return safeTp to safeSl
    }

    private fun buildJsonBody(params: Map<String, String>): String {
        val obj = buildJsonObject {
            params.forEach { (key, value) ->
                put(key, JsonPrimitive(value))
            }
        }
        return obj.toString()
    }

    internal fun calculateQuantity(amountUsdt: Double, price: Double, symbol: String): BigDecimal {
        val rawQuantity = amountUsdt / price
        val precision = getQuantityPrecision(symbol)

        return BigDecimal.valueOf(rawQuantity)
            .setScale(precision, RoundingMode.DOWN)
    }

    internal fun getQuantityPrecision(symbol: String): Int {
        // Bybit-specific qtyStep values (different from Binance!)
        val s = symbol.uppercase()
        return when {
            s.startsWith("BTC") -> 3    // qtyStep=0.001
            s.startsWith("ETH") -> 2    // qtyStep=0.01 (Binance=0.001)
            s.startsWith("BNB") || s.startsWith("LTC") -> 2  // qtyStep=0.01
            s.startsWith("SOL") -> 1    // qtyStep=0.1 (Binance=0.01)
            s.startsWith("XRP") || s.startsWith("ADA") || s.startsWith("DOT") ||
            s.startsWith("AVAX") || s.startsWith("NEAR") ||
            s.startsWith("FIL") || s.startsWith("APT") ||
            s.startsWith("ARB") || s.startsWith("OP") || s.startsWith("SUI") ||
            s.startsWith("MATIC") || s.startsWith("POL") || s.startsWith("ETC") -> 1
            s.startsWith("LINK") || s.startsWith("ATOM") ||
            s.startsWith("UNI") || s.startsWith("AAVE") || s.startsWith("INJ") -> 2  // qtyStep=0.01
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

    override suspend fun getClosedPnl(limit: Int): Result<List<ClosedPnlRecord>> {
        return tryWithBaseUrls {
            try {
                httpClient.syncTime()
                val params = mutableMapOf(
                    "category" to BybitConstants.CATEGORY_LINEAR,
                    "limit" to limit.coerceIn(1, 100).toString()
                )
                val response = httpClient.executeSignedGet("/v5/position/closed-pnl", params)
                    ?: return@tryWithBaseUrls Result.success(emptyList())

                val error = parseError(response)
                if (error != null) return@tryWithBaseUrls Result.failure(error)

                val obj = json.parseToJsonElement(response).jsonObject
                val list = obj["result"]?.jsonObject?.get("list")?.jsonArray ?: JsonArray(emptyList())
                val records = list.mapNotNull { item ->
                    val rec = item.jsonObject
                    val closedPnl = rec["closedPnl"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@mapNotNull null
                    val side = rec["side"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    ClosedPnlRecord(
                        symbol = rec["symbol"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                        side = if (side == BybitConstants.SIDE_BUY) "LONG" else "SHORT",
                        entryPrice = rec["avgEntryPrice"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                        exitPrice = rec["avgExitPrice"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                        quantity = rec["qty"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                        closedPnl = closedPnl,
                        closedAt = rec["updatedTime"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                    )
                }
                Result.success(records)
            } catch (e: Exception) {
                logger.error(e) { "Bybit get closed PnL failed" }
                Result.failure(e)
            }
        }
    }

    private fun parseError(response: String): BybitApiException? {
        return try {
            val obj = json.parseToJsonElement(response).jsonObject
            val code = obj["retCode"]?.jsonPrimitive?.intOrNull
            val msg = obj["retMsg"]?.jsonPrimitive?.content
            if (code != null && code != 0) {
                BybitApiException(code, msg ?: "Unknown error")
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}

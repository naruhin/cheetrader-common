package com.cheetrader.common.exchange.okx

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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.ConcurrentHashMap

class OkxExchangeService(
    apiKey: String,
    secretKey: String,
    passphrase: String,
    testnet: Boolean = false,
    private val defaultLeverage: Int = 20,
    crossMargin: Boolean = true,
    private val logger: ExchangeLogger = NoOpLogger,
    baseUrlOverride: String? = null
) : ExchangeService {

    override val name = "OKX"

    private val baseUrl = baseUrlOverride ?: "https://www.okx.com"
    private val preferredMarginMode = if (crossMargin) {
        OkxConstants.MARGIN_MODE_CROSS
    } else {
        OkxConstants.MARGIN_MODE_ISOLATED
    }

    private val httpClient = OkxHttpClient(apiKey, secretKey, passphrase, baseUrl, testnet, logger)
    private val json = Json { ignoreUnknownKeys = true }

    private data class AccountConfig(
        val posMode: String,
        val acctLv: Int       // 1=Simple, 2=Single-ccy, 3=Multi-ccy, 4=Portfolio
    )

    private var cachedAccountConfig: AccountConfig? = null
    private var accountConfigCachedAt: Long = 0
    private val accountConfigTtlMs: Long = 5 * 60 * 1000 // 5 minutes

    private data class InstrumentInfo(
        val ctVal: BigDecimal,   // contract value (e.g. 0.01 BTC per contract)
        val lotSz: BigDecimal,   // lot size (min order increment in contracts)
        val minSz: BigDecimal    // minimum order size in contracts
    )

    private val instrumentCache = ConcurrentHashMap<String, InstrumentInfo>()

    override suspend fun testConnection(): Result<Boolean> {
        return try {
            val balance = getBalance()
            if (balance.isSuccess) {
                logger.info { "OKX connection OK. Balance: ${balance.getOrNull()}" }
                Result.success(true)
            } else {
                Result.failure(balance.exceptionOrNull() ?: Exception("Connection failed"))
            }
        } catch (e: Exception) {
            logger.error(e) { "OKX connection test failed" }
            Result.failure(e)
        }
    }

    override suspend fun getBalance(): Result<Double> {
        return try {
            val response = httpClient.executeSignedGet(
                OkxConstants.Endpoints.ACCOUNT_BALANCE,
                mapOf("ccy" to "USDT")
            ) ?: return Result.failure(OkxException("No response from API"))

            val error = parseError(response)
            if (error != null) {
                return Result.failure(error)
            }

            val obj = json.parseToJsonElement(response).jsonObject
            val data = obj["data"]?.jsonArray ?: JsonArray(emptyList())
            val first = data.firstOrNull()?.jsonObject
            val details = first?.get("details")?.jsonArray ?: JsonArray(emptyList())
            val usdt = details.firstOrNull {
                it.jsonObject["ccy"]?.jsonPrimitive?.content == "USDT"
            }?.jsonObject

            val balance = readBalanceField(usdt, "availEq")
                ?: readBalanceField(usdt, "availBal")
                ?: readBalanceField(usdt, "cashBal")
                ?: readBalanceField(usdt, "eq")
                ?: readBalanceField(first, "totalEq")
                ?: readBalanceField(first, "adjEq")
                ?: return Result.failure(OkxException("Cannot parse balance (USDT not found)"))

            Result.success(balance)
        } catch (e: Exception) {
            logger.error(e) { "OKX get balance failed" }
            Result.failure(e)
        }
    }

    override suspend fun executeSignal(signal: Signal, balancePercent: Double): Result<OrderExecution> {
        return try {
            val balanceResult = getBalance()
            if (balanceResult.isFailure) {
                return Result.failure(balanceResult.exceptionOrNull()!!)
            }
            val balance = balanceResult.getOrThrow()

            val instId = convertSymbol(signal.symbol)
            val isBuy = signal.type == SignalType.LONG
            val side = if (isBuy) OkxConstants.SIDE_BUY else OkxConstants.SIDE_SELL
            val posSide = if (getPositionMode() == OkxConstants.POS_MODE_LONG_SHORT) {
                if (isBuy) OkxConstants.POS_SIDE_LONG else OkxConstants.POS_SIDE_SHORT
            } else {
                null
            }

            setLeverage(instId, posSide)

            val instrumentInfo = getInstrumentInfo(instId)
            if (instrumentInfo == null) {
                return Result.success(
                    OrderExecution(
                        signalId = signal.id,
                        status = ExecutionStatus.FAILED,
                        errorMessage = "Instrument $instId not found on OKX"
                    )
                )
            }

            val tradeAmount = balance * (balancePercent / 100.0) * defaultLeverage
            val quantity = calculateContractQuantity(tradeAmount, signal.entryPrice, instrumentInfo)
            if (quantity <= BigDecimal.ZERO) {
                return Result.success(
                    OrderExecution(
                        signalId = signal.id,
                        status = ExecutionStatus.FAILED,
                        errorMessage = "Quantity is zero (trade amount too small for contract size)"
                    )
                )
            }

            val referencePrice = getMarketPrice(instId).getOrNull() ?: signal.entryPrice
            val (safeTp, safeSl) = sanitizeTpSl(
                isBuy = isBuy,
                referencePrice = referencePrice,
                takeProfit = signal.takeProfit,
                stopLoss = signal.stopLoss
            )

            val orderResult = placeOrder(
                instId = instId,
                side = side,
                posSide = posSide,
                quantity = quantity,
                takeProfit = safeTp,
                stopLoss = safeSl
            )

            if (orderResult.isSuccess) {
                val orderId = orderResult.getOrThrow()
                val conditionalErrors = mutableListOf<String>()

                // Trailing stop via algo order
                val trailingParams = extractTrailingParams(signal.metadata)
                if (trailingParams != null) {
                    val trailingResult = placeTrailingStopAlgo(
                        instId = instId,
                        side = if (isBuy) OkxConstants.SIDE_SELL else OkxConstants.SIDE_BUY,
                        posSide = posSide,
                        sz = quantity,
                        callbackRatio = trailingParams.deviation,
                        activePx = trailingParams.triggerPrice
                    )
                    if (trailingResult.isFailure) {
                        conditionalErrors.add("Trailing: ${trailingResult.exceptionOrNull()?.message}")
                    }
                }

                val status = if (conditionalErrors.isNotEmpty()) {
                    logger.warn { "OKX conditional order errors: $conditionalErrors" }
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
            logger.error(e) { "OKX execute signal failed: ${signal.id}" }
            Result.success(
                OrderExecution(
                    signalId = signal.id,
                    status = ExecutionStatus.FAILED,
                    errorMessage = e.message
                )
            )
        }
    }

    override suspend fun closePosition(symbol: String): Result<OrderExecution> {
        return try {
            val instId = convertSymbol(symbol)
            val positions = getPositions(instId)
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
                try {
                    closePositionInternal(instId, position.posSide)
                } catch (e: Exception) {
                    errors.add("${instId}[${position.posSide}]: ${e.message}")
                }
            }

            if (errors.isNotEmpty()) {
                logger.error { "OKX close position partial failures: $errors" }
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
            logger.error(e) { "OKX close position failed: $symbol" }
            Result.failure(e)
        }
    }

    override suspend fun closeAllPositions(): Result<Unit> {
        return try {
            val positions = getPositions(null)
            val errors = mutableListOf<String>()
            positions.forEach { position ->
                try {
                    closePositionInternal(position.instId, position.posSide)
                } catch (e: Exception) {
                    errors.add("${position.instId}[${position.posSide}]: ${e.message}")
                }
            }
            if (errors.isNotEmpty()) {
                logger.error { "OKX close all positions partial failures: $errors" }
                Result.failure(OkxException("Failed to close ${errors.size}/${positions.size} positions"))
            } else {
                Result.success(Unit)
            }
        } catch (e: Exception) {
            logger.error(e) { "OKX close all positions failed" }
            Result.failure(e)
        }
    }

    override suspend fun getOpenPositionsCount(): Result<Int> {
        return try {
            val positions = getPositions(null)
            Result.success(positions.size)
        } catch (e: Exception) {
            logger.error(e) { "OKX get positions failed" }
            Result.failure(e)
        }
    }

    override suspend fun cancelAllOrders(symbol: String): Result<Unit> {
        return try {
            val instId = convertSymbol(symbol)
            val pending = getPendingOrders(instId)
            if (pending.isEmpty()) {
                return Result.success(Unit)
            }

            val chunks = pending.chunked(20)
            for (chunk in chunks) {
                val body = buildJsonArray {
                    chunk.forEach { ordId ->
                        add(
                            buildJsonObject {
                                put("instId", JsonPrimitive(instId))
                                put("ordId", JsonPrimitive(ordId))
                            }
                        )
                    }
                }.toString()

                val response = httpClient.executeSignedPost(OkxConstants.Endpoints.CANCEL_BATCH, body)
                    ?: return Result.failure(OkxException("No response from API"))

                val error = parseError(response)
                if (error != null) {
                    return Result.failure(error)
                }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            logger.error(e) { "OKX cancel all orders failed: $symbol" }
            Result.failure(e)
        }
    }

    private suspend fun setLeverage(instId: String, posSide: String?) {
        try {
            val mgnMode = resolveMarginMode()
            if (posSide != null) {
                // In long_short_mode, set leverage for both sides
                for (side in listOf(OkxConstants.POS_SIDE_LONG, OkxConstants.POS_SIDE_SHORT)) {
                    val params = mutableMapOf(
                        "instId" to instId,
                        "mgnMode" to mgnMode,
                        "lever" to defaultLeverage.toString(),
                        "posSide" to side
                    )
                    val body = buildJsonBody(params)
                    httpClient.executeSignedPost(OkxConstants.Endpoints.SET_LEVERAGE, body)
                }
            } else {
                val params = mutableMapOf(
                    "instId" to instId,
                    "mgnMode" to mgnMode,
                    "lever" to defaultLeverage.toString()
                )
                val body = buildJsonBody(params)
                httpClient.executeSignedPost(OkxConstants.Endpoints.SET_LEVERAGE, body)
            }
        } catch (e: Exception) {
            logger.warn { "OKX leverage warning: ${e.message}" }
        }
    }

    private suspend fun placeOrder(
        instId: String,
        side: String,
        posSide: String?,
        quantity: BigDecimal,
        takeProfit: Double?,
        stopLoss: Double?
    ): Result<String> {
        val mgnMode = resolveMarginMode()
        val body = buildJsonObject {
            put("instId", JsonPrimitive(instId))
            put("tdMode", JsonPrimitive(mgnMode))
            put("side", JsonPrimitive(side))
            put("ordType", JsonPrimitive(OkxConstants.ORDER_TYPE_MARKET))
            put("sz", JsonPrimitive(quantity.toPlainString()))
            posSide?.let { put("posSide", JsonPrimitive(it)) }

            if (takeProfit != null || stopLoss != null) {
                put("attachAlgoOrds", buildJsonArray {
                    add(buildJsonObject {
                        takeProfit?.let {
                            put("tpTriggerPx", JsonPrimitive(formatPrice(it)))
                            put("tpOrdPx", JsonPrimitive("-1"))
                        }
                        stopLoss?.let {
                            put("slTriggerPx", JsonPrimitive(formatPrice(it)))
                            put("slOrdPx", JsonPrimitive("-1"))
                        }
                    })
                })
            }
        }.toString()

        val response = httpClient.executeSignedPost(
            OkxConstants.Endpoints.PLACE_ORDER,
            body
        ) ?: return Result.failure(OkxException("No response from API"))

        val error = parseError(response)
        if (error != null) {
            logger.error { "OKX place order failed: ${error.message}. Response: $response" }
            return Result.failure(error)
        }

        val obj = json.parseToJsonElement(response).jsonObject
        val data = obj["data"]?.jsonArray ?: JsonArray(emptyList())
        val first = data.firstOrNull()?.jsonObject
            ?: return Result.failure(OkxException("OKX order response missing data"))

        val sCode = first["sCode"]?.jsonPrimitive?.content
        if (sCode != null && sCode != "0") {
            val sMsg = first["sMsg"]?.jsonPrimitive?.content ?: "Unknown error"
            val code = sCode.toIntOrNull() ?: -1
            logger.error { "OKX place order rejected: sCode=$sCode sMsg=$sMsg. Response: $response" }
            return Result.failure(OkxApiException(code, sMsg))
        }

        val ordId = first["ordId"]?.jsonPrimitive?.content
        return if (!ordId.isNullOrBlank()) {
            Result.success(ordId)
        } else {
            logger.error { "OKX order id missing. Response: $response" }
            Result.failure(OkxException("OKX order id missing"))
        }
    }

    private suspend fun placeTrailingStopAlgo(
        instId: String,
        side: String,
        posSide: String?,
        sz: BigDecimal,
        callbackRatio: Double,
        activePx: Double?
    ): Result<String> {
        val mgnMode = resolveMarginMode()
        // OKX callbackRatio is a decimal ratio: "0.01" = 1%. Range: 0.001–0.1
        // sz must be the actual position size — move_order_stop does not accept sz=0
        val body = buildJsonObject {
            put("instId", JsonPrimitive(instId))
            put("tdMode", JsonPrimitive(mgnMode))
            put("side", JsonPrimitive(side))
            put("ordType", JsonPrimitive("move_order_stop"))
            put("sz", JsonPrimitive(sz.toPlainString()))
            put("callbackRatio", JsonPrimitive(formatPrice(callbackRatio)))
            posSide?.let { put("posSide", JsonPrimitive(it)) }
            activePx?.let { put("activePx", JsonPrimitive(formatPrice(it))) }
        }.toString()
        logger.debug { "OKX trailing stop request: $body" }

        val response = httpClient.executeSignedPost(OkxConstants.Endpoints.ORDER_ALGO, body)
            ?: return Result.failure(OkxException("No response from API"))
        logger.debug { "OKX trailing stop response: $response" }

        val error = parseError(response)
        if (error != null) {
            logger.error { "OKX trailing stop algo failed: ${error.message}. Response: $response" }
            return Result.failure(error)
        }

        val obj = json.parseToJsonElement(response).jsonObject
        val data = obj["data"]?.jsonArray ?: JsonArray(emptyList())
        val first = data.firstOrNull()?.jsonObject
        val sCode = first?.get("sCode")?.jsonPrimitive?.content
        if (sCode != null && sCode != "0") {
            val sMsg = first?.get("sMsg")?.jsonPrimitive?.content ?: "Unknown error"
            logger.error { "OKX trailing algo rejected: sCode=$sCode sMsg=$sMsg" }
            return Result.failure(OkxApiException(sCode.toIntOrNull() ?: -1, sMsg))
        }

        val algoId = first?.get("algoId")?.jsonPrimitive?.content ?: ""
        logger.info { "OKX trailing stop placed: algoId=$algoId callbackRatio=$callbackRatio activePx=$activePx" }
        return Result.success(algoId)
    }

    private suspend fun getAccountConfig(): AccountConfig {
        val cached = cachedAccountConfig
        if (cached != null && (System.currentTimeMillis() - accountConfigCachedAt) < accountConfigTtlMs) {
            return cached
        }
        return try {
            val response = httpClient.executeSignedGet(OkxConstants.Endpoints.ACCOUNT_CONFIG)
                ?: return AccountConfig(posMode = "", acctLv = 0)

            val error = parseError(response)
            if (error != null) {
                return AccountConfig(posMode = "", acctLv = 0)
            }

            val obj = json.parseToJsonElement(response).jsonObject
            val data = obj["data"]?.jsonArray ?: JsonArray(emptyList())
            val first = data.firstOrNull()?.jsonObject
            val posMode = first?.get("posMode")?.jsonPrimitive?.content ?: ""
            val acctLv = first?.get("acctLv")?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            val config = AccountConfig(posMode = posMode, acctLv = acctLv)
            cachedAccountConfig = config
            accountConfigCachedAt = System.currentTimeMillis()
            logger.info { "OKX account config: acctLv=$acctLv, posMode=$posMode" }
            config
        } catch (_: Exception) {
            AccountConfig(posMode = "", acctLv = 0)
        }
    }

    private suspend fun getPositionMode(): String = getAccountConfig().posMode

    private suspend fun resolveMarginMode(): String {
        val config = getAccountConfig()
        // Simple mode (acctLv=1) only supports "cash" for spot, cannot trade SWAP
        // Single-currency margin (acctLv=2) supports cross/isolated
        // Multi-currency margin (acctLv=3) and Portfolio margin (acctLv=4) support cross/isolated
        return when (config.acctLv) {
            1 -> OkxConstants.MARGIN_MODE_CROSS // will likely fail, but Simple mode can't trade futures
            else -> preferredMarginMode
        }
    }

    override suspend fun getMarketPrice(symbol: String): Result<Double> {
        return try {
            val response = httpClient.executePublicGet(
                OkxConstants.Endpoints.MARKET_TICKER,
                mapOf("instId" to symbol)
            ) ?: return Result.failure(OkxException("No response from API"))

            val obj = json.parseToJsonElement(response).jsonObject
            val data = obj["data"]?.jsonArray ?: JsonArray(emptyList())
            val first = data.firstOrNull()?.jsonObject
                ?: return Result.failure(OkxException("Empty ticker data"))
            val price = first["last"]?.jsonPrimitive?.content?.toDoubleOrNull()
                ?: first["markPx"]?.jsonPrimitive?.content?.toDoubleOrNull()
                ?: return Result.failure(OkxException("Cannot parse price"))
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
            logger.warn { "OKX TP ignored (invalid vs price $referencePrice): $takeProfit" }
        }
        if (stopLoss != null && safeSl == null) {
            logger.warn { "OKX SL ignored (invalid vs price $referencePrice): $stopLoss" }
        }

        return safeTp to safeSl
    }

    private data class OkxPosition(
        val instId: String,
        val posSide: String?
    )

    private suspend fun getPositions(instId: String?): List<OkxPosition> {
        val params = if (instId != null) {
            mapOf("instId" to instId)
        } else {
            mapOf("instType" to "SWAP")
        }

        val response = httpClient.executeSignedGet(OkxConstants.Endpoints.ACCOUNT_POSITIONS, params)
            ?: return emptyList()

        val error = parseError(response)
        if (error != null) {
            return emptyList()
        }

        val obj = json.parseToJsonElement(response).jsonObject
        val data = obj["data"]?.jsonArray ?: JsonArray(emptyList())
        val positions = mutableListOf<OkxPosition>()

        for (item in data) {
            val pos = item.jsonObject
            val posId = pos["instId"]?.jsonPrimitive?.content ?: continue
            val posSize = pos["pos"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
            if (posSize == 0.0) continue

            val posSide = pos["posSide"]?.jsonPrimitive?.content
            positions.add(
                OkxPosition(
                    instId = posId,
                    posSide = posSide?.takeIf { it != "net" }
                )
            )
        }

        return positions
    }

    private suspend fun closePositionInternal(instId: String, posSide: String?) {
        val mgnMode = resolveMarginMode()
        val params = mutableMapOf(
            "instId" to instId,
            "mgnMode" to mgnMode
        )
        posSide?.let { params["posSide"] = it }
        val body = buildJsonBody(params)
        httpClient.executeSignedPost(OkxConstants.Endpoints.CLOSE_POSITION, body)
    }

    private suspend fun getPendingOrders(instId: String): List<String> {
        val response = httpClient.executeSignedGet(
            OkxConstants.Endpoints.ORDERS_PENDING,
            mapOf("instId" to instId)
        ) ?: return emptyList()

        val error = parseError(response)
        if (error != null) {
            return emptyList()
        }

        val obj = json.parseToJsonElement(response).jsonObject
        val data = obj["data"]?.jsonArray ?: JsonArray(emptyList())
        return data.mapNotNull { item ->
            item.jsonObject["ordId"]?.jsonPrimitive?.content
        }
    }

    private suspend fun getInstrumentInfo(instId: String): InstrumentInfo? {
        instrumentCache[instId]?.let { return it }
        return try {
            val response = httpClient.executePublicGet(
                OkxConstants.Endpoints.PUBLIC_INSTRUMENTS,
                mapOf("instType" to "SWAP", "instId" to instId)
            ) ?: return null

            val obj = json.parseToJsonElement(response).jsonObject
            val data = obj["data"]?.jsonArray ?: JsonArray(emptyList())
            val first = data.firstOrNull()?.jsonObject ?: return null

            val ctVal = first["ctVal"]?.jsonPrimitive?.content?.toBigDecimalOrNull() ?: return null
            val lotSz = first["lotSz"]?.jsonPrimitive?.content?.toBigDecimalOrNull() ?: BigDecimal.ONE
            val minSz = first["minSz"]?.jsonPrimitive?.content?.toBigDecimalOrNull() ?: BigDecimal.ONE

            val info = InstrumentInfo(ctVal = ctVal, lotSz = lotSz, minSz = minSz)
            instrumentCache[instId] = info
            logger.info { "OKX instrument $instId: ctVal=$ctVal, lotSz=$lotSz, minSz=$minSz" }
            info
        } catch (e: Exception) {
            logger.error(e) { "OKX failed to fetch instrument info for $instId" }
            null
        }
    }

    private fun buildJsonBody(params: Map<String, String>): String {
        val obj = buildJsonObject {
            params.forEach { (key, value) ->
                put(key, JsonPrimitive(value))
            }
        }
        return obj.toString()
    }

    private fun readBalanceField(obj: kotlinx.serialization.json.JsonObject?, key: String): Double? {
        return obj?.get(key)?.jsonPrimitive?.content?.toDoubleOrNull()
    }

    private fun calculateContractQuantity(amountUsdt: Double, price: Double, info: InstrumentInfo): BigDecimal {
        // Each contract is worth ctVal units of base asset
        // rawContracts = amountUsdt / (price * ctVal)
        val contractValue = info.ctVal.toDouble() * price
        val rawContracts = amountUsdt / contractValue

        // Round down to nearest lotSz multiple
        val contracts = BigDecimal.valueOf(rawContracts)
            .divide(info.lotSz, 0, RoundingMode.DOWN)
            .multiply(info.lotSz)

        // Enforce minimum
        return if (contracts < info.minSz) BigDecimal.ZERO else contracts
    }

    internal fun convertSymbol(symbol: String): String {
        val cleaned = symbol.replace("_", "-").replace("/", "-").uppercase()
        if (cleaned.contains("-") && cleaned.endsWith("-SWAP")) {
            return cleaned
        }

        if (cleaned.contains("-")) {
            return "$cleaned-SWAP"
        }

        val quote = listOf("USDT", "USDC").find { cleaned.endsWith(it) }
        return if (quote != null) {
            val base = cleaned.removeSuffix(quote)
            "$base-$quote-SWAP"
        } else {
            cleaned
        }
    }

    private fun parseError(response: String): OkxApiException? {
        return try {
            val obj = json.parseToJsonElement(response).jsonObject
            val code = obj["code"]?.jsonPrimitive?.content?.toIntOrNull()
            val msg = obj["msg"]?.jsonPrimitive?.content
            if (code != null && code != 0) {
                OkxApiException(code, msg ?: "Unknown error")
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}

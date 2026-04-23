package com.cheetrader.common.exchange.okx

import com.cheetrader.common.exchange.ExchangeFill
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
    private val testnet: Boolean = false,
    private val defaultLeverage: Int = 20,
    crossMargin: Boolean = true,
    private val logger: ExchangeLogger = NoOpLogger,
    baseUrlOverride: String? = null
) : ExchangeService {

    companion object {
        /**
         * Base symbols (e.g. `SHIB`) for tokens that Binance futures lists
         * with a `1000X` multiplier prefix but OKX lists per-unit. When one
         * of these is seen — either in a signal symbol like `1000SHIBUSDT`
         * or in an OKX instId like `SHIB-USDT-SWAP` — the adapter applies
         * a 1000× conversion at its boundary so the rest of the system can
         * treat signal-convention prices and quantities uniformly.
         *
         * Keep narrow. Add new tokens only when strategies actually trade
         * them, to avoid false positives on 3-4 letter tickers that happen
         * to share prefixes. Current list reflects what live Binance futures
         * offers as `1000XUSDT` as of 2026-04.
         */
        internal val OKX_SCALED_1000X_BASES = setOf(
            "SHIB", "PEPE", "BONK", "FLOKI", "LUNC", "SATS",
            "RATS", "CAT", "CHEEMS", "XEC", "X", "TOSHI"
        )
    }

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
            // 1000X catastrophe fix (2026-04-23): Binance-convention signal
            // prices (`1000SHIBUSDT @ 0.006`) must be divided by 1000 when
            // placed on OKX, which trades the underlying at `0.000006` per
            // SHIB. Applied uniformly to entry, SL, TP, multi-TP tiers, and
            // trailing trigger. Factor = 1.0 for non-scaled symbols so this
            // is a no-op for BTC/ETH/etc. See [signalScalingFactor] + the
            // [OKX_SCALED_1000X_BASES] whitelist.
            val scale = signalScalingFactor(signal.symbol)
            val signalEntry = signal.entryPrice / scale
            val signalSl = signal.stopLoss?.let { it / scale }
            val signalTp = signal.takeProfit?.let { it / scale }

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
                val envLabel = if (testnet) "OKX Demo" else "OKX"
                return Result.success(
                    OrderExecution(
                        signalId = signal.id,
                        status = ExecutionStatus.FAILED,
                        errorMessage = "Instrument $instId not found on $envLabel"
                    )
                )
            }

            val tradeAmount = balance * (balancePercent / 100.0) * defaultLeverage
            // calculateContractQuantity divides by price×ctVal → pass OKX-side
            // price so contracts = notional / (okxPrice × ctVal). With the
            // scale fix above, this now produces ~1000× more contracts for
            // a 1000X symbol, matching the intended notional.
            val quantity = calculateContractQuantity(tradeAmount, signalEntry, instrumentInfo)
            if (quantity <= BigDecimal.ZERO) {
                return Result.success(
                    OrderExecution(
                        signalId = signal.id,
                        status = ExecutionStatus.FAILED,
                        errorMessage = "Quantity is zero (trade amount too small for contract size)"
                    )
                )
            }

            // getMarketPrice scales its output back to signal convention —
            // pass signal.symbol (not instId) so the scaling factor resolves
            // correctly, then divide here for the OKX-native comparison in
            // sanitizeTpSl (which takes OKX-native SL/TP prices).
            val referencePriceSignal = getMarketPrice(signal.symbol).getOrNull() ?: signal.entryPrice
            val referencePriceOkx = referencePriceSignal / scale
            val (safeTp, safeSl) = sanitizeTpSl(
                isBuy = isBuy,
                referencePrice = referencePriceOkx,
                takeProfit = signalTp,
                stopLoss = signalSl
            )

            if (signal.stopLoss != null && safeSl == null) {
                return Result.success(OrderExecution(
                    signalId = signal.id,
                    status = ExecutionStatus.FAILED,
                    errorMessage = "SL ${signal.stopLoss} already breached (price=$referencePriceSignal) — order rejected"
                ))
            }

            // Multi-TP — asymmetric: the extractor seeds `tp1` from the passed
            // `safeTp` (already OKX-convention from the sanitise above), while
            // `tp2` and `tp3` come raw from signal metadata and need scaling.
            val rawMultiTp = extractMultiTpParams(signal.metadata, safeTp)
            val multiTp = rawMultiTp?.copy(
                tp2 = rawMultiTp.tp2 / scale,
                tp3 = rawMultiTp.tp3?.let { it / scale }
            )

            val orderResult = placeOrder(
                instId = instId,
                side = side,
                posSide = posSide,
                quantity = quantity,
                // SL is intentionally NOT embedded via attachAlgoOrds anymore —
                // see Patch 8 (2026-04-23). OKX silently dropped SL-only
                // attachAlgoOrds (when the strategy had no TP, e.g. Impulse
                // Breakout + trailing), so SL now always goes through a
                // dedicated placeSlAlgoOrder call after main order succeeds.
                // TP keeps the existing single-TP attachAlgoOrds path (which
                // works because OCO-with-both-sides is accepted reliably).
                takeProfit = if (multiTp != null) null else safeTp,
                stopLoss = null
            )

            if (orderResult.isSuccess) {
                val orderId = orderResult.getOrThrow()
                val conditionalErrors = mutableListOf<String>()
                val closeSide = if (isBuy) OkxConstants.SIDE_SELL else OkxConstants.SIDE_BUY

                // Package C.2 / Patch 8 — capture algo IDs for TP/SL/trailing so
                // the gateway can reconcile close events back to this execution.
                val capturedTpOrderIds = mutableListOf<String>()
                var capturedSlOrderId: String? = null
                var capturedTrailingOrderId: String? = null

                // Multi-stage take-profit orders (prices already scaled above)
                if (multiTp != null) {
                    val precision = quantity.scale()
                    val tp1Qty = quantity.multiply(BigDecimal.valueOf(multiTp.tp1Pct / 100.0))
                        .setScale(precision, RoundingMode.DOWN)
                    val tp2Qty = quantity.multiply(BigDecimal.valueOf(multiTp.tp2Pct / 100.0))
                        .setScale(precision, RoundingMode.DOWN)
                    val tp3Qty = quantity.subtract(tp1Qty).subtract(tp2Qty)

                    if (tp1Qty > BigDecimal.ZERO && tp2Qty > BigDecimal.ZERO) {
                        val tp1Result = placeTpAlgoOrder(instId, closeSide, posSide, multiTp.tp1, tp1Qty)
                        if (tp1Result.isFailure) conditionalErrors.add("TP1: ${tp1Result.exceptionOrNull()?.message}")
                        else tp1Result.getOrNull()?.takeIf { it.isNotBlank() }?.let(capturedTpOrderIds::add)

                        val tp2Result = placeTpAlgoOrder(instId, closeSide, posSide, multiTp.tp2, tp2Qty)
                        if (tp2Result.isFailure) conditionalErrors.add("TP2: ${tp2Result.exceptionOrNull()?.message}")
                        else tp2Result.getOrNull()?.takeIf { it.isNotBlank() }?.let(capturedTpOrderIds::add)

                        if (multiTp.tp3 != null && tp3Qty > BigDecimal.ZERO) {
                            val tp3Result = placeTpAlgoOrder(instId, closeSide, posSide, multiTp.tp3, tp3Qty)
                            if (tp3Result.isFailure) conditionalErrors.add("TP3: ${tp3Result.exceptionOrNull()?.message}")
                            else tp3Result.getOrNull()?.takeIf { it.isNotBlank() }?.let(capturedTpOrderIds::add)
                        }
                    } else {
                        // Fallback: quantities too small for splitting, use single TP
                        val tpResult = placeTpAlgoOrder(instId, closeSide, posSide, multiTp.tp1, quantity)
                        if (tpResult.isFailure) conditionalErrors.add("TP: ${tpResult.exceptionOrNull()?.message}")
                        else tpResult.getOrNull()?.takeIf { it.isNotBlank() }?.let(capturedTpOrderIds::add)
                    }
                }

                // Stop-loss — placed as its OWN algo order (not via attachAlgoOrds).
                // See Patch 8 (2026-04-23): OKX silently dropped SL-only
                // attachAlgoOrds, so SL was never placed for strategies without
                // TP (trailing-based). Now unified: both single-TP mode and
                // multi-TP mode place SL as a separate conditional algo.
                // Price is already scaled to OKX convention (signalSl = signal.stopLoss / scale).
                if (safeSl != null) {
                    val slResult = placeSlAlgoOrder(instId, closeSide, posSide, safeSl, quantity)
                    if (slResult.isFailure) {
                        conditionalErrors.add("SL: ${slResult.exceptionOrNull()?.message}")
                    } else {
                        capturedSlOrderId = slResult.getOrNull()?.takeIf { it.isNotBlank() }
                    }
                }

                // Trailing stop — callbackRatio is a percentage (no scaling),
                // activePx is a price (needs scaling).
                val trailingParams = extractTrailingParams(signal.metadata)
                if (trailingParams != null) {
                    val trailingResult = placeTrailingStopAlgoWithRecalc(
                        instId = instId,
                        side = closeSide,
                        posSide = posSide,
                        sz = quantity,
                        callbackRatio = trailingParams.deviation,
                        activePx = trailingParams.triggerPrice?.let { it / scale },
                        isBuy = isBuy
                    )
                    if (trailingResult.isFailure) {
                        conditionalErrors.add("Trailing: ${trailingResult.exceptionOrNull()?.message}")
                    } else {
                        capturedTrailingOrderId = trailingResult.getOrNull()?.takeIf { it.isNotBlank() }
                    }
                }

                val status = if (conditionalErrors.isNotEmpty()) {
                    logger.warn { "OKX conditional order errors: $conditionalErrors" }
                    ExecutionStatus.PARTIAL
                } else {
                    ExecutionStatus.SUCCESS
                }

                // Post-placement hotfix 2026-04-21: OKX POST /api/v5/trade/order
                // returns only the ordId — no avgPx / accFillSz. Follow up with GET.
                // Scale the exchange-native fill back to signal convention so the
                // OrderExecution surfaces prices/qty in the same units as the
                // original signal (consistent with Binance/Bybit adapters).
                val fill = fetchOrderFill(instId, orderId)

                Result.success(
                    OrderExecution(
                        signalId = signal.id,
                        status = status,
                        exchangeOrderId = orderId,
                        executedPrice = fill?.avgPx?.times(scale),
                        executedQuantity = fill?.accFillSz?.div(scale),
                        errorMessage = conditionalErrors.takeIf { it.isNotEmpty() }?.joinToString("; "),
                        slOrderId = capturedSlOrderId,
                        tpOrderIds = capturedTpOrderIds.toList(),
                        trailingOrderId = capturedTrailingOrderId
                    )
                )
            } else {
                // Propagate the real exception instead of hiding it inside a
                // Result.success(FAILED) wrapper (the "silent success" anti-pattern
                // fixed in Package A for all exchange adapters).
                Result.failure(orderResult.exceptionOrNull()
                    ?: OkxException("Order placement failed (no error detail)"))
            }
        } catch (e: Exception) {
            logger.error(e) { "OKX execute signal failed: ${signal.id}" }
            Result.failure(e)
        }
    }

    /**
     * Post-placement fill snapshot. OKX's `POST /api/v5/trade/order` only
     * returns `ordId`; avg fill price requires a follow-up `GET
     * /api/v5/trade/order?ordId=X&instId=Y`. Market orders usually have
     * `avgPx` populated within a few hundred ms; we do 3 retries × 200 ms
     * with exponential backoff, parsing the sentinel empty-strings OKX
     * uses for un-filled orders as null so the caller can distinguish
     * "fetch failed" from "no fills yet".
     *
     * All errors are swallowed — the fill data is best-effort UX info,
     * not a source of truth. If the call fails entirely, executedPrice
     * stays null and clients fall back to signal entry price.
     */
    private data class OrderFillInfo(val avgPx: Double?, val accFillSz: Double?)

    private suspend fun fetchOrderFill(instId: String, orderId: String): OrderFillInfo? {
        val delays = longArrayOf(150L, 300L, 600L)
        // Phase-1 hotfix (Bug B — 2026-04-21): `accFillSz` is in CONTRACTS,
        // same as `pos` in getOpenPositions. Normalize to base-asset units by
        // multiplying by ctVal so downstream PnL/fee math stays correct. See
        // getOpenPositions comment for the broader context.
        //
        // Note: fetchOrderFill returns BASE-ASSET (post-ctVal) quantities +
        // OKX-NATIVE prices — the caller (executeSignal) applies the 1000X
        // signalScalingFactor once when building OrderExecution. Don't scale
        // here to avoid double-application.
        val ctVal = getInstrumentInfo(instId)?.ctVal?.toDouble() ?: 1.0
        for ((attempt, d) in delays.withIndex()) {
            try {
                kotlinx.coroutines.delay(d)
                val response = httpClient.executeSignedGet(
                    "/api/v5/trade/order",
                    mapOf("ordId" to orderId, "instId" to instId)
                ) ?: continue

                val obj = json.parseToJsonElement(response).jsonObject
                if (obj["code"]?.jsonPrimitive?.content != "0") continue
                val data = obj["data"]?.jsonArray?.firstOrNull()?.jsonObject ?: continue

                // OKX uses "" as the sentinel for "not yet filled" — blank out
                // to null so downstream doesn't parse 0.0 and mis-report a
                // phantom $0 fill (same class of bug as BingX avgPrice="0"
                // fixed in Package A).
                val avgPx = data["avgPx"]?.jsonPrimitive?.content
                    ?.takeIf { it.isNotBlank() }
                    ?.toDoubleOrNull()
                    ?.takeIf { it > 0.0 }
                val accSzContracts = data["accFillSz"]?.jsonPrimitive?.content
                    ?.takeIf { it.isNotBlank() }
                    ?.toDoubleOrNull()
                    ?.takeIf { it > 0.0 }
                val accSz = accSzContracts?.times(ctVal)

                if (avgPx != null && accSz != null) {
                    return OrderFillInfo(avgPx, accSz)
                }
                logger.debug { "OKX order $orderId not yet filled (attempt ${attempt + 1}/${delays.size})" }
            } catch (e: Exception) {
                logger.warn { "OKX fetch-order-fill attempt ${attempt + 1} failed for $orderId: ${e.message}" }
            }
        }

        // Fallback — `/trade/order` never returned filled avgPx/accFillSz within the
        // retry window. Bug #2 (2026-04-22): on OKX demo BTC market orders,
        // settlement can take longer than our 1050 ms window, so OrderExecution
        // gets shipped with executedPrice=null/executedQuantity=null despite a
        // valid orderId. Downstream client guards (Package A ghost-trade check)
        // then REFUSE to create an ActiveTrade → the position is live on-exchange
        // but invisible on the dashboard. Fall back to `/account/positions` which
        // reports the settled avg-entry-price + current size for the live
        // position. This is authoritative: if there's no matching position, the
        // order truly didn't fill and we propagate null up so the guard correctly
        // rejects a ghost.
        //
        // withTimeoutOrNull caps the total fallback latency — if the exchange
        // is slow or the position endpoint blocks, we give up rather than
        // extending executeSignal's wall-clock indefinitely.
        logger.info { "OKX order $orderId: avgPx retries exhausted — falling back to getOpenPositions" }
        return try {
            kotlinx.coroutines.delay(500L)
            val positions = getOpenPositions().getOrNull().orEmpty()
            // Positions now come back in SIGNAL convention (scaled by
            // getOpenPositions). Un-scale here so OrderFillInfo stays
            // OKX-native to match the primary retry path above; the
            // caller applies scale once.
            val fallbackScale = scalingFactorFromInstId(instId)
            val pos = positions.firstOrNull { convertSymbol(it.symbol) == instId }
            if (pos == null || pos.size <= 0.0 || pos.entryPrice <= 0.0) {
                logger.warn { "OKX order $orderId has no matching position — executedPrice left null" }
                null
            } else {
                val okxPrice = pos.entryPrice / fallbackScale
                val okxQty = pos.size * fallbackScale  // base-asset, caller scales to signal
                logger.info {
                    "OKX order $orderId: using position snapshot avgPx=$okxPrice size=$okxQty (okx-native)"
                }
                OrderFillInfo(avgPx = okxPrice, accFillSz = okxQty)
            }
        } catch (e: Exception) {
            logger.warn { "OKX order $orderId fallback position lookup failed: ${e.message}" }
            null
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
        return getOpenPositions().map { it.size }
    }

    override suspend fun getOpenPositions(): Result<List<ExchangePosition>> {
        return try {
            val params = mapOf("instType" to "SWAP")
            val response = httpClient.executeSignedGet(OkxConstants.Endpoints.ACCOUNT_POSITIONS, params)
                ?: return Result.success(emptyList())

            val error = parseError(response)
            if (error != null) return Result.failure(error)

            val obj = json.parseToJsonElement(response).jsonObject
            val data = obj["data"]?.jsonArray ?: JsonArray(emptyList())
            // Phase-1 P0 hotfix (Bug B — 2026-04-21): OKX `pos` field is in
            // CONTRACTS, not base asset units. For BTC-USDT-SWAP one contract
            // represents 0.01 BTC (`ctVal` from /public/instruments). Other
            // exchanges return size in base asset directly. To keep the common
            // contract [ExchangePosition.size] = base-asset-units consistent
            // across adapters, we multiply by `ctVal` here before returning.
            // Without this fix, every downstream `notional = entry * size`
            // calc (fees, PnL, risk check) was 100× off for BTC-USDT-SWAP —
            // phantom fees then flipped unrealized +$4 into recorded −$104.
            // Uses the same cached `getInstrumentInfo` the entry path uses,
            // so hot positions need no extra network call.
            val result = mutableListOf<ExchangePosition>()
            for (item in data) {
                val pos = item.jsonObject
                val posSize = pos["pos"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
                if (posSize == 0.0) continue

                val instId = pos["instId"]?.jsonPrimitive?.content ?: continue
                // Fail-safe: if instrument lookup fails (rare — cached), use
                // 1.0 so the position is still visible rather than dropped.
                // PnL will be wrong in that case, but the user sees the trade.
                val ctVal = getInstrumentInfo(instId)?.ctVal?.toDouble() ?: 1.0

                val posSide = pos["posSide"]?.jsonPrimitive?.content ?: "net"
                val side = when {
                    posSide == "long" -> "LONG"
                    posSide == "short" -> "SHORT"
                    posSize > 0 -> "LONG"
                    else -> "SHORT"
                }

                // 1000X scaling: for memecoins that ship as `1000XXXUSDT` in
                // signal convention (Binance) but `XXX-USDT-SWAP` per-unit on
                // OKX, restore the factor so prices + qty match what the rest
                // of the system stores (ActiveTrade.entryPrice, signal.takeProfit).
                val scale = scalingFactorFromInstId(instId)
                val baseAssetSize = kotlin.math.abs(posSize) * ctVal
                result.add(
                    ExchangePosition(
                        // Denormalize OKX-native instId → universal symbol. Restores
                        // the `1000` prefix for whitelisted memecoins so ActiveTrade.symbol
                        // (stored as `1000SHIBUSDT`) matches the polled ExchangePosition.symbol
                        // in MainViewModel close-detection comparisons.
                        symbol = denormalizeSymbol(instId),
                        side = side,
                        // signal-convention qty = base-asset / scale (1000SHIB unit = 1000 SHIB).
                        size = baseAssetSize / scale,
                        entryPrice = (pos["avgPx"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0) * scale,
                        markPrice = (pos["markPx"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0) * scale,
                        // unrealizedPnl + margin are USDT-denominated, not price-scaled.
                        unrealizedPnl = pos["upl"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                        leverage = pos["lever"]?.jsonPrimitive?.content?.toDoubleOrNull()?.toInt() ?: 1,
                        margin = pos["margin"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                        liquidationPrice = (pos["liqPx"]?.jsonPrimitive?.content?.toDoubleOrNull())?.times(scale)
                    )
                )
            }
            Result.success(result)
        } catch (e: Exception) {
            logger.error(e) { "OKX get positions failed" }
            Result.failure(e)
        }
    }

    /**
     * Package D — `/api/v5/trade/fills-history` returns per-fill records with
     * `ordId`, side, fillPx, fillSz, ts and `execType`. OKX `execType == "C"`
     * is a close execution (reduce); we map it to [ExchangeFill.isReduceOnly].
     * The endpoint caps range at ~3 months; we use `begin` (inclusive) to filter.
     */
    override suspend fun getRecentFills(
        symbol: String,
        sinceMs: Long,
        limit: Int
    ): Result<List<ExchangeFill>> {
        return try {
            val instId = convertSymbol(symbol)
            // Phase-1 hotfix (Bug B — 2026-04-21): `fillSz` is in CONTRACTS
            // on OKX swaps. Normalize by ctVal so the returned
            // [ExchangeFill.quantity] matches the base-asset convention used
            // by every other exchange adapter (Binance / Bybit / BingX).
            // Package D's `resolveCloseReasonFromFills` compares quantities
            // across adapters — inconsistent units there would break close
            // attribution silently.
            val ctVal = getInstrumentInfo(instId)?.ctVal?.toDouble() ?: 1.0
            // 1000X scaling: for memecoins (`1000SHIBUSDT` in signal
            // convention), scale OKX-native fillPx + fillSz back so the
            // returned ExchangeFill matches the signal-convention numbers
            // MainViewModel.resolveClose compares against.
            val scale = signalScalingFactor(symbol)
            val params = mutableMapOf(
                "instType" to "SWAP",
                "instId" to instId,
                "begin" to sinceMs.coerceAtLeast(0L).toString(),
                "limit" to limit.coerceIn(1, 100).toString()
            )
            val response = httpClient.executeSignedGet("/api/v5/trade/fills-history", params)
                ?: return Result.success(emptyList())

            val obj = json.parseToJsonElement(response).jsonObject
            val code = obj["code"]?.jsonPrimitive?.content
            if (code != null && code != "0") {
                val msg = obj["msg"]?.jsonPrimitive?.content ?: "OKX error $code"
                return Result.failure(Exception("OKX fills-history failed: $msg"))
            }
            val data = obj["data"]?.jsonArray ?: JsonArray(emptyList())
            val fills = data.mapNotNull { item ->
                val rec = item.jsonObject
                val ordId = rec["ordId"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val side = rec["side"]?.jsonPrimitive?.content?.uppercase() ?: return@mapNotNull null
                val rawPrice = rec["fillPx"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@mapNotNull null
                val qtyContracts = rec["fillSz"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@mapNotNull null
                // base-asset qty = contracts × ctVal (OKX convention), then ÷ scale
                // to get signal-convention qty (1000SHIB unit = 1000 SHIB).
                val qty = (qtyContracts * ctVal) / scale
                val price = rawPrice * scale
                val time = rec["ts"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                // fillPnl is in USDT, not price-scaled.
                val pnl = rec["fillPnl"]?.jsonPrimitive?.content?.toDoubleOrNull()
                // OKX execType: "T" = taker open, "M" = maker open, "C" = close.
                // Everything other than explicit "C" is treated as "unknown" rather
                // than forced-to-false so callers don't over-filter.
                val execType = rec["execType"]?.jsonPrimitive?.content
                val reduceOnly: Boolean? = if (execType == "C") true else null
                ExchangeFill(
                    orderId = ordId,
                    side = side,
                    price = price,
                    quantity = qty,
                    time = time,
                    realizedPnl = pnl,
                    isReduceOnly = reduceOnly
                )
            }
            Result.success(fills)
        } catch (e: Exception) {
            logger.error(e) { "OKX get recent fills failed" }
            Result.failure(e)
        }
    }

    override suspend fun cancelAllOrders(symbol: String): Result<Unit> {
        return try {
            val instId = convertSymbol(symbol)

            // Cancel regular orders
            val pending = getPendingOrders(instId)
            if (pending.isNotEmpty()) {
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
            }

            // Cancel algo orders (TP, SL, trailing stop)
            cancelAlgoOrders(instId)

            Result.success(Unit)
        } catch (e: Exception) {
            logger.error(e) { "OKX cancel all orders failed: $symbol" }
            Result.failure(e)
        }
    }

    private suspend fun cancelAlgoOrders(instId: String) {
        try {
            val algoIds = getPendingAlgoOrders(instId)
            if (algoIds.isEmpty()) {
                logger.debug { "OKX cancelAlgoOrders: no pending algo orders for $instId" }
                return
            }

            logger.info { "OKX cancelAlgoOrders: attempting to cancel ${algoIds.size} algo orders for $instId" }
            var cancelled = 0
            val chunks = algoIds.chunked(20)
            for (chunk in chunks) {
                val body = buildJsonArray {
                    chunk.forEach { algoId ->
                        add(
                            buildJsonObject {
                                put("instId", JsonPrimitive(instId))
                                put("algoId", JsonPrimitive(algoId))
                            }
                        )
                    }
                }.toString()

                val response = httpClient.executeSignedPost(OkxConstants.Endpoints.CANCEL_ALGOS, body)
                if (response != null) {
                    val error = parseError(response)
                    if (error != null) {
                        logger.warn { "OKX cancelAlgoOrders: chunk failed — ${error.message}" }
                    } else {
                        cancelled += chunk.size
                    }
                }
            }
            logger.info { "OKX cancelAlgoOrders: cancelled $cancelled / ${algoIds.size} algo orders for $instId" }
        } catch (e: Exception) {
            logger.warn { "OKX cancelAlgoOrders: unexpected error — ${e.message}" }
        }
    }

    /**
     * Queries pending algo orders across all types we might have placed.
     *
     * Patch 6 (2026-04-23): iterate per ordType instead of a single
     * comma-joined filter. Live OKX-demo test showed SL+trailing orders
     * remaining after manual close; root cause was our filter missing
     * ordType="oco" — single-TP mode places SL+TP via `attachAlgoOrds`
     * with BOTH `slTriggerPx` and `tpTriggerPx`, which OKX materialises
     * as an OCO (one-cancels-other) algo. The previous
     * `"conditional,move_order_stop"` filter missed this entirely.
     *
     * Also added "trigger" for defensive coverage (not actively placed
     * by our code today, but a user migrating from another client might
     * have one; safer to include).
     *
     * One API call per type: ~200-400 ms × 4 = ~1 s in the pathological
     * case. Only runs on manual close, not a hot path.
     */
    private suspend fun getPendingAlgoOrders(instId: String): List<String> {
        val types = listOf("conditional", "oco", "trigger", "move_order_stop")
        val allIds = mutableListOf<String>()
        for (type in types) {
            val response = httpClient.executeSignedGet(
                OkxConstants.Endpoints.ORDERS_ALGO_PENDING,
                mapOf("instId" to instId, "ordType" to type)
            ) ?: continue

            val error = parseError(response)
            if (error != null) {
                logger.warn { "OKX getPendingAlgoOrders: type=$type failed — ${error.message}" }
                continue
            }

            val obj = json.parseToJsonElement(response).jsonObject
            val data = obj["data"]?.jsonArray ?: JsonArray(emptyList())
            val ids = data.mapNotNull { it.jsonObject["algoId"]?.jsonPrimitive?.content }
            if (ids.isNotEmpty()) {
                logger.info { "OKX getPendingAlgoOrders: found ${ids.size} $type orders for $instId" }
                allIds += ids
            }
        }
        return allIds
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

    private suspend fun placeTpAlgoOrder(
        instId: String,
        side: String,
        posSide: String?,
        triggerPrice: Double,
        sz: BigDecimal
    ): Result<String> {
        val mgnMode = resolveMarginMode()
        val body = buildJsonObject {
            put("instId", JsonPrimitive(instId))
            put("tdMode", JsonPrimitive(mgnMode))
            put("side", JsonPrimitive(side))
            put("ordType", JsonPrimitive("conditional"))
            put("sz", JsonPrimitive(sz.toPlainString()))
            put("tpTriggerPx", JsonPrimitive(formatPrice(triggerPrice)))
            put("tpOrdPx", JsonPrimitive("-1"))
            put("reduceOnly", JsonPrimitive(true))
            posSide?.let { put("posSide", JsonPrimitive(it)) }
        }.toString()
        logger.debug { "OKX TP algo request: $body" }

        val response = httpClient.executeSignedPost(OkxConstants.Endpoints.ORDER_ALGO, body)
            ?: return Result.failure(OkxException("No response from API"))
        logger.debug { "OKX TP algo response: $response" }

        val error = parseError(response)
        if (error != null) {
            logger.error { "OKX TP algo failed: ${error.message}. Response: $response" }
            return Result.failure(error)
        }

        val obj = json.parseToJsonElement(response).jsonObject
        val data = obj["data"]?.jsonArray ?: JsonArray(emptyList())
        val first = data.firstOrNull()?.jsonObject
        val sCode = first?.get("sCode")?.jsonPrimitive?.content
        if (sCode != null && sCode != "0") {
            val sMsg = first?.get("sMsg")?.jsonPrimitive?.content ?: "Unknown error"
            logger.error { "OKX TP algo rejected: sCode=$sCode sMsg=$sMsg" }
            return Result.failure(OkxApiException(sCode.toIntOrNull() ?: -1, sMsg))
        }

        val algoId = first?.get("algoId")?.jsonPrimitive?.content ?: ""
        logger.info { "OKX TP algo placed: algoId=$algoId triggerPrice=$triggerPrice sz=$sz" }
        return Result.success(algoId)
    }

    /**
     * Place a stop-loss as its own conditional algo order (Patch 8, 2026-04-23).
     *
     * Background: SL used to ride along inside `attachAlgoOrds` on the main
     * order. In practice OKX silently dropped that embedded SL whenever no
     * TP was paired with it (single-TP-less strategies such as Impulse
     * Breakout that use trailing instead of a fixed TP). Main order was
     * `sCode=0`, `attachAlgoOrdsInfo` held the actual algo-level rejection
     * which we never parsed → UI reported PLACED while exchange had no SL.
     *
     * Separating the call removes the silent-drop class entirely: any
     * algo rejection here propagates through `parseError` / `sCode` into
     * `conditionalErrors`, and the status drops to PARTIAL so clients
     * warn the user about an unprotected position.
     *
     * @param triggerPrice in OKX-native convention (caller is responsible for
     *                     the `signal.stopLoss / scale` conversion on 1000X
     *                     symbols).
     */
    private suspend fun placeSlAlgoOrder(
        instId: String,
        side: String,
        posSide: String?,
        triggerPrice: Double,
        sz: BigDecimal
    ): Result<String> {
        val mgnMode = resolveMarginMode()
        val body = buildJsonObject {
            put("instId", JsonPrimitive(instId))
            put("tdMode", JsonPrimitive(mgnMode))
            put("side", JsonPrimitive(side))
            put("ordType", JsonPrimitive("conditional"))
            put("sz", JsonPrimitive(sz.toPlainString()))
            put("slTriggerPx", JsonPrimitive(formatPrice(triggerPrice)))
            put("slOrdPx", JsonPrimitive("-1")) // -1 = market close when triggered
            put("reduceOnly", JsonPrimitive(true))
            posSide?.let { put("posSide", JsonPrimitive(it)) }
        }.toString()
        logger.debug { "OKX SL algo request: $body" }

        val response = httpClient.executeSignedPost(OkxConstants.Endpoints.ORDER_ALGO, body)
            ?: return Result.failure(OkxException("No response from API"))
        logger.debug { "OKX SL algo response: $response" }

        val error = parseError(response)
        if (error != null) {
            logger.error { "OKX SL algo failed: ${error.message}. Response: $response" }
            return Result.failure(error)
        }

        val obj = json.parseToJsonElement(response).jsonObject
        val data = obj["data"]?.jsonArray ?: JsonArray(emptyList())
        val first = data.firstOrNull()?.jsonObject
        val sCode = first?.get("sCode")?.jsonPrimitive?.content
        if (sCode != null && sCode != "0") {
            val sMsg = first?.get("sMsg")?.jsonPrimitive?.content ?: "Unknown error"
            logger.error { "OKX SL algo rejected: sCode=$sCode sMsg=$sMsg" }
            return Result.failure(OkxApiException(sCode.toIntOrNull() ?: -1, sMsg))
        }

        val algoId = first?.get("algoId")?.jsonPrimitive?.content ?: ""
        logger.info { "OKX SL algo placed: algoId=$algoId triggerPrice=$triggerPrice sz=$sz" }
        return Result.success(algoId)
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

    private suspend fun placeTrailingStopAlgoWithRecalc(
        instId: String,
        side: String,
        posSide: String?,
        sz: BigDecimal,
        callbackRatio: Double,
        activePx: Double?,
        isBuy: Boolean
    ): Result<String> {
        // Attempt 1: original activePx
        val result1 = placeTrailingStopAlgo(instId, side, posSide, sz, callbackRatio, activePx)
        if (result1.isSuccess) return result1

        // Attempt 2: recalculate activePx from current market price
        logger.warn { "OKX trailing failed, recalculating activePx from market price..." }
        kotlinx.coroutines.delay(500)
        val marketPrice = getMarketPrice(instId).getOrNull()
        if (marketPrice != null) {
            val offset = marketPrice * 0.001
            val recalculated = if (isBuy) marketPrice + offset else marketPrice - offset
            val result2 = placeTrailingStopAlgo(instId, side, posSide, sz, callbackRatio, recalculated)
            if (result2.isSuccess) return result2
        }

        // Attempt 3: no activePx — activate immediately
        logger.warn { "OKX trailing recalc still rejected, placing without activePx" }
        kotlinx.coroutines.delay(500)
        return placeTrailingStopAlgo(instId, side, posSide, sz, callbackRatio, null)
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
            // Callers pass signal-convention (`1000SHIBUSDT` or `BTCUSDT`) —
            // convert to OKX instId before the request. This fixes a
            // pre-existing latent bug where getMarketPrice failed silently on
            // OKX for every call with non-OKX-format symbol (returning null
            // and forcing fallbacks to signal.entryPrice in MainViewModel).
            val instId = convertSymbol(symbol)
            val scale = signalScalingFactor(symbol)
            val response = httpClient.executePublicGet(
                OkxConstants.Endpoints.MARKET_TICKER,
                mapOf("instId" to instId)
            ) ?: return Result.failure(OkxException("No response from API"))

            val obj = json.parseToJsonElement(response).jsonObject
            val data = obj["data"]?.jsonArray ?: JsonArray(emptyList())
            val first = data.firstOrNull()?.jsonObject
                ?: return Result.failure(OkxException("Empty ticker data"))
            val exchangePrice = first["last"]?.jsonPrimitive?.content?.toDoubleOrNull()
                ?: first["markPx"]?.jsonPrimitive?.content?.toDoubleOrNull()
                ?: return Result.failure(OkxException("Cannot parse price"))
            // Scale OKX-native price back to signal convention so the caller
            // sees the same number units as `signal.entryPrice`.
            Result.success(exchangePrice * scale)
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
        val response = httpClient.executeSignedPost(OkxConstants.Endpoints.CLOSE_POSITION, body)
            ?: throw OkxException("No response from OKX close position API")

        val error = parseError(response)
        if (error != null) {
            throw error
        }

        // Also check per-order sCode inside data[]
        val obj = json.parseToJsonElement(response).jsonObject
        val data = obj["data"]?.jsonArray ?: JsonArray(emptyList())
        val first = data.firstOrNull()?.jsonObject
        val sCode = first?.get("sCode")?.jsonPrimitive?.content
        if (sCode != null && sCode != "0") {
            val sMsg = first?.get("sMsg")?.jsonPrimitive?.content ?: "Unknown error"
            throw OkxApiException(sCode.toIntOrNull() ?: -1, sMsg)
        }

        logger.info { "OKX position closed: instId=$instId posSide=$posSide" }
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

    /**
     * Denormalizes OKX instId (`BTC-USDT-SWAP`) back into the universal symbol
     * format (`BTCUSDT`) used in signals + ActiveTrade + UI. Fixes the
     * 2026-04-21 live regression where OKX positions displayed as "BTC-"
     * (truncated OKX-native format) instead of "BTCUSDT" on the dashboard.
     *
     * For memecoins that Binance lists with a 1000x multiplier prefix
     * (`1000SHIBUSDT`) but OKX lists per-unit (`SHIB-USDT-SWAP`), the prefix
     * is RESTORED from [OKX_SCALED_1000X_BASES] so [ExchangePosition.symbol]
     * matches the [ActiveTrade.symbol] stored from the original signal.
     * Without this, poll-based close matching in MainViewModel compared
     * `1000SHIBUSDT` (ActiveTrade) vs `SHIBUSDT` (polled position) → mismatch
     * → false-positive orphan detection.
     */
    internal fun denormalizeSymbol(instId: String): String {
        if (!instId.contains("-")) return instId
        val parts = instId.removeSuffix("-SWAP").split("-")
        if (parts.size < 2) return instId
        val base = parts[0].uppercase()
        val quote = parts[1]
        val prefix = if (base in OKX_SCALED_1000X_BASES) "1000" else ""
        return "$prefix$base$quote"
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
            var base = cleaned.removeSuffix(quote)
            // OKX doesn't use the 1000-prefix convention (e.g. 1000BONKUSDT → BONK-USDT-SWAP)
            if (base.startsWith("1000")) {
                base = base.removePrefix("1000")
            }
            "$base-$quote-SWAP"
        } else {
            cleaned
        }
    }

    /**
     * Returns the multiplier between signal-convention prices and OKX-native
     * prices for [signalSymbol]. `1000SHIBUSDT` → 1000.0, `BTCUSDT` → 1.0.
     *
     * Rule: every price flowing into the OKX API is `signalPrice / factor`,
     * every price flowing out is `exchangePrice * factor`. Quantities flow
     * the opposite way (signal→exchange multiplies by factor, exchange→signal
     * divides). Applied at the adapter boundary so the rest of the system
     * can treat signal-convention prices/qty uniformly across all 4 exchanges.
     */
    internal fun signalScalingFactor(signalSymbol: String): Double {
        val cleaned = signalSymbol.uppercase().replace("_", "-").replace("-", "")
        val quote = listOf("USDT", "USDC", "BUSD").find { cleaned.endsWith(it) } ?: return 1.0
        val base = cleaned.removeSuffix(quote)
        return when {
            // Narrow allow-list of known Binance-futures prefix patterns to
            // avoid false-positives on numeric-prefix tickers like 1INCHUSDT.
            base.startsWith("10000") -> 10_000.0
            base.startsWith("1000") -> 1000.0
            base.startsWith("1M") -> 1_000_000.0
            else -> 1.0
        }
    }

    /**
     * Factor derived from an OKX instId when the caller doesn't have the
     * original signal symbol (e.g. `getOpenPositions` iterates all positions
     * without per-call context). Uses the [OKX_SCALED_1000X_BASES] whitelist
     * — kept narrow so non-memecoin tokens that happen to share 3-4 letter
     * bases aren't accidentally scaled.
     */
    private fun scalingFactorFromInstId(instId: String): Double {
        val base = instId.substringBefore("-").uppercase()
        return if (base in OKX_SCALED_1000X_BASES) 1000.0 else 1.0
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

package com.cheetrader.common.exchange.bingx

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

    companion object {
        /**
         * Base symbols for tokens that Binance futures lists with a `1000X`
         * multiplier prefix (`1000SHIBUSDT`) but BingX lists per-unit
         * (`SHIB-USDT`). The adapter applies a 1000× conversion at its
         * boundary for these — same pattern as OKX. Previously (pre-2026-04-23)
         * BingX silently built `1000SHIB-USDT` instrument which doesn't
         * exist on BingX → order rejection, dead trade.
         *
         * Keep aligned with [com.cheetrader.common.exchange.okx.OkxExchangeService.OKX_SCALED_1000X_BASES].
         */
        internal val BINGX_SCALED_1000X_BASES = setOf(
            "SHIB", "PEPE", "BONK", "FLOKI", "LUNC", "SATS",
            "RATS", "CAT", "CHEEMS", "XEC", "X", "TOSHI"
        )
    }

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
            // 1000X catastrophe fix (2026-04-23): Binance-convention signal
            // prices (`1000SHIBUSDT @ 0.006`) must be divided by 1000 when
            // placed on BingX, which trades the underlying at `0.000006` per
            // SHIB. Factor = 1.0 for non-scaled symbols so no-op for BTC/ETH.
            val scale = signalScalingFactor(signal.symbol)
            val signalEntry = signal.entryPrice / scale
            val signalSl = signal.stopLoss?.let { it / scale }
            val signalTp = signal.takeProfit?.let { it / scale }

            val isBuy = signal.type == SignalType.LONG
            val side = if (isBuy) BingXConstants.SIDE_BUY else BingXConstants.SIDE_SELL
            val positionSide = if (isBuy) BingXConstants.POSITION_SIDE_LONG else BingXConstants.POSITION_SIDE_SHORT

            setupPosition(symbol, positionSide)

            val tradeAmount = balance * (balancePercent / 100.0) * defaultLeverage
            // Pass BingX-side price so quantity = notional / bingxPrice yields
            // ~1000× more base-asset units for a 1000X symbol — matches intended
            // notional on a per-unit exchange.
            val quantity = calculateQuantity(tradeAmount, signalEntry, symbol)

            // Sanitize TP/SL — reference price fetched via getMarketPrice, which
            // scales output back to signal convention. Divide by scale to keep
            // comparison in BingX-native space (same space as safeTp/safeSl).
            val referencePriceSignal = getMarketPrice(signal.symbol).getOrNull() ?: signal.entryPrice
            val referencePriceBingX = referencePriceSignal / scale
            val (safeTp, safeSl) = sanitizeTpSl(
                isBuy = isBuy,
                referencePrice = referencePriceBingX,
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

            // Multi-TP — asymmetric: extractor seeds `tp1` from the passed
            // `safeTp` (already BingX-convention), while `tp2` and `tp3`
            // come raw from signal metadata and need explicit scaling.
            val rawMultiTp = extractMultiTpParams(signal.metadata, safeTp)
            val multiTp = rawMultiTp?.copy(
                tp2 = rawMultiTp.tp2 / scale,
                tp3 = rawMultiTp.tp3?.let { it / scale }
            )

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

                // Track placement outcomes so the client can warn the user about
                // unprotected positions. On BingX, SL (and single TP) are embedded
                // in the main order — if `placeOrder` succeeded and had SL config,
                // the SL is assumed placed together with the entry. We cannot
                // independently verify embedded-order placement without a separate
                // open-orders query, which is out of scope for this change.
                //
                // `hasTakeProfit` starts optimistic and is flipped to `false` if
                // any explicit TP order placement fails (multi-TP path or trailing).
                val hasStopLoss = true
                val requestedSingleTp = multiTp == null && safeTp != null
                val hasMultiTp = multiTp != null
                val trailingParams = extractTrailingParams(signal.metadata)
                var hasTakeProfit = true

                // Package C.2 — capture exchange-side order IDs so the gateway can
                // reconcile close events back to this execution. SL is always embedded
                // in the main entry on BingX (no separate ID).
                val capturedTpOrderIds = mutableListOf<String>()
                var capturedTrailingOrderId: String? = null

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
                        if (tp1Result.isFailure) {
                            conditionalErrors.add("TP1: ${tp1Result.exceptionOrNull()?.message}")
                            hasTakeProfit = false
                        } else {
                            tp1Result.getOrNull()?.takeIf { it.isNotBlank() }?.let(capturedTpOrderIds::add)
                        }

                        val tp2Result = placeTpAlgoOrder(symbol, closeSide, positionSide, multiTp.tp2, tp2Qty)
                        if (tp2Result.isFailure) {
                            conditionalErrors.add("TP2: ${tp2Result.exceptionOrNull()?.message}")
                            hasTakeProfit = false
                        } else {
                            tp2Result.getOrNull()?.takeIf { it.isNotBlank() }?.let(capturedTpOrderIds::add)
                        }

                        if (multiTp.tp3 != null && tp3Qty > BigDecimal.ZERO) {
                            val tp3Result = placeTpAlgoOrder(symbol, closeSide, positionSide, multiTp.tp3, tp3Qty)
                            if (tp3Result.isFailure) {
                                conditionalErrors.add("TP3: ${tp3Result.exceptionOrNull()?.message}")
                                hasTakeProfit = false
                            } else {
                                tp3Result.getOrNull()?.takeIf { it.isNotBlank() }?.let(capturedTpOrderIds::add)
                            }
                        }
                    } else {
                        // Fallback: quantities too small, place single TP
                        val tpResult = placeTpAlgoOrder(symbol, closeSide, positionSide, multiTp.tp1, quantity)
                        if (tpResult.isFailure) {
                            conditionalErrors.add("TP: ${tpResult.exceptionOrNull()?.message}")
                            hasTakeProfit = false
                        } else {
                            tpResult.getOrNull()?.takeIf { it.isNotBlank() }?.let(capturedTpOrderIds::add)
                        }
                    }

                    // TODO: Break-even after TP1/TP2 requires a PositionMonitorService that watches
                    // for TP fill events and modifies the SL order. Metadata keys "breakEvenAfterTp1"
                    // and "breakEvenAfterTp2" are available in signal.metadata for this purpose.

                    // TODO: Early exit based on oppositeScore requires continuous candle-by-candle
                    // evaluation. Metadata keys "oppositeScore"/"earlyExitScoreThreshold" in
                    // signal.metadata are snapshots at signal time.
                }

                // Trailing stop from metadata (parsed once above)
                if (trailingParams != null) {
                    val trailingSide = if (isBuy) BingXConstants.SIDE_SELL else BingXConstants.SIDE_BUY
                    val trailingResult = placeTrailingStopWithRecalc(
                        symbol = symbol,
                        side = trailingSide,
                        positionSide = positionSide,
                        quantity = quantity,
                        priceRate = trailingParams.deviation, // decimal: 0.01 = 1% (no scaling, it's a ratio)
                        activationPrice = trailingParams.triggerPrice?.let { it / scale },
                        isBuy = isBuy
                    )
                    if (trailingResult.isFailure) {
                        conditionalErrors.add("Trailing: ${trailingResult.exceptionOrNull()?.message}")
                        // Trailing replaces TP as the profit-exit — if it failed and
                        // no TP was placed, the position has no profit-exit.
                        if (!requestedSingleTp && !hasMultiTp) hasTakeProfit = false
                    } else {
                        capturedTrailingOrderId = trailingResult.getOrNull()?.takeIf { it.isNotBlank() }
                    }
                }

                // Verify the position actually opened on the exchange. BingX occasionally
                // accepts an order (returns success + orderId) without the position
                // materialising — we must not mark such a signal as SUCCESS/PARTIAL or
                // the client will render a ghost ActiveTrade that doesn't exist on-exchange.
                val opened = verifyPositionOpened(symbol, positionSide)
                if (!opened) {
                    logger.error {
                        "BingX order ${orderData.orderId} accepted but position not found " +
                                "after verify (symbol=$symbol side=$positionSide) — treating as FAILED"
                    }
                    return Result.failure(BingXException(
                        "Order ${orderData.orderId} accepted by BingX but position did not appear on exchange"
                    ))
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
                    // Filter out BingX's "0" placeholder avgPrice/qty — these arrive when
                    // a market order hasn't settled its fill price yet. Surfacing 0.0 as
                    // "executed price" breaks PnL and dashboard math downstream.
                    //
                    // Scale BingX-native price back to signal convention; scale qty the
                    // opposite way so downstream sees numbers in `1000SHIBUSDT` space.
                    executedPrice = orderData.price?.toDoubleOrNull()?.takeIf { it > 0.0 }?.times(scale),
                    executedQuantity = orderData.quantity?.toDoubleOrNull()?.takeIf { it > 0.0 }?.div(scale),
                    errorMessage = conditionalErrors.takeIf { it.isNotEmpty() }?.joinToString("; "),
                    hasExchangeStopLoss = hasStopLoss,
                    hasExchangeTakeProfit = hasTakeProfit,
                    // slOrderId stays null: BingX embeds SL in the main entry order.
                    slOrderId = null,
                    tpOrderIds = capturedTpOrderIds.toList(),
                    trailingOrderId = capturedTrailingOrderId
                ))
            } else {
                // Propagate the real exception so the caller's Result.onFailure /
                // getOrElse branch actually fires. Wrapping failure inside
                // Result.success(FAILED) silently swallowed every BingX error and
                // caused the "silent success" class of bugs.
                Result.failure(result.exceptionOrNull()
                    ?: BingXException("Order placement failed (no error detail)"))
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to execute signal ${signal.id}" }
            Result.failure(e)
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

                    // Restore signal convention:
                    //  - symbol: `SHIB-USDT` → `1000SHIBUSDT` (match ActiveTrade)
                    //  - price/qty: 1000× factor for whitelisted memecoins
                    //  - PnL/margin: USDT-denominated, NOT price-scaled
                    val bingxSymbol = posObj["symbol"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val universalSymbol = denormalizeSymbol(bingxSymbol)
                    val scale = signalScalingFactor(universalSymbol)
                    ExchangePosition(
                        symbol = universalSymbol,
                        side = side,
                        size = kotlin.math.abs(positionAmt) / scale,
                        entryPrice = (posObj["avgPrice"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0) * scale,
                        markPrice = (posObj["markPrice"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0) * scale,
                        unrealizedPnl = posObj["unrealizedProfit"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                        leverage = posObj["leverage"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1,
                        margin = posObj["initialMargin"]?.jsonPrimitive?.content?.toDoubleOrNull()
                            ?: posObj["margin"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                        liquidationPrice = posObj["liquidationPrice"]?.jsonPrimitive?.content?.toDoubleOrNull()?.times(scale)
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

    /**
     * Package D — `/openApi/swap/v2/trade/fillHistory` returns the user's
     * recent fills with `orderId`, side, price, volume and `tradingTime`.
     * BingX doesn't expose a `reduceOnly` flag on fills; we pass `null` and
     * let the caller match by orderId alone. `realisedPNL` is present per
     * fill when it closed a position.
     */
    override suspend fun getRecentFills(
        symbol: String,
        sinceMs: Long,
        limit: Int
    ): Result<List<ExchangeFill>> {
        return try {
            val bingxSymbol = convertSymbol(symbol)
            // 1000X scaling: for memecoins (`1000SHIBUSDT` signal convention)
            // scale BingX-native fill price/qty back so MainViewModel's
            // resolveClose compares against consistent numbers.
            val scale = signalScalingFactor(symbol)
            val params = mutableMapOf(
                "symbol" to bingxSymbol,
                "startTs" to sinceMs.coerceAtLeast(0L).toString(),
                "endTs" to System.currentTimeMillis().toString(),
                "limit" to limit.coerceIn(1, 1000).toString()
            )
            val response = httpClient.executeSignedGet("/openApi/swap/v2/trade/fillHistory", params)
                ?: return Result.success(emptyList())

            val root = json.parseToJsonElement(response).jsonObject
            val code = root["code"]?.jsonPrimitive?.intOrNull
            if (code != null && code != 0) {
                val msg = root["msg"]?.jsonPrimitive?.content ?: "BingX error $code"
                return Result.failure(Exception("BingX fillHistory failed: $msg"))
            }
            // BingX wraps results under data.fill_history (v2).
            val data = root["data"]?.jsonObject
            val array = data?.get("fill_history")?.jsonArray
                ?: data?.get("fillHistoryOrders")?.jsonArray
                ?: root["data"] as? JsonArray
                ?: return Result.success(emptyList())

            val fills = array.mapNotNull { item ->
                val rec = item.jsonObject
                val orderId = rec["orderId"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val side = rec["side"]?.jsonPrimitive?.content?.uppercase() ?: return@mapNotNull null
                val rawPrice = rec["price"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@mapNotNull null
                val rawQty = rec["volume"]?.jsonPrimitive?.content?.toDoubleOrNull()
                    ?: rec["qty"]?.jsonPrimitive?.content?.toDoubleOrNull()
                    ?: return@mapNotNull null
                val time = rec["filledTm"]?.jsonPrimitive?.content?.toLongOrNull()
                    ?: rec["tradingTime"]?.jsonPrimitive?.content?.toLongOrNull()
                    ?: 0L
                // realisedPNL is USDT-denominated, not price-scaled.
                val pnl = rec["realisedPNL"]?.jsonPrimitive?.content?.toDoubleOrNull()
                    ?: rec["profit"]?.jsonPrimitive?.content?.toDoubleOrNull()
                ExchangeFill(
                    orderId = orderId,
                    side = side,
                    price = rawPrice * scale,
                    quantity = rawQty / scale,
                    time = time,
                    realizedPnl = pnl,
                    isReduceOnly = null
                )
            }
            Result.success(fills)
        } catch (e: Exception) {
            logger.error(e) { "BingX get recent fills failed" }
            Result.failure(e)
        }
    }

    // ===== Private Methods =====

    /**
     * Verify that the position is actually visible on the exchange after placement.
     * BingX V2 API can return SUCCESS + orderId while the position fails to
     * materialise (reasons vary: margin check, symbol config, stale leverage setting).
     * Without this check the client happily tracks a ghost ActiveTrade that does
     * not exist on-exchange. We retry twice with a 1s gap to tolerate settlement delay.
     */
    private suspend fun verifyPositionOpened(symbol: String, positionSide: String): Boolean {
        // `symbol` arrives as BingX-native (`SHIB-USDT`), while getOpenPositions
        // now returns signal-convention (`1000SHIBUSDT`). Normalize both sides
        // to a canonical form before comparing.
        val expectedUniversal = denormalizeSymbol(symbol)
        val maxAttempts = 2
        repeat(maxAttempts) {
            delay(1000)
            val positions = getOpenPositions().getOrNull()
            if (positions != null) {
                val match = positions.any { pos ->
                    pos.symbol.equals(expectedUniversal, ignoreCase = true) &&
                        pos.side.equals(positionSide, ignoreCase = true) &&
                        pos.size > 0.0
                }
                if (match) return true
            }
        }
        return false
    }

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
        priceRate: Double,        // decimal value: 0.01 = 1% (BingX valid range: 0.001–0.1)
        activationPrice: Double?
    ): Result<String> {
        if (priceRate > 0.1) {
            logger.warn { "BingX priceRate $priceRate exceeds max 0.1 (10%), clamping to 0.1" }
        }
        val clampedRate = priceRate.coerceIn(0.001, 0.1)

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
            logger.info { "BingX trailing stop placed: orderId=$orderId priceRate=$clampedRate (${clampedRate * 100}%)" }
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
            // Callers pass either signal-convention (`1000SHIBUSDT`, `BTCUSDT`)
            // or already-converted BingX format (`BTC-USDT`). Normalize so the
            // request always uses BingX's expected format, fixing a pre-existing
            // latent bug where calls with `BTCUSDT` (no dash) were rejected by
            // BingX silently.
            val bingxSymbol = convertSymbol(symbol)
            val scale = signalScalingFactor(symbol)
            val response = httpClient.executeSignedGet(
                "/openApi/swap/v2/quote/price",
                mutableMapOf("symbol" to bingxSymbol)
            ) ?: return Result.failure(BingXException("No response from API"))

            val jsonResponse = json.parseToJsonElement(response).jsonObject
            val code = jsonResponse["code"]?.jsonPrimitive?.intOrNull
            if (code == BingXConstants.ErrorCodes.SUCCESS) {
                val exchangePrice = jsonResponse["data"]?.jsonObject?.get("price")?.jsonPrimitive?.content?.toDoubleOrNull()
                    ?: return Result.failure(BingXException("Cannot parse price"))
                // Scale BingX-native price back to signal convention (no-op
                // when caller already used BingX format since scale then = 1.0
                // via the whitelist; memecoin SHIB-USDT → 1000.0 matches).
                Result.success(exchangePrice * scale)
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
        return safeTp to safeSl
    }

    internal fun convertSymbol(symbol: String): String {
        if (symbol.contains("-")) return symbol

        val quote = listOf("USDT", "USDC", "BUSD").find { symbol.endsWith(it) }
        return if (quote != null) {
            var base = symbol.removeSuffix(quote)
            // BingX lists memecoins per-unit (`SHIB-USDT`), not with the
            // `1000X` multiplier prefix Binance uses. Strip so signals
            // for `1000SHIBUSDT` land on the valid BingX instrument.
            // Paired with `signalScalingFactor` which converts prices/qty.
            base = stripScalingPrefix(base)
            "$base-$quote"
        } else {
            symbol
        }
    }

    /**
     * Returns the 1000× multiplier between signal-convention prices and
     * BingX-native prices for [symbol]. Handles both signal format
     * (`1000SHIBUSDT` → 1000.0) and BingX-native format with whitelist
     * fallback (`SHIB-USDT` → 1000.0 when SHIB is a known scaled base).
     *
     * Rule: every price flowing to the BingX API is `signalPrice / factor`;
     * every price flowing back out is `exchangePrice * factor`. Quantities
     * flow the opposite way. Applied at the adapter boundary so the rest
     * of the system treats signal-convention values uniformly.
     */
    internal fun signalScalingFactor(symbol: String): Double {
        val cleaned = symbol.uppercase().replace("_", "-").replace("-", "")
        val quote = listOf("USDT", "USDC", "BUSD").find { cleaned.endsWith(it) } ?: return 1.0
        var base = cleaned.removeSuffix(quote)
        // Prefix-based parse first (signal format)
        val byPrefix = when {
            base.startsWith("10000") -> 10_000.0.also { base = base.removePrefix("10000") }
            base.startsWith("1000") -> 1000.0.also { base = base.removePrefix("1000") }
            base.startsWith("1M") -> 1_000_000.0.also { base = base.removePrefix("1M") }
            else -> null
        }
        if (byPrefix != null) return byPrefix
        // No prefix — fall back to whitelist (handles BingX-format input)
        return if (base in BINGX_SCALED_1000X_BASES) 1000.0 else 1.0
    }

    /**
     * Strips known multiplier prefix from a base symbol. `1000SHIB` → `SHIB`,
     * `10000ELON` → `ELON`, `SHIB` → `SHIB`. Used inside [convertSymbol] to
     * build the correct BingX instrument name.
     */
    private fun stripScalingPrefix(base: String): String = when {
        base.startsWith("10000") -> base.removePrefix("10000")
        base.startsWith("1000") -> base.removePrefix("1000")
        base.startsWith("1M") -> base.removePrefix("1M")
        else -> base
    }

    /**
     * Converts BingX-native `SHIB-USDT` back to universal signal convention
     * `1000SHIBUSDT` (restoring the `1000` prefix for whitelisted memecoins).
     * Used in [getOpenPositions] so the polled [ExchangePosition.symbol]
     * matches [ActiveTrade.symbol] stored from the original signal.
     */
    internal fun denormalizeSymbol(bingxSymbol: String): String {
        if (!bingxSymbol.contains("-")) return bingxSymbol
        val parts = bingxSymbol.split("-")
        if (parts.size < 2) return bingxSymbol
        val base = parts[0].uppercase()
        val quote = parts[1]
        val prefix = if (base in BINGX_SCALED_1000X_BASES) "1000" else ""
        return "$prefix$base$quote"
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

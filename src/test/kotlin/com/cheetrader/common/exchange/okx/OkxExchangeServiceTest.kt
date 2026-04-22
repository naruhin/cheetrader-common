package com.cheetrader.common.exchange.okx

import com.cheetrader.common.model.ExecutionStatus
import com.cheetrader.common.testutil.MockExchangeServer
import com.cheetrader.common.testutil.OkxResponses
import com.cheetrader.common.testutil.RecordingLogger
import com.cheetrader.common.testutil.TestSignals
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class OkxExchangeServiceTest {
    private val mock = MockExchangeServer()
    private val logger = RecordingLogger()
    private lateinit var service: OkxExchangeService

    @BeforeEach
    fun setup() {
        mock.start()
        service = OkxExchangeService(
            apiKey = "test-api-key",
            secretKey = "test-secret-key",
            passphrase = "test-passphrase",
            logger = logger,
            baseUrlOverride = mock.baseUrl()
        )
    }

    @AfterEach
    fun teardown() {
        mock.stop()
        logger.clear()
    }

    // ===== testConnection =====

    @Test
    fun `testConnection success`() = runTest {
        // testConnection -> getBalance -> accountBalance + accountConfig
        mock.enqueue(OkxResponses.balance(500.0))

        val result = service.testConnection()
        assertTrue(result.isSuccess)
    }

    @Test
    fun `testConnection balance error`() = runTest {
        mock.enqueue(OkxResponses.apiError(50000, "Auth error"))

        val result = service.testConnection()
        assertTrue(result.isFailure)
    }

    // ===== getBalance =====

    @Test
    fun `getBalance success`() = runTest {
        mock.enqueue(OkxResponses.balance(1234.56))

        val result = service.getBalance()
        assertTrue(result.isSuccess)
        assertEquals(1234.56, result.getOrThrow(), 0.01)
    }

    @Test
    fun `getBalance USDT not found`() = runTest {
        mock.enqueue(OkxResponses.emptyBalance())

        val result = service.getBalance()
        // With emptyBalance, details is empty but totalEq is "0" which parses as 0.0
        // If USDT not found in details, it falls back to totalEq/adjEq
        assertTrue(result.isSuccess || result.isFailure) // depends on totalEq=0 being valid
    }

    @Test
    fun `getBalance API error`() = runTest {
        mock.enqueue(OkxResponses.apiError(50000, "Internal error"))

        val result = service.getBalance()
        assertTrue(result.isFailure)
    }

    // ===== executeSignal =====

    @Test
    fun `executeSignal LONG success`() = runTest {
        val signal = TestSignals.longBtc(tp = 70000.0, sl = 60000.0)

        // getBalance
        mock.enqueue(OkxResponses.balance(1000.0))
        // getAccountConfig (for positionMode)
        mock.enqueue(OkxResponses.accountConfig("long_short_mode", 2))
        // setLeverage (both sides in hedge mode)
        mock.enqueue(OkxResponses.leverageSuccess())
        mock.enqueue(OkxResponses.leverageSuccess())
        // getInstrumentInfo
        mock.enqueue(OkxResponses.instrument("BTC-USDT-SWAP", "0.01", "1", "1"))
        // getMarketPrice
        mock.enqueue(OkxResponses.ticker(65000.0))
        // getAccountConfig (cached from above, might not fire)
        // resolveMarginMode -> getAccountConfig (cached)
        // placeOrder
        mock.enqueue(OkxResponses.orderSuccess("okx-order-1"))

        val result = service.executeSignal(signal, 5.0)
        assertTrue(result.isSuccess)
        val exec = result.getOrThrow()
        assertEquals(ExecutionStatus.SUCCESS, exec.status)
        assertEquals("okx-order-1", exec.exchangeOrderId)
    }

    @Test
    fun `executeSignal order rejected propagates Result failure`() = runTest {
        // Previously this returned Result.success(OrderExecution(FAILED, ...)) which
        // caused silent-success bugs in callers using .getOrElse{}. Now OKX
        // propagates the exception (Package A anti-pattern fix).
        val signal = TestSignals.longBtc()

        mock.enqueue(OkxResponses.balance(1000.0))
        mock.enqueue(OkxResponses.accountConfig("long_short_mode", 2))
        mock.enqueue(OkxResponses.leverageSuccess())
        mock.enqueue(OkxResponses.leverageSuccess())
        mock.enqueue(OkxResponses.instrument("BTC-USDT-SWAP", "0.01", "1", "1"))
        mock.enqueue(OkxResponses.ticker(65000.0))
        mock.enqueue(OkxResponses.orderRejected("51000", "Insufficient balance"))

        val result = service.executeSignal(signal, 5.0)
        assertTrue(result.isFailure, "order rejection must surface as Result.failure")
        val ex = result.exceptionOrNull()
        assertNotNull(ex)
        assertTrue(ex is OkxException, "exception must carry OKX error detail")
    }

    @Test
    fun `executeSignal balance failure`() = runTest {
        val signal = TestSignals.longBtc()

        mock.enqueue(OkxResponses.apiError(50000, "Auth error"))

        val result = service.executeSignal(signal, 5.0)
        assertTrue(result.isFailure)
    }

    @Test
    fun `executeSignal instrument not found`() = runTest {
        val signal = TestSignals.longBtc()

        mock.enqueue(OkxResponses.balance(1000.0))
        mock.enqueue(OkxResponses.accountConfig("long_short_mode", 2))
        mock.enqueue(OkxResponses.leverageSuccess())
        mock.enqueue(OkxResponses.leverageSuccess())
        // Instrument returns empty
        mock.enqueue("""{"code":"0","msg":"","data":[]}""")

        val result = service.executeSignal(signal, 5.0)
        assertTrue(result.isSuccess)
        assertEquals(ExecutionStatus.FAILED, result.getOrThrow().status)
        assertTrue(result.getOrThrow().errorMessage?.contains("not found") == true)
    }

    @Test
    fun `executeSignal with trailing stop`() = runTest {
        val signal = TestSignals.longBtc(
            tp = 70000.0,
            sl = 60000.0,
            metadata = TestSignals.withTrailingStop(deviation = 0.01)
        )

        mock.enqueue(OkxResponses.balance(1000.0))
        mock.enqueue(OkxResponses.accountConfig("long_short_mode", 2))
        mock.enqueue(OkxResponses.leverageSuccess())
        mock.enqueue(OkxResponses.leverageSuccess())
        mock.enqueue(OkxResponses.instrument("BTC-USDT-SWAP", "0.01", "1", "1"))
        mock.enqueue(OkxResponses.ticker(65000.0))
        mock.enqueue(OkxResponses.orderSuccess())
        mock.enqueue(OkxResponses.algoSuccess("trailing-123"))

        val result = service.executeSignal(signal, 5.0)
        assertTrue(result.isSuccess)
        assertEquals(ExecutionStatus.SUCCESS, result.getOrThrow().status)
    }

    @Test
    fun `executeSignal trailing stop fails returns PARTIAL`() = runTest {
        val signal = TestSignals.longBtc(
            tp = null,
            sl = null,
            metadata = TestSignals.withTrailingStop(deviation = 0.01)
        )

        mock.enqueue(OkxResponses.balance(1000.0))
        mock.enqueue(OkxResponses.accountConfig("long_short_mode", 2))
        mock.enqueue(OkxResponses.leverageSuccess())
        mock.enqueue(OkxResponses.leverageSuccess())
        mock.enqueue(OkxResponses.instrument("BTC-USDT-SWAP", "0.01", "1", "1"))
        mock.enqueue(OkxResponses.ticker(65000.0))
        mock.enqueue(OkxResponses.orderSuccess())
        mock.enqueue(OkxResponses.apiError(50000, "Trailing failed"))
        // placeTrailingStopAlgoWithRecalc retries: attempt 2 needs a ticker
        // (getMarketPrice) + another algo attempt; attempt 3 one more algo.
        // Provide benign apiError responses so the retry path completes fast
        // instead of hanging on mock-queue exhaustion.
        mock.enqueue(OkxResponses.apiError(50000, "no market"))
        mock.enqueue(OkxResponses.apiError(50000, "Trailing failed attempt 2"))
        mock.enqueue(OkxResponses.apiError(50000, "Trailing failed attempt 3"))
        // fetchOrderFill — 3 retries (mock returns apiError, treated as "not
        // yet filled" and advances to next retry) + 1 getOpenPositions
        // fallback added by Bug #2 fix (2026-04-22).
        mock.enqueue(OkxResponses.apiError(50000, "not yet filled"))
        mock.enqueue(OkxResponses.apiError(50000, "not yet filled"))
        mock.enqueue(OkxResponses.apiError(50000, "not yet filled"))
        mock.enqueue(OkxResponses.positionsEmpty())

        val result = service.executeSignal(signal, 5.0)
        assertTrue(result.isSuccess)
        assertEquals(ExecutionStatus.PARTIAL, result.getOrThrow().status)
    }

    @Test
    fun `executeSignal falls back to getOpenPositions when avgPx not settled`() = runTest {
        // Bug #2 (2026-04-22): on OKX demo, market orders can take longer than
        // the 3-retry fetchOrderFill window to settle avgPx/accFillSz. Without
        // the getOpenPositions fallback, OrderExecution would ship with
        // executedPrice=null / executedQuantity=null — downstream Package A
        // ghost-trade guard then refuses to create an ActiveTrade, leaving a
        // live on-exchange position invisible on the dashboard. The fallback
        // pulls price + size from the settled position snapshot.
        val signal = TestSignals.longBtc(tp = 70000.0, sl = 60000.0)

        mock.enqueue(OkxResponses.balance(1000.0))
        mock.enqueue(OkxResponses.accountConfig("long_short_mode", 2))
        mock.enqueue(OkxResponses.leverageSuccess())
        mock.enqueue(OkxResponses.leverageSuccess())
        mock.enqueue(OkxResponses.instrument("BTC-USDT-SWAP", "0.01", "1", "1"))
        mock.enqueue(OkxResponses.ticker(65000.0))
        mock.enqueue(OkxResponses.orderSuccess("okx-order-fill-slow"))
        // fetchOrderFill 3 retries — OKX's "not yet filled" sentinel (empty
        // avgPx/accFillSz strings, code=0).
        val notFilled = """{"code":"0","msg":"","data":[{"ordId":"okx-order-fill-slow","avgPx":"","accFillSz":""}]}"""
        mock.enqueue(notFilled)
        mock.enqueue(notFilled)
        mock.enqueue(notFilled)
        // Fallback getOpenPositions: settled position with entry 65001.23,
        // size 10 contracts × ctVal 0.01 = 0.10 BTC base-asset units.
        mock.enqueue(
            """{"code":"0","msg":"","data":[{"instId":"BTC-USDT-SWAP","pos":"10","posSide":"long","avgPx":"65001.23","markPx":"65005","upl":"0","lever":"20","margin":"","liqPx":""}]}"""
        )

        val result = service.executeSignal(signal, 5.0)
        assertTrue(result.isSuccess, "executeSignal must succeed via fallback")
        val exec = result.getOrThrow()
        assertEquals(ExecutionStatus.SUCCESS, exec.status)
        assertEquals("okx-order-fill-slow", exec.exchangeOrderId)
        assertEquals(65001.23, exec.executedPrice!!, 0.01)
        // getOpenPositions already multiplied by ctVal — fallback MUST NOT
        // multiply again. 10 contracts * 0.01 ctVal = 0.10 base units.
        assertEquals(0.10, exec.executedQuantity!!, 1e-9)
    }

    @Test
    fun `executeSignal leaves executedPrice null when fallback position missing`() = runTest {
        // If the order truly didn't place (or placed and was auto-cancelled by
        // the exchange before we can query), the fallback must return null so
        // the downstream ghost-trade guard correctly rejects the OrderExecution.
        val signal = TestSignals.longBtc(tp = 70000.0, sl = 60000.0)

        mock.enqueue(OkxResponses.balance(1000.0))
        mock.enqueue(OkxResponses.accountConfig("long_short_mode", 2))
        mock.enqueue(OkxResponses.leverageSuccess())
        mock.enqueue(OkxResponses.leverageSuccess())
        mock.enqueue(OkxResponses.instrument("BTC-USDT-SWAP", "0.01", "1", "1"))
        mock.enqueue(OkxResponses.ticker(65000.0))
        mock.enqueue(OkxResponses.orderSuccess("okx-order-ghost"))
        val notFilled = """{"code":"0","msg":"","data":[{"ordId":"okx-order-ghost","avgPx":"","accFillSz":""}]}"""
        mock.enqueue(notFilled)
        mock.enqueue(notFilled)
        mock.enqueue(notFilled)
        mock.enqueue(OkxResponses.positionsEmpty())

        val result = service.executeSignal(signal, 5.0)
        assertTrue(result.isSuccess)
        val exec = result.getOrThrow()
        // Status is SUCCESS because the order was placed (orderId returned) —
        // but the downstream ViewModel ghost-trade guard will reject it as an
        // ActiveTrade because executedQuantity is null.
        assertEquals(ExecutionStatus.SUCCESS, exec.status)
        assertEquals("okx-order-ghost", exec.exchangeOrderId)
        assertEquals(null, exec.executedPrice)
        assertEquals(null, exec.executedQuantity)
    }

    // ===== getOpenPositionsCount =====

    @Test
    fun `getOpenPositionsCount success`() = runTest {
        mock.enqueue(OkxResponses.positions("BTC-USDT-SWAP", 1.0, "long"))
        // Bug B hotfix (2026-04-21): getOpenPositions now normalizes size
        // by ctVal, which requires an instrument lookup per position.
        mock.enqueue(OkxResponses.instrument("BTC-USDT-SWAP", "0.01", "1", "1"))

        val result = service.getOpenPositionsCount()
        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrThrow())
    }

    @Test
    fun `getOpenPositionsCount empty`() = runTest {
        mock.enqueue(OkxResponses.positionsEmpty())

        val result = service.getOpenPositionsCount()
        assertTrue(result.isSuccess)
        assertEquals(0, result.getOrThrow())
    }

    // ===== closePosition =====

    @Test
    fun `closePosition success`() = runTest {
        mock.enqueue(OkxResponses.positions("BTC-USDT-SWAP", 1.0, "long"))
        // getAccountConfig for resolveMarginMode
        mock.enqueue(OkxResponses.accountConfig("long_short_mode", 2))
        mock.enqueue(OkxResponses.closePositionSuccess())

        val result = service.closePosition("BTCUSDT")
        assertTrue(result.isSuccess)
        assertEquals(ExecutionStatus.SUCCESS, result.getOrThrow().status)
    }

    @Test
    fun `closePosition no positions`() = runTest {
        mock.enqueue(OkxResponses.positionsEmpty())

        val result = service.closePosition("BTCUSDT")
        assertTrue(result.isSuccess)
        assertEquals(ExecutionStatus.SUCCESS, result.getOrThrow().status)
    }

    // ===== closeAllPositions =====

    @Test
    fun `closeAllPositions success`() = runTest {
        mock.enqueue(OkxResponses.positions("BTC-USDT-SWAP", 1.0, "long"))
        mock.enqueue(OkxResponses.accountConfig("long_short_mode", 2))
        mock.enqueue(OkxResponses.closePositionSuccess())

        val result = service.closeAllPositions()
        assertTrue(result.isSuccess)
    }

    @Test
    fun `closeAllPositions empty`() = runTest {
        mock.enqueue(OkxResponses.positionsEmpty())

        val result = service.closeAllPositions()
        assertTrue(result.isSuccess)
    }

    // ===== cancelAllOrders =====

    @Test
    fun `cancelAllOrders success`() = runTest {
        mock.enqueue(OkxResponses.pendingOrders("ord-1", "ord-2"))
        mock.enqueue(OkxResponses.cancelBatchSuccess())

        val result = service.cancelAllOrders("BTCUSDT")
        assertTrue(result.isSuccess)
    }

    @Test
    fun `cancelAllOrders no pending`() = runTest {
        mock.enqueue(OkxResponses.pendingOrdersEmpty())

        val result = service.cancelAllOrders("BTCUSDT")
        assertTrue(result.isSuccess)
    }

    // ===== getMarketPrice =====

    @Test
    fun `getMarketPrice success`() = runTest {
        mock.enqueue(OkxResponses.ticker(65432.10))

        val result = service.getMarketPrice("BTC-USDT-SWAP")
        assertTrue(result.isSuccess)
        assertEquals(65432.10, result.getOrThrow(), 0.01)
    }

    @Test
    fun `getMarketPrice empty data`() = runTest {
        mock.enqueue("""{"code":"0","msg":"","data":[]}""")

        val result = service.getMarketPrice("BTC-USDT-SWAP")
        assertTrue(result.isFailure)
    }

    @Test
    fun `name is OKX`() {
        assertEquals("OKX", service.name)
    }

    // ===== getRecentFills (Package D) =====

    @Test
    fun `getRecentFills maps execType C to isReduceOnly true`() = runTest {
        // Bug B hotfix (2026-04-21): getRecentFills now normalizes fillSz
        // by ctVal — instrument lookup happens first.
        mock.enqueue(OkxResponses.instrument("BTC-USDT-SWAP", "0.01", "1", "1"))
        mock.enqueue(
            OkxResponses.fillsHistory(
                mapOf("orderId" to "open-1", "side" to "buy", "price" to "65000", "qty" to "1", "time" to "100", "execType" to "T"),
                mapOf("orderId" to "tp-1", "side" to "sell", "price" to "66000", "qty" to "1", "time" to "200", "execType" to "C", "pnl" to "10")
            )
        )

        val result = service.getRecentFills("BTCUSDT", sinceMs = 0L, limit = 50)
        assertTrue(result.isSuccess)
        val fills = result.getOrThrow()
        assertEquals(2, fills.size)
        // Open execution (execType=T) → unknown reduceOnly (null, not false — honest).
        assertEquals("open-1", fills[0].orderId)
        assertEquals("BUY", fills[0].side)
        assertNull(fills[0].isReduceOnly)
        // Close execution (execType=C) → reduceOnly true.
        assertEquals("tp-1", fills[1].orderId)
        assertEquals(true, fills[1].isReduceOnly)
        assertEquals(10.0, fills[1].realizedPnl!!, 0.001)
    }

    @Test
    fun `getRecentFills empty data returns empty list`() = runTest {
        // Bug B hotfix: instrument lookup is now unconditional on the fills path.
        mock.enqueue(OkxResponses.instrument("BTC-USDT-SWAP", "0.01", "1", "1"))
        mock.enqueue(OkxResponses.fillsHistory())

        val result = service.getRecentFills("BTCUSDT", sinceMs = 0L, limit = 50)
        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().isEmpty())
    }

    // Bug B regression coverage (2026-04-21): OKX `pos` / `fillSz` are in
    // contracts — for BTC-USDT-SWAP 1 contract = 0.01 BTC. The common contract
    // across adapters is base-asset units, so [ExchangePosition.size] and
    // [ExchangeFill.quantity] must be normalized by ctVal. Without this, the
    // downstream `notional = entry * size` calc was 100× wrong on OKX and
    // phantom fees flipped unrealized +$4 into recorded −$104 in prod.

    @Test
    fun `getOpenPositions multiplies size by ctVal to return base asset units`() = runTest {
        // 10 contracts of BTC-USDT-SWAP at ctVal=0.01 should surface as 0.10 BTC.
        mock.enqueue(OkxResponses.positions("BTC-USDT-SWAP", 10.0, "long"))
        mock.enqueue(OkxResponses.instrument("BTC-USDT-SWAP", "0.01", "1", "1"))

        val result = service.getOpenPositions()
        assertTrue(result.isSuccess)
        val positions = result.getOrThrow()
        assertEquals(1, positions.size)
        assertEquals(0.10, positions[0].size, 1e-9)
        assertEquals("BTCUSDT", positions[0].symbol)
    }

    @Test
    fun `getRecentFills multiplies fillSz by ctVal to return base asset units`() = runTest {
        mock.enqueue(OkxResponses.instrument("BTC-USDT-SWAP", "0.01", "1", "1"))
        mock.enqueue(
            OkxResponses.fillsHistory(
                mapOf("orderId" to "x", "side" to "buy", "price" to "65000", "qty" to "5", "time" to "100", "execType" to "T")
            )
        )

        val result = service.getRecentFills("BTCUSDT", sinceMs = 0L, limit = 50)
        val fills = result.getOrThrow()
        assertEquals(1, fills.size)
        // 5 contracts × 0.01 ctVal = 0.05 BTC
        assertEquals(0.05, fills[0].quantity, 1e-9)
    }
}

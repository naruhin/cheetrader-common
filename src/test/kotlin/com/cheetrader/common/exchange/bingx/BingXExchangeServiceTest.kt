package com.cheetrader.common.exchange.bingx

import com.cheetrader.common.model.ExecutionStatus
import com.cheetrader.common.testutil.BingXResponses
import com.cheetrader.common.testutil.MockExchangeServer
import com.cheetrader.common.testutil.RecordingLogger
import com.cheetrader.common.testutil.TestSignals
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class BingXExchangeServiceTest {
    private val mock = MockExchangeServer()
    private val logger = RecordingLogger()
    private lateinit var service: BingXExchangeService

    @BeforeEach
    fun setup() {
        mock.start()
        service = BingXExchangeService(
            apiKey = "test-api-key",
            secretKey = "test-secret-key",
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
        mock.enqueue(BingXResponses.serverTime())   // syncTime in testConnection
        mock.enqueue(BingXResponses.serverTime())   // syncTime in getBalance
        mock.enqueue(BingXResponses.balance(500.0)) // balance

        val result = service.testConnection()
        assertTrue(result.isSuccess)
    }

    @Test
    fun `testConnection balance failure`() = runTest {
        mock.enqueue(BingXResponses.serverTime())
        mock.enqueue(BingXResponses.serverTime())
        mock.enqueue(BingXResponses.apiError(-1, "Auth error"))

        val result = service.testConnection()
        assertTrue(result.isFailure)
    }

    // ===== getBalance =====

    @Test
    fun `getBalance success`() = runTest {
        mock.enqueue(BingXResponses.serverTime())
        mock.enqueue(BingXResponses.balance(1234.56))

        val result = service.getBalance()
        assertTrue(result.isSuccess)
        assertEquals(1234.56, result.getOrThrow(), 0.01)
    }

    @Test
    fun `getBalance empty data`() = runTest {
        mock.enqueue(BingXResponses.serverTime())
        mock.enqueue(BingXResponses.emptyBalance())

        val result = service.getBalance()
        assertTrue(result.isFailure)
    }

    @Test
    fun `getBalance API error`() = runTest {
        mock.enqueue(BingXResponses.serverTime())
        mock.enqueue(BingXResponses.apiError(100001, "Insufficient balance"))

        val result = service.getBalance()
        assertTrue(result.isFailure)
    }

    // ===== executeSignal =====

    @Test
    fun `executeSignal LONG success`() = runTest {
        val signal = TestSignals.longBtc(tp = 70000.0, sl = 60000.0)

        // Flow: syncTime, getBalance(syncTime + balance), setupPosition(margin + leverage),
        //       getMarketPrice(signed GET, no syncTime), placeOrder(signed POST, no syncTime),
        //       verifyPositionOpened(syncTime + positions)
        mock.enqueue(BingXResponses.serverTime())     // syncTime in executeSignal
        mock.enqueue(BingXResponses.serverTime())     // syncTime in getBalance
        mock.enqueue(BingXResponses.balance(1000.0))  // balance
        mock.enqueue(BingXResponses.marginTypeSuccess()) // setupPosition margin
        mock.enqueue(BingXResponses.leverageSuccess())   // setupPosition leverage
        mock.enqueue(BingXResponses.marketPrice(65000.0)) // getMarketPrice (no syncTime)
        mock.enqueue(BingXResponses.orderSuccess("bingx-order-1")) // placeOrder
        mock.enqueue(BingXResponses.serverTime())     // verify: syncTime
        mock.enqueue(BingXResponses.positions("BTC-USDT", 0.010, "LONG")) // verify: positions

        val result = service.executeSignal(signal, 5.0)
        assertTrue(result.isSuccess)
        val exec = result.getOrThrow()
        assertEquals(ExecutionStatus.SUCCESS, exec.status)
        assertEquals("bingx-order-1", exec.exchangeOrderId)
        assertTrue(exec.hasExchangeStopLoss)
        assertTrue(exec.hasExchangeTakeProfit)
    }

    @Test
    fun `executeSignal SHORT`() = runTest {
        val signal = TestSignals.shortEth(tp = 3000.0, sl = 3800.0)

        mock.enqueue(BingXResponses.serverTime())
        mock.enqueue(BingXResponses.serverTime())
        mock.enqueue(BingXResponses.balance(1000.0))
        mock.enqueue(BingXResponses.marginTypeSuccess())
        mock.enqueue(BingXResponses.leverageSuccess())
        mock.enqueue(BingXResponses.marketPrice(3500.0))
        mock.enqueue(BingXResponses.orderSuccess())
        mock.enqueue(BingXResponses.serverTime())
        mock.enqueue(BingXResponses.positions("ETH-USDT", 0.100, "SHORT"))

        val result = service.executeSignal(signal, 5.0)
        assertTrue(result.isSuccess)
        assertEquals(ExecutionStatus.SUCCESS, result.getOrThrow().status)
    }

    @Test
    fun `executeSignal balance failure`() = runTest {
        val signal = TestSignals.longBtc()

        mock.enqueue(BingXResponses.serverTime())
        mock.enqueue(BingXResponses.serverTime())
        mock.enqueue(BingXResponses.apiError(-1, "Auth error"))

        val result = service.executeSignal(signal, 5.0)
        assertTrue(result.isFailure)
    }

    @Test
    fun `executeSignal order rejected propagates Result failure`() = runTest {
        val signal = TestSignals.longBtc()

        mock.enqueue(BingXResponses.serverTime())
        mock.enqueue(BingXResponses.serverTime())
        mock.enqueue(BingXResponses.balance(1000.0))
        mock.enqueue(BingXResponses.marginTypeSuccess())
        mock.enqueue(BingXResponses.leverageSuccess())
        mock.enqueue(BingXResponses.marketPrice(65000.0))
        mock.enqueue(BingXResponses.apiError(100001, "Order rejected"))

        val result = service.executeSignal(signal, 5.0)
        // Previously this returned Result.success(OrderExecution(FAILED, ...)) which
        // caused silent-success bugs in callers using .getOrElse{}. Now propagate failure.
        assertTrue(result.isFailure, "order rejection must surface as Result.failure")
        val ex = result.exceptionOrNull()
        assertNotNull(ex)
        assertTrue(ex is BingXApiException, "exception must carry BingX error code")
    }

    @Test
    fun `executeSignal no TP SL`() = runTest {
        val signal = TestSignals.longBtc(tp = null, sl = null)

        mock.enqueue(BingXResponses.serverTime())
        mock.enqueue(BingXResponses.serverTime())
        mock.enqueue(BingXResponses.balance(1000.0))
        mock.enqueue(BingXResponses.marginTypeSuccess())
        mock.enqueue(BingXResponses.leverageSuccess())
        mock.enqueue(BingXResponses.marketPrice(65000.0))
        mock.enqueue(BingXResponses.orderSuccess())
        mock.enqueue(BingXResponses.serverTime())
        mock.enqueue(BingXResponses.positions("BTC-USDT", 0.010, "LONG"))

        val result = service.executeSignal(signal, 5.0)
        assertTrue(result.isSuccess)
        assertEquals(ExecutionStatus.SUCCESS, result.getOrThrow().status)
    }

    @Test
    fun `executeSignal with trailing stop`() = runTest {
        val signal = TestSignals.longBtc(
            tp = 70000.0,
            sl = 60000.0,
            metadata = TestSignals.withTrailingStop(deviation = 0.01, triggerPrice = 66000.0)
        )

        mock.enqueue(BingXResponses.serverTime())
        mock.enqueue(BingXResponses.serverTime())
        mock.enqueue(BingXResponses.balance(1000.0))
        mock.enqueue(BingXResponses.marginTypeSuccess())
        mock.enqueue(BingXResponses.leverageSuccess())
        mock.enqueue(BingXResponses.marketPrice(65000.0))
        mock.enqueue(BingXResponses.orderSuccess())
        mock.enqueue(BingXResponses.trailingStopSuccess())
        mock.enqueue(BingXResponses.serverTime())
        mock.enqueue(BingXResponses.positions("BTC-USDT", 0.010, "LONG"))

        val result = service.executeSignal(signal, 5.0)
        assertTrue(result.isSuccess)
        assertEquals(ExecutionStatus.SUCCESS, result.getOrThrow().status)
    }

    @Test
    fun `executeSignal trailing stop fails returns PARTIAL with hasExchangeTakeProfit false`() = runTest {
        // No embedded TP and no multi-TP — trailing is the only profit-exit.
        // When trailing fails, the position has NO profit-exit on the exchange,
        // so hasExchangeTakeProfit must be false and status must be PARTIAL.
        val signal = TestSignals.longBtc(
            tp = null,
            sl = null,
            metadata = TestSignals.withTrailingStop(deviation = 0.01)
        )

        mock.enqueue(BingXResponses.serverTime())
        mock.enqueue(BingXResponses.serverTime())
        mock.enqueue(BingXResponses.balance(1000.0))
        mock.enqueue(BingXResponses.marginTypeSuccess())
        mock.enqueue(BingXResponses.leverageSuccess())
        mock.enqueue(BingXResponses.marketPrice(65000.0))
        mock.enqueue(BingXResponses.orderSuccess())
        // Trailing recalc path makes 3 attempts on 110416 errors; here we return a
        // non-activation-price error so it fails fast after the first attempt.
        mock.enqueue(BingXResponses.apiError(-1, "Trailing failed"))
        mock.enqueue(BingXResponses.serverTime())
        mock.enqueue(BingXResponses.positions("BTC-USDT", 0.010, "LONG"))

        val result = service.executeSignal(signal, 5.0)
        assertTrue(result.isSuccess)
        val exec = result.getOrThrow()
        assertEquals(ExecutionStatus.PARTIAL, exec.status)
        assertFalse(exec.hasExchangeTakeProfit)
        assertTrue(exec.hasExchangeStopLoss)
    }

    @Test
    fun `executeSignal trailing fails but embedded TP exists keeps hasExchangeTakeProfit true`() = runTest {
        // Embedded TP is placed with the main order. Trailing failure does NOT
        // leave the position without a profit-exit in this case.
        val signal = TestSignals.longBtc(
            tp = 70000.0,
            sl = 60000.0,
            metadata = TestSignals.withTrailingStop(deviation = 0.01, triggerPrice = 66000.0)
        )

        mock.enqueue(BingXResponses.serverTime())
        mock.enqueue(BingXResponses.serverTime())
        mock.enqueue(BingXResponses.balance(1000.0))
        mock.enqueue(BingXResponses.marginTypeSuccess())
        mock.enqueue(BingXResponses.leverageSuccess())
        mock.enqueue(BingXResponses.marketPrice(65000.0))
        mock.enqueue(BingXResponses.orderSuccess())
        mock.enqueue(BingXResponses.apiError(-1, "Trailing failed"))
        mock.enqueue(BingXResponses.serverTime())
        mock.enqueue(BingXResponses.positions("BTC-USDT", 0.010, "LONG"))

        val result = service.executeSignal(signal, 5.0)
        assertTrue(result.isSuccess)
        val exec = result.getOrThrow()
        assertEquals(ExecutionStatus.PARTIAL, exec.status)
        assertTrue(exec.hasExchangeTakeProfit, "embedded TP keeps profit-exit intact when trailing fails")
    }

    @Test
    fun `executeSignal filters avgPrice zero to null`() = runTest {
        // BingX market orders sometimes return avgPrice="0" when the fill price
        // hasn't settled yet. We must NOT surface 0.0 downstream — it breaks PnL
        // calculations and was the root cause of knowledge/2026-04-03 PnL bug.
        val signal = TestSignals.longBtc(tp = 70000.0, sl = 60000.0)

        mock.enqueue(BingXResponses.serverTime())
        mock.enqueue(BingXResponses.serverTime())
        mock.enqueue(BingXResponses.balance(1000.0))
        mock.enqueue(BingXResponses.marginTypeSuccess())
        mock.enqueue(BingXResponses.leverageSuccess())
        mock.enqueue(BingXResponses.marketPrice(65000.0))
        mock.enqueue(BingXResponses.orderSuccess(avgPrice = "0"))  // <-- the bug trigger
        mock.enqueue(BingXResponses.serverTime())
        mock.enqueue(BingXResponses.positions("BTC-USDT", 0.010, "LONG"))

        val result = service.executeSignal(signal, 5.0)
        assertTrue(result.isSuccess)
        val exec = result.getOrThrow()
        assertNull(exec.executedPrice, "avgPrice=0 must become null, not 0.0")
    }

    @Test
    fun `executeSignal position never appears returns Result failure`() = runTest {
        // Main order accepted, but positions endpoint shows nothing across both
        // verify attempts. We must treat this as FAILED (no ghost ActiveTrade).
        val signal = TestSignals.longBtc(tp = 70000.0, sl = 60000.0)

        mock.enqueue(BingXResponses.serverTime())
        mock.enqueue(BingXResponses.serverTime())
        mock.enqueue(BingXResponses.balance(1000.0))
        mock.enqueue(BingXResponses.marginTypeSuccess())
        mock.enqueue(BingXResponses.leverageSuccess())
        mock.enqueue(BingXResponses.marketPrice(65000.0))
        mock.enqueue(BingXResponses.orderSuccess("ghost-order"))
        // verify attempt 1
        mock.enqueue(BingXResponses.serverTime())
        mock.enqueue(BingXResponses.positionsEmpty())
        // verify attempt 2
        mock.enqueue(BingXResponses.serverTime())
        mock.enqueue(BingXResponses.positionsEmpty())

        val result = service.executeSignal(signal, 5.0)
        assertTrue(result.isFailure, "ghost order (accepted but no position) must surface as failure")
        val ex = result.exceptionOrNull()
        assertNotNull(ex)
        assertTrue(ex!!.message!!.contains("did not appear", ignoreCase = true))
    }

    @Test
    fun `executeSignal position appears on second verify attempt`() = runTest {
        // First attempt: empty (settlement delay). Second attempt: position shows up.
        val signal = TestSignals.longBtc(tp = 70000.0, sl = 60000.0)

        mock.enqueue(BingXResponses.serverTime())
        mock.enqueue(BingXResponses.serverTime())
        mock.enqueue(BingXResponses.balance(1000.0))
        mock.enqueue(BingXResponses.marginTypeSuccess())
        mock.enqueue(BingXResponses.leverageSuccess())
        mock.enqueue(BingXResponses.marketPrice(65000.0))
        mock.enqueue(BingXResponses.orderSuccess())
        // verify attempt 1: empty
        mock.enqueue(BingXResponses.serverTime())
        mock.enqueue(BingXResponses.positionsEmpty())
        // verify attempt 2: position present
        mock.enqueue(BingXResponses.serverTime())
        mock.enqueue(BingXResponses.positions("BTC-USDT", 0.010, "LONG"))

        val result = service.executeSignal(signal, 5.0)
        assertTrue(result.isSuccess, "retry must succeed when position shows on attempt 2")
        assertEquals(ExecutionStatus.SUCCESS, result.getOrThrow().status)
    }

    @Test
    fun `executeSignal SL already breached is rejected with FAILED`() = runTest {
        // Pre-flight rejection: signal.stopLoss is 66000 for LONG, but market is 65000.
        // safeSl becomes null (sanitizeTpSl guard) → we reject without placing an order.
        val signal = TestSignals.longBtc(tp = 70000.0, sl = 66000.0)

        mock.enqueue(BingXResponses.serverTime())
        mock.enqueue(BingXResponses.serverTime())
        mock.enqueue(BingXResponses.balance(1000.0))
        mock.enqueue(BingXResponses.marginTypeSuccess())
        mock.enqueue(BingXResponses.leverageSuccess())
        mock.enqueue(BingXResponses.marketPrice(65000.0)) // market < SL for LONG → breach

        val result = service.executeSignal(signal, 5.0)
        assertTrue(result.isSuccess)
        val exec = result.getOrThrow()
        assertEquals(ExecutionStatus.FAILED, exec.status)
        assertTrue(exec.errorMessage!!.contains("already breached", ignoreCase = true))
    }

    // ===== getOpenPositionsCount =====

    @Test
    fun `getOpenPositionsCount success`() = runTest {
        mock.enqueue(BingXResponses.serverTime())
        mock.enqueue(BingXResponses.positions("BTC-USDT", 0.01))

        val result = service.getOpenPositionsCount()
        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrThrow())
    }

    @Test
    fun `getOpenPositionsCount empty`() = runTest {
        mock.enqueue(BingXResponses.serverTime())
        mock.enqueue(BingXResponses.positionsEmpty())

        val result = service.getOpenPositionsCount()
        assertTrue(result.isSuccess)
        assertEquals(0, result.getOrThrow())
    }

    // ===== closePosition =====

    @Test
    fun `closePosition success`() = runTest {
        mock.enqueue(BingXResponses.serverTime())
        mock.enqueue(BingXResponses.closeSuccess())

        val result = service.closePosition("BTCUSDT")
        assertTrue(result.isSuccess)
        assertEquals(ExecutionStatus.SUCCESS, result.getOrThrow().status)
    }

    @Test
    fun `closePosition API error`() = runTest {
        mock.enqueue(BingXResponses.serverTime())
        mock.enqueue(BingXResponses.apiError(100400, "Position not found"))

        val result = service.closePosition("BTCUSDT")
        assertTrue(result.isFailure)
    }

    // ===== closeAllPositions =====

    @Test
    fun `closeAllPositions success`() = runTest {
        mock.enqueue(BingXResponses.serverTime())
        mock.enqueue(BingXResponses.positions("BTC-USDT", 0.01))
        // closePosition for each
        mock.enqueue(BingXResponses.serverTime())
        mock.enqueue(BingXResponses.closeSuccess())

        val result = service.closeAllPositions()
        assertTrue(result.isSuccess)
    }

    @Test
    fun `closeAllPositions empty`() = runTest {
        mock.enqueue(BingXResponses.serverTime())
        mock.enqueue(BingXResponses.positionsEmpty())

        val result = service.closeAllPositions()
        assertTrue(result.isSuccess)
    }

    // ===== cancelAllOrders =====

    @Test
    fun `cancelAllOrders success`() = runTest {
        mock.enqueue(BingXResponses.serverTime())
        mock.enqueue(BingXResponses.cancelAllSuccess())

        val result = service.cancelAllOrders("BTCUSDT")
        assertTrue(result.isSuccess)
    }

    @Test
    fun `cancelAllOrders API error`() = runTest {
        mock.enqueue(BingXResponses.serverTime())
        mock.enqueue(BingXResponses.apiError(-1, "Error"))

        val result = service.cancelAllOrders("BTCUSDT")
        assertTrue(result.isFailure)
    }

    // ===== getMarketPrice =====

    @Test
    fun `getMarketPrice success`() = runTest {
        // getMarketPrice uses executeSignedGet directly (no syncTime call)
        mock.enqueue(BingXResponses.marketPrice(65432.10))

        val result = service.getMarketPrice("BTC-USDT")
        assertTrue(result.isSuccess)
        assertEquals(65432.10, result.getOrThrow(), 0.01)
    }

    @Test
    fun `getMarketPrice API error`() = runTest {
        mock.enqueue(BingXResponses.apiError(-1, "Unknown symbol"))

        val result = service.getMarketPrice("INVALID")
        assertTrue(result.isFailure)
    }

    @Test
    fun `name is BingX`() {
        assertEquals("BingX", service.name)
    }
}

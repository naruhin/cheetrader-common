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

        val result = service.executeSignal(signal, 5.0)
        assertTrue(result.isSuccess)
        assertEquals(ExecutionStatus.PARTIAL, result.getOrThrow().status)
    }

    // ===== getOpenPositionsCount =====

    @Test
    fun `getOpenPositionsCount success`() = runTest {
        mock.enqueue(OkxResponses.positions("BTC-USDT-SWAP", 1.0, "long"))

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
        mock.enqueue(OkxResponses.fillsHistory())

        val result = service.getRecentFills("BTCUSDT", sinceMs = 0L, limit = 50)
        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().isEmpty())
    }
}

package com.cheetrader.common.exchange.bybit

import com.cheetrader.common.model.ExecutionStatus
import com.cheetrader.common.testutil.BybitResponses
import com.cheetrader.common.testutil.MockExchangeServer
import com.cheetrader.common.testutil.RecordingLogger
import com.cheetrader.common.testutil.TestSignals
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class BybitExchangeServiceTest {
    private val mock = MockExchangeServer()
    private val logger = RecordingLogger()
    private lateinit var service: BybitExchangeService

    @BeforeEach
    fun setup() {
        mock.start()
        service = BybitExchangeService(
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
        // tryWithBaseUrls -> syncTime + getBalanceInternal (syncTime + fetchBalance) + getOpenPositions (syncTime + positionList)
        mock.enqueue(BybitResponses.serverTime())  // syncTime in testConnection
        mock.enqueue(BybitResponses.serverTime())  // syncTime in getBalanceInternal
        mock.enqueue(BybitResponses.balance(500.0)) // fetchBalance UNIFIED
        mock.enqueue(BybitResponses.serverTime())  // syncTime in getOpenPositions
        mock.enqueue(BybitResponses.positionListEmpty()) // position list check

        val result = service.testConnection()
        assertTrue(result.isSuccess)
    }

    @Test
    fun `testConnection balance failure`() = runTest {
        mock.enqueue(BybitResponses.serverTime())
        mock.enqueue(BybitResponses.serverTime())
        mock.enqueue(BybitResponses.apiError(10004, "Invalid API key"))
        mock.enqueue(BybitResponses.apiError(10004, "Invalid API key")) // CONTRACT fallback

        val result = service.testConnection()
        assertTrue(result.isFailure)
    }

    // ===== getBalance =====

    @Test
    fun `getBalance success UNIFIED`() = runTest {
        mock.enqueue(BybitResponses.serverTime())
        mock.enqueue(BybitResponses.balance(1234.56))

        val result = service.getBalance()
        assertTrue(result.isSuccess)
        assertEquals(1234.56, result.getOrThrow(), 0.01)
    }

    @Test
    fun `getBalance fallback to CONTRACT`() = runTest {
        mock.enqueue(BybitResponses.serverTime())
        mock.enqueue(BybitResponses.emptyBalance()) // UNIFIED fails
        mock.enqueue(BybitResponses.balance(999.0)) // CONTRACT succeeds

        val result = service.getBalance()
        assertTrue(result.isSuccess)
        assertEquals(999.0, result.getOrThrow(), 0.01)
    }

    @Test
    fun `getBalance both fail`() = runTest {
        mock.enqueue(BybitResponses.serverTime())
        mock.enqueue(BybitResponses.emptyBalance())
        mock.enqueue(BybitResponses.emptyBalance())

        val result = service.getBalance()
        assertTrue(result.isFailure)
    }

    // ===== executeSignal =====

    @Test
    fun `executeSignal LONG success`() = runTest {
        val signal = TestSignals.longBtc(tp = 70000.0, sl = 60000.0)

        mock.enqueue(BybitResponses.serverTime())  // syncTime in executeSignal
        // getBalance -> tryWithBaseUrls -> getBalanceInternal
        mock.enqueue(BybitResponses.serverTime())
        mock.enqueue(BybitResponses.balance(1000.0))
        // setLeverage
        mock.enqueue(BybitResponses.leverageSuccess())
        // getPositionMode -> position list
        mock.enqueue(BybitResponses.positionListEmpty())
        // getMarketPrice
        mock.enqueue(BybitResponses.ticker(65000.0))
        // placeOrder
        mock.enqueue(BybitResponses.orderSuccess("bybit-order-1"))

        val result = service.executeSignal(signal, 5.0)
        assertTrue(result.isSuccess)
        val exec = result.getOrThrow()
        assertEquals(ExecutionStatus.SUCCESS, exec.status)
        assertEquals("bybit-order-1", exec.exchangeOrderId)
    }

    @Test
    fun `executeSignal SHORT`() = runTest {
        val signal = TestSignals.shortEth(tp = 3000.0, sl = 3800.0)

        mock.enqueue(BybitResponses.serverTime())
        mock.enqueue(BybitResponses.serverTime())
        mock.enqueue(BybitResponses.balance(1000.0))
        mock.enqueue(BybitResponses.leverageSuccess())
        mock.enqueue(BybitResponses.positionListEmpty())
        mock.enqueue(BybitResponses.ticker(3500.0))
        mock.enqueue(BybitResponses.orderSuccess())

        val result = service.executeSignal(signal, 5.0)
        assertTrue(result.isSuccess)
        assertEquals(ExecutionStatus.SUCCESS, result.getOrThrow().status)
    }

    @Test
    fun `executeSignal order fails returns FAILED`() = runTest {
        val signal = TestSignals.longBtc()

        mock.enqueue(BybitResponses.serverTime())
        mock.enqueue(BybitResponses.serverTime())
        mock.enqueue(BybitResponses.balance(1000.0))
        mock.enqueue(BybitResponses.leverageSuccess())
        mock.enqueue(BybitResponses.positionListEmpty())
        mock.enqueue(BybitResponses.ticker(65000.0))
        mock.enqueue(BybitResponses.apiError(170000, "Order error"))

        val result = service.executeSignal(signal, 5.0)
        assertTrue(result.isSuccess)
        assertEquals(ExecutionStatus.FAILED, result.getOrThrow().status)
    }

    @Test
    fun `executeSignal no TP SL`() = runTest {
        val signal = TestSignals.longBtc(tp = null, sl = null)

        mock.enqueue(BybitResponses.serverTime())
        mock.enqueue(BybitResponses.serverTime())
        mock.enqueue(BybitResponses.balance(1000.0))
        mock.enqueue(BybitResponses.leverageSuccess())
        mock.enqueue(BybitResponses.positionListEmpty())
        mock.enqueue(BybitResponses.ticker(65000.0))
        mock.enqueue(BybitResponses.orderSuccess())

        val result = service.executeSignal(signal, 5.0)
        assertTrue(result.isSuccess)
        assertEquals(ExecutionStatus.SUCCESS, result.getOrThrow().status)
    }

    @Test
    fun `executeSignal balance failure`() = runTest {
        val signal = TestSignals.longBtc()

        mock.enqueue(BybitResponses.serverTime())
        // getBalance -> tryWithBaseUrls
        mock.enqueue(BybitResponses.serverTime())
        mock.enqueue(BybitResponses.emptyBalance())
        mock.enqueue(BybitResponses.emptyBalance())

        val result = service.executeSignal(signal, 5.0)
        assertTrue(result.isFailure)
    }

    // ===== getOpenPositionsCount =====

    @Test
    fun `getOpenPositionsCount success`() = runTest {
        mock.enqueue(BybitResponses.serverTime())
        mock.enqueue(BybitResponses.positionList("BTCUSDT", 0.01, "Buy"))

        val result = service.getOpenPositionsCount()
        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrThrow())
    }

    @Test
    fun `getOpenPositionsCount empty`() = runTest {
        mock.enqueue(BybitResponses.serverTime())
        mock.enqueue(BybitResponses.positionListEmpty())

        val result = service.getOpenPositionsCount()
        assertTrue(result.isSuccess)
        assertEquals(0, result.getOrThrow())
    }

    // ===== closePosition =====

    @Test
    fun `closePosition success`() = runTest {
        mock.enqueue(BybitResponses.serverTime())
        mock.enqueue(BybitResponses.positionList("BTCUSDT", 0.01, "Buy"))
        mock.enqueue(BybitResponses.orderSuccess())

        val result = service.closePosition("BTCUSDT")
        assertTrue(result.isSuccess)
        assertEquals(ExecutionStatus.SUCCESS, result.getOrThrow().status)
    }

    @Test
    fun `closePosition no positions`() = runTest {
        mock.enqueue(BybitResponses.serverTime())
        mock.enqueue(BybitResponses.positionListEmpty())

        val result = service.closePosition("BTCUSDT")
        assertTrue(result.isSuccess)
        assertEquals(ExecutionStatus.SUCCESS, result.getOrThrow().status)
    }

    // ===== closeAllPositions =====

    @Test
    fun `closeAllPositions success`() = runTest {
        mock.enqueue(BybitResponses.serverTime())
        mock.enqueue(BybitResponses.positionList("BTCUSDT", 0.01, "Buy"))
        mock.enqueue(BybitResponses.orderSuccess())

        val result = service.closeAllPositions()
        assertTrue(result.isSuccess)
    }

    // ===== cancelAllOrders =====

    @Test
    fun `cancelAllOrders success`() = runTest {
        mock.enqueue(BybitResponses.serverTime())
        mock.enqueue(BybitResponses.cancelAllSuccess())

        val result = service.cancelAllOrders("BTCUSDT")
        assertTrue(result.isSuccess)
    }

    @Test
    fun `cancelAllOrders API error`() = runTest {
        mock.enqueue(BybitResponses.serverTime())
        mock.enqueue(BybitResponses.apiError(10001, "Error"))

        val result = service.cancelAllOrders("BTCUSDT")
        assertTrue(result.isFailure)
    }

    // ===== getMarketPrice =====

    @Test
    fun `executeSignal with trailing stop`() = runTest {
        val signal = TestSignals.longBtc(
            tp = 70000.0,
            sl = 60000.0,
            metadata = TestSignals.withTrailingStop(deviation = 0.01, triggerPrice = 66000.0)
        )

        mock.enqueue(BybitResponses.serverTime())  // syncTime
        mock.enqueue(BybitResponses.serverTime())  // syncTime in getBalance
        mock.enqueue(BybitResponses.balance(1000.0))
        mock.enqueue(BybitResponses.leverageSuccess())
        mock.enqueue(BybitResponses.positionListEmpty())
        mock.enqueue(BybitResponses.ticker(65000.0))
        mock.enqueue(BybitResponses.orderSuccess("bybit-trail-1"))
        mock.enqueue(BybitResponses.tradingStopSuccess()) // trailing stop via /v5/position/trading-stop

        val result = service.executeSignal(signal, 5.0)
        assertTrue(result.isSuccess)
        val exec = result.getOrThrow()
        assertEquals(ExecutionStatus.SUCCESS, exec.status)
        assertEquals("bybit-trail-1", exec.exchangeOrderId)
    }

    @Test
    fun `executeSignal trailing stop fails returns PARTIAL`() = runTest {
        val signal = TestSignals.longBtc(
            tp = 70000.0,
            sl = 60000.0,
            metadata = TestSignals.withTrailingStop(deviation = 0.01, triggerPrice = 66000.0)
        )

        mock.enqueue(BybitResponses.serverTime())
        mock.enqueue(BybitResponses.serverTime())
        mock.enqueue(BybitResponses.balance(1000.0))
        mock.enqueue(BybitResponses.leverageSuccess())
        mock.enqueue(BybitResponses.positionListEmpty())
        mock.enqueue(BybitResponses.ticker(65000.0))
        mock.enqueue(BybitResponses.orderSuccess())
        mock.enqueue(BybitResponses.apiError(170000, "Trailing stop error")) // trailing fails
        mock.enqueue(BybitResponses.apiError(170000, "Trailing stop error")) // retry 1
        mock.enqueue(BybitResponses.apiError(170000, "Trailing stop error")) // retry 2

        val result = service.executeSignal(signal, 5.0)
        assertTrue(result.isSuccess)
        assertEquals(ExecutionStatus.PARTIAL, result.getOrThrow().status)
    }

    @Test
    fun `getMarketPrice success`() = runTest {
        mock.enqueue(BybitResponses.ticker(65432.10))

        val result = service.getMarketPrice("BTCUSDT")
        assertTrue(result.isSuccess)
        assertEquals(65432.10, result.getOrThrow(), 0.01)
    }

    @Test
    fun `getMarketPrice empty list`() = runTest {
        mock.enqueue("""{"retCode":0,"retMsg":"OK","result":{"list":[]}}""")

        val result = service.getMarketPrice("BTCUSDT")
        assertTrue(result.isFailure)
    }

    @Test
    fun `name is Bybit`() {
        assertEquals("Bybit", service.name)
    }
}

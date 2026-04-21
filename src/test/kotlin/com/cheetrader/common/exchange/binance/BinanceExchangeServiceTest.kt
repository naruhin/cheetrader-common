package com.cheetrader.common.exchange.binance

import com.cheetrader.common.model.ExecutionStatus
import com.cheetrader.common.testutil.BinanceResponses
import com.cheetrader.common.testutil.MockExchangeServer
import com.cheetrader.common.testutil.RecordingLogger
import com.cheetrader.common.testutil.TestSignals
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class BinanceExchangeServiceTest {
    private val mock = MockExchangeServer()
    private val logger = RecordingLogger()
    private lateinit var service: BinanceExchangeService

    @BeforeEach
    fun setup() {
        mock.start()
        service = BinanceExchangeService(
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
        // syncTime, loadExchangeInfo, syncTime (getBalance), balance
        mock.enqueue(BinanceResponses.serverTime())
        mock.enqueue(BinanceResponses.exchangeInfo())
        mock.enqueue(BinanceResponses.serverTime())
        mock.enqueue(BinanceResponses.balance(500.0))

        val result = service.testConnection()
        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow())
    }

    @Test
    fun `testConnection balance failure`() = runTest {
        mock.enqueue(BinanceResponses.serverTime())
        mock.enqueue(BinanceResponses.exchangeInfo())
        mock.enqueue(BinanceResponses.serverTime())
        mock.enqueue(BinanceResponses.apiError(-2015, "Invalid API-key"))

        val result = service.testConnection()
        assertTrue(result.isFailure)
    }

    // ===== getBalance =====

    @Test
    fun `getBalance success`() = runTest {
        mock.enqueue(BinanceResponses.serverTime())
        mock.enqueue(BinanceResponses.balance(1234.56))

        val result = service.getBalance()
        assertTrue(result.isSuccess)
        assertEquals(1234.56, result.getOrThrow(), 0.01)
    }

    @Test
    fun `getBalance USDT not found`() = runTest {
        mock.enqueue(BinanceResponses.serverTime())
        mock.enqueue(BinanceResponses.emptyBalance())

        val result = service.getBalance()
        assertTrue(result.isFailure)
    }

    @Test
    fun `getBalance API error`() = runTest {
        mock.enqueue(BinanceResponses.serverTime())
        mock.enqueue(BinanceResponses.apiError(-1000, "Some error"))

        val result = service.getBalance()
        assertTrue(result.isFailure)
    }

    // ===== executeSignal =====

    @Test
    fun `executeSignal LONG full flow success`() = runTest {
        val signal = TestSignals.longBtc(tp = 70000.0, sl = 60000.0)

        // syncTime, exchangeInfo, syncTime+balance (getBalance), positionSideDual,
        // marginType, leverage, tickerPrice, order, algoTP, algoSL
        mock.enqueue(BinanceResponses.serverTime())            // syncTime in executeSignal
        mock.enqueue(BinanceResponses.exchangeInfo())          // loadExchangeInfo
        mock.enqueue(BinanceResponses.serverTime())            // syncTime in getBalance
        mock.enqueue(BinanceResponses.balance(1000.0))         // balance
        mock.enqueue(BinanceResponses.positionSideDual(false)) // positionSideMode
        mock.enqueue(BinanceResponses.marginTypeAlreadySet())  // setupPosition margin
        mock.enqueue(BinanceResponses.leverageSuccess())       // setupPosition leverage
        mock.enqueue(BinanceResponses.tickerPrice(65000.0))    // getMarketPrice
        mock.enqueue(BinanceResponses.orderSuccess())          // placeOrder
        mock.enqueue(BinanceResponses.algoOrderSuccess())      // TP algo
        mock.enqueue(BinanceResponses.algoOrderSuccess())      // SL algo

        val result = service.executeSignal(signal, 5.0)
        assertTrue(result.isSuccess)
        val execution = result.getOrThrow()
        assertEquals(ExecutionStatus.SUCCESS, execution.status)
        assertEquals("123456", execution.exchangeOrderId)
    }

    @Test
    fun `executeSignal SHORT`() = runTest {
        val signal = TestSignals.shortEth(tp = 3000.0, sl = 3800.0)

        mock.enqueue(BinanceResponses.serverTime())
        mock.enqueue(BinanceResponses.exchangeInfo())
        mock.enqueue(BinanceResponses.serverTime())
        mock.enqueue(BinanceResponses.balance(1000.0))
        mock.enqueue(BinanceResponses.positionSideDual(false))
        mock.enqueue(BinanceResponses.marginTypeSuccess())
        mock.enqueue(BinanceResponses.leverageSuccess())
        mock.enqueue(BinanceResponses.tickerPrice(3500.0))
        mock.enqueue(BinanceResponses.orderSuccess(orderId = "short-456", avgPrice = "3500.0", qty = "1.000"))
        mock.enqueue(BinanceResponses.algoOrderSuccess())
        mock.enqueue(BinanceResponses.algoOrderSuccess())

        val result = service.executeSignal(signal, 5.0)
        assertTrue(result.isSuccess)
        assertEquals(ExecutionStatus.SUCCESS, result.getOrThrow().status)
    }

    @Test
    fun `executeSignal balance failure`() = runTest {
        val signal = TestSignals.longBtc()

        mock.enqueue(BinanceResponses.serverTime())
        mock.enqueue(BinanceResponses.exchangeInfo())
        mock.enqueue(BinanceResponses.serverTime())
        mock.enqueue(BinanceResponses.apiError(-2015, "Invalid key"))

        val result = service.executeSignal(signal, 5.0)
        assertTrue(result.isFailure)
    }

    @Test
    fun `executeSignal order rejected propagates Result failure`() = runTest {
        // Previously this returned Result.success(OrderExecution(FAILED, ...)) which
        // caused silent-success bugs in callers using .getOrElse{}. Now Binance
        // propagates the exception (Package A anti-pattern fix).
        val signal = TestSignals.longBtc()

        mock.enqueue(BinanceResponses.serverTime())
        mock.enqueue(BinanceResponses.exchangeInfo())
        mock.enqueue(BinanceResponses.serverTime())
        mock.enqueue(BinanceResponses.balance(1000.0))
        mock.enqueue(BinanceResponses.positionSideDual(false))
        mock.enqueue(BinanceResponses.marginTypeSuccess())
        mock.enqueue(BinanceResponses.leverageSuccess())
        mock.enqueue(BinanceResponses.tickerPrice(65000.0))
        mock.enqueue(BinanceResponses.apiError(-1100, "Illegal character"))

        val result = service.executeSignal(signal, 5.0)
        assertTrue(result.isFailure, "order rejection must surface as Result.failure")
        val ex = result.exceptionOrNull()
        assertNotNull(ex)
        assertTrue(ex is BinanceException, "exception must carry Binance error detail")
    }

    @Test
    fun `executeSignal TP fails returns PARTIAL`() = runTest {
        val signal = TestSignals.longBtc(tp = 70000.0, sl = 60000.0)

        mock.enqueue(BinanceResponses.serverTime())
        mock.enqueue(BinanceResponses.exchangeInfo())
        mock.enqueue(BinanceResponses.serverTime())
        mock.enqueue(BinanceResponses.balance(1000.0))
        mock.enqueue(BinanceResponses.positionSideDual(false))
        mock.enqueue(BinanceResponses.marginTypeSuccess())
        mock.enqueue(BinanceResponses.leverageSuccess())
        mock.enqueue(BinanceResponses.tickerPrice(65000.0))
        mock.enqueue(BinanceResponses.orderSuccess())
        mock.enqueue(BinanceResponses.apiError(-1100, "TP failed"))   // TP algo fails
        mock.enqueue(BinanceResponses.algoOrderSuccess())             // SL algo succeeds

        val result = service.executeSignal(signal, 5.0)
        assertTrue(result.isSuccess)
        assertEquals(ExecutionStatus.PARTIAL, result.getOrThrow().status)
    }

    @Test
    fun `executeSignal no TP SL only main order`() = runTest {
        val signal = TestSignals.longBtc(tp = null, sl = null)

        mock.enqueue(BinanceResponses.serverTime())
        mock.enqueue(BinanceResponses.exchangeInfo())
        mock.enqueue(BinanceResponses.serverTime())
        mock.enqueue(BinanceResponses.balance(1000.0))
        mock.enqueue(BinanceResponses.positionSideDual(false))
        mock.enqueue(BinanceResponses.marginTypeSuccess())
        mock.enqueue(BinanceResponses.leverageSuccess())
        mock.enqueue(BinanceResponses.tickerPrice(65000.0))
        mock.enqueue(BinanceResponses.orderSuccess())

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

        mock.enqueue(BinanceResponses.serverTime())
        mock.enqueue(BinanceResponses.exchangeInfo())
        mock.enqueue(BinanceResponses.serverTime())
        mock.enqueue(BinanceResponses.balance(1000.0))
        mock.enqueue(BinanceResponses.positionSideDual(false))
        mock.enqueue(BinanceResponses.marginTypeSuccess())
        mock.enqueue(BinanceResponses.leverageSuccess())
        mock.enqueue(BinanceResponses.tickerPrice(65000.0))
        mock.enqueue(BinanceResponses.orderSuccess())
        mock.enqueue(BinanceResponses.algoOrderSuccess()) // TP
        mock.enqueue(BinanceResponses.algoOrderSuccess()) // SL
        mock.enqueue(BinanceResponses.algoOrderSuccess()) // Trailing

        val result = service.executeSignal(signal, 5.0)
        assertTrue(result.isSuccess)
        assertEquals(ExecutionStatus.SUCCESS, result.getOrThrow().status)
    }

    @Test
    fun `executeSignal dual position mode`() = runTest {
        val signal = TestSignals.longBtc(tp = 70000.0, sl = 60000.0)

        mock.enqueue(BinanceResponses.serverTime())
        mock.enqueue(BinanceResponses.exchangeInfo())
        mock.enqueue(BinanceResponses.serverTime())
        mock.enqueue(BinanceResponses.balance(1000.0))
        mock.enqueue(BinanceResponses.positionSideDual(true)) // dual mode
        mock.enqueue(BinanceResponses.marginTypeSuccess())
        mock.enqueue(BinanceResponses.leverageSuccess())
        mock.enqueue(BinanceResponses.tickerPrice(65000.0))
        mock.enqueue(BinanceResponses.orderSuccess())
        mock.enqueue(BinanceResponses.algoOrderSuccess()) // TP
        mock.enqueue(BinanceResponses.algoOrderSuccess()) // SL

        val result = service.executeSignal(signal, 5.0)
        assertTrue(result.isSuccess)
        assertEquals(ExecutionStatus.SUCCESS, result.getOrThrow().status)
    }

    // ===== getOpenPositionsCount =====

    @Test
    fun `getOpenPositionsCount success`() = runTest {
        mock.enqueue(BinanceResponses.serverTime())
        mock.enqueue(BinanceResponses.positionRisk("BTCUSDT", 0.01))

        val result = service.getOpenPositionsCount()
        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrThrow())
    }

    @Test
    fun `getOpenPositionsCount empty`() = runTest {
        mock.enqueue(BinanceResponses.serverTime())
        mock.enqueue(BinanceResponses.positionRiskEmpty())

        val result = service.getOpenPositionsCount()
        assertTrue(result.isSuccess)
        assertEquals(0, result.getOrThrow())
    }

    // ===== closePosition =====

    @Test
    fun `closePosition success`() = runTest {
        mock.enqueue(BinanceResponses.serverTime())
        mock.enqueue(BinanceResponses.positionRisk("BTCUSDT", 0.01, "BOTH"))
        mock.enqueue(BinanceResponses.orderSuccess())

        val result = service.closePosition("BTCUSDT")
        assertTrue(result.isSuccess)
        assertEquals(ExecutionStatus.SUCCESS, result.getOrThrow().status)
    }

    @Test
    fun `closePosition no positions`() = runTest {
        mock.enqueue(BinanceResponses.serverTime())
        mock.enqueue(BinanceResponses.positionRiskEmpty())

        val result = service.closePosition("BTCUSDT")
        assertTrue(result.isSuccess)
        assertEquals(ExecutionStatus.SUCCESS, result.getOrThrow().status)
    }

    // ===== closeAllPositions =====

    @Test
    fun `closeAllPositions success`() = runTest {
        mock.enqueue(BinanceResponses.serverTime())
        mock.enqueue(BinanceResponses.positionRisk("BTCUSDT", 0.01))
        mock.enqueue(BinanceResponses.orderSuccess())

        val result = service.closeAllPositions()
        assertTrue(result.isSuccess)
    }

    @Test
    fun `closeAllPositions empty`() = runTest {
        mock.enqueue(BinanceResponses.serverTime())
        mock.enqueue(BinanceResponses.positionRiskEmpty())

        val result = service.closeAllPositions()
        assertTrue(result.isSuccess)
    }

    // ===== cancelAllOrders =====

    @Test
    fun `cancelAllOrders success`() = runTest {
        mock.enqueue(BinanceResponses.serverTime())
        mock.enqueue(BinanceResponses.cancelSuccess())

        val result = service.cancelAllOrders("BTCUSDT")
        assertTrue(result.isSuccess)
    }

    @Test
    fun `cancelAllOrders API error`() = runTest {
        mock.enqueue(BinanceResponses.serverTime())
        mock.enqueue(BinanceResponses.apiError(-1000, "Unknown error"))

        val result = service.cancelAllOrders("BTCUSDT")
        assertTrue(result.isFailure)
    }

    // ===== getMarketPrice =====

    @Test
    fun `getMarketPrice success`() = runTest {
        mock.enqueue(BinanceResponses.tickerPrice(65432.10))

        val result = service.getMarketPrice("BTCUSDT")
        assertTrue(result.isSuccess)
        assertEquals(65432.10, result.getOrThrow(), 0.01)
    }

    @Test
    fun `getMarketPrice no response`() = runTest {
        mock.enqueue("", 500)

        val result = service.getMarketPrice("BTCUSDT")
        assertTrue(result.isFailure)
    }

    @Test
    fun `name is Binance`() {
        assertEquals("Binance", service.name)
    }
}

package com.cheetrader.common.exchange.binance

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class BinanceSymbolConversionTest {
    private val service = BinanceExchangeService(
        apiKey = "test",
        secretKey = "test",
        baseUrlOverride = "http://localhost:1"
    )

    @Test fun `convertSymbol BTCUSDT unchanged`() = assertEquals("BTCUSDT", service.convertSymbol("BTCUSDT"))
    @Test fun `convertSymbol lowercase`() = assertEquals("ETHUSDT", service.convertSymbol("ethusdt"))
    @Test fun `convertSymbol with dash`() = assertEquals("BTCUSDT", service.convertSymbol("BTC-USDT"))
    @Test fun `convertSymbol with underscore`() = assertEquals("BTCUSDT", service.convertSymbol("BTC_USDT"))
    @Test fun `convertSymbol strip SWAP suffix`() = assertEquals("BTCUSDT", service.convertSymbol("BTC-USDT-SWAP"))

    @Test fun `quantity precision BTC`() = assertEquals(3, service.getQuantityPrecision("BTCUSDT"))
    @Test fun `quantity precision ETH`() = assertEquals(3, service.getQuantityPrecision("ETHUSDT"))
    @Test fun `quantity precision SOL`() = assertEquals(2, service.getQuantityPrecision("SOLUSDT"))
    @Test fun `quantity precision XRP`() = assertEquals(1, service.getQuantityPrecision("XRPUSDT"))
    @Test fun `quantity precision DOGE`() = assertEquals(0, service.getQuantityPrecision("DOGEUSDT"))
    @Test fun `quantity precision unknown`() = assertEquals(1, service.getQuantityPrecision("UNKNOWNUSDT"))

    @Test fun `calculateQuantity BTC`() {
        val qty = service.calculateQuantity(1000.0, 65000.0, "BTCUSDT")
        assertTrue(qty > BigDecimal.ZERO, "Should be positive: $qty")
        assertTrue(qty.scale() <= 3, "Precision should be <= 3: $qty")
    }

    @Test fun `calculateQuantity rounds down`() {
        val qty = service.calculateQuantity(100.0, 65000.0, "BTCUSDT")
        // 100 / 65000 = 0.001538... rounds down to 0.001
        assertEquals(BigDecimal("0.001"), qty)
    }

    @Test fun `sanitizeTpSl valid long`() {
        val (tp, sl) = service.sanitizeTpSl(true, 65000.0, 70000.0, 60000.0)
        assertEquals(70000.0, tp)
        assertEquals(60000.0, sl)
    }

    @Test fun `sanitizeTpSl invalid TP for long`() {
        val (tp, sl) = service.sanitizeTpSl(true, 65000.0, 60000.0, 60000.0)
        assertNull(tp, "TP below entry should be null for LONG")
        assertEquals(60000.0, sl)
    }

    @Test fun `sanitizeTpSl invalid SL for long`() {
        val (tp, sl) = service.sanitizeTpSl(true, 65000.0, 70000.0, 70000.0)
        assertEquals(70000.0, tp)
        assertNull(sl, "SL above entry should be null for LONG")
    }

    @Test fun `sanitizeTpSl valid short`() {
        val (tp, sl) = service.sanitizeTpSl(false, 65000.0, 60000.0, 70000.0)
        assertEquals(60000.0, tp)
        assertEquals(70000.0, sl)
    }

    @Test fun `sanitizeTpSl null inputs`() {
        val (tp, sl) = service.sanitizeTpSl(true, 65000.0, null, null)
        assertNull(tp)
        assertNull(sl)
    }
}

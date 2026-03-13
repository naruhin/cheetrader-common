package com.cheetrader.common.exchange.bybit

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class BybitSymbolConversionTest {
    private val service = BybitExchangeService(
        apiKey = "test",
        secretKey = "test",
        baseUrlOverride = "http://localhost:1"
    )

    @Test fun `convertSymbol BTCUSDT unchanged`() = assertEquals("BTCUSDT", service.convertSymbol("BTCUSDT"))
    @Test fun `convertSymbol lowercase`() = assertEquals("ETHUSDT", service.convertSymbol("ethusdt"))
    @Test fun `convertSymbol with dash`() = assertEquals("BTCUSDT", service.convertSymbol("BTC-USDT"))
    @Test fun `convertSymbol strip SWAP suffix`() = assertEquals("BTCUSDT", service.convertSymbol("BTC-USDT-SWAP"))

    @Test fun `quantity precision BTC`() = assertEquals(3, service.getQuantityPrecision("BTCUSDT"))
    @Test fun `quantity precision DOGE`() = assertEquals(0, service.getQuantityPrecision("DOGEUSDT"))

    @Test fun `calculateQuantity rounds down`() {
        val qty = service.calculateQuantity(100.0, 65000.0, "BTCUSDT")
        assertEquals(BigDecimal("0.001"), qty)
    }

    @Test fun `sanitizeTpSl valid long`() {
        val (tp, sl) = service.sanitizeTpSl(true, 65000.0, 70000.0, 60000.0)
        assertEquals(70000.0, tp)
        assertEquals(60000.0, sl)
    }

    @Test fun `sanitizeTpSl invalid TP for short`() {
        val (tp, sl) = service.sanitizeTpSl(false, 65000.0, 70000.0, 70000.0)
        assertNull(tp, "TP above entry should be null for SHORT")
        assertEquals(70000.0, sl)
    }
}

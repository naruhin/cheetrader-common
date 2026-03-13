package com.cheetrader.common.exchange.bingx

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class BingXSymbolConversionTest {
    private val service = BingXExchangeService(
        apiKey = "test",
        secretKey = "test",
        baseUrlOverride = "http://localhost:1"
    )

    @Test fun `convertSymbol BTCUSDT to BTC-USDT`() = assertEquals("BTC-USDT", service.convertSymbol("BTCUSDT"))
    @Test fun `convertSymbol already has dash`() = assertEquals("BTC-USDT", service.convertSymbol("BTC-USDT"))
    @Test fun `convertSymbol ETHUSDC`() = assertEquals("ETH-USDC", service.convertSymbol("ETHUSDC"))
    @Test fun `convertSymbol no known quote`() = assertEquals("SOMETOKEN", service.convertSymbol("SOMETOKEN"))

    @Test fun `calculateQuantity BTC precision 3`() {
        val qty = service.calculateQuantity(1000.0, 65000.0, "BTC-USDT")
        assertTrue(qty.scale() <= 3)
    }

    @Test fun `calculateQuantity ETH precision 2`() {
        val qty = service.calculateQuantity(1000.0, 3500.0, "ETH-USDT")
        assertTrue(qty.scale() <= 2)
    }

    @Test fun `calculateQuantity other coins precision 0`() {
        val qty = service.calculateQuantity(1000.0, 0.15, "DOGE-USDT")
        assertEquals(0, qty.scale())
    }

    @Test fun `sanitizeTpSl valid long`() {
        val (tp, sl) = service.sanitizeTpSl(true, 65000.0, 70000.0, 60000.0)
        assertEquals(70000.0, tp)
        assertEquals(60000.0, sl)
    }

    @Test fun `sanitizeTpSl zero reference price passes through`() {
        val (tp, sl) = service.sanitizeTpSl(true, 0.0, 70000.0, 60000.0)
        assertEquals(70000.0, tp)
        assertEquals(60000.0, sl)
    }
}

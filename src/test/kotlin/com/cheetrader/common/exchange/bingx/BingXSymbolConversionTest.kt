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

    // ─── 1000X scaling (2026-04-23 fix) ───────────────────────────────────

    // convertSymbol must strip the `1000` prefix so the placed instrument
    // (e.g. `SHIB-USDT`) actually exists on BingX. Pre-2026-04-23 the adapter
    // built `1000SHIB-USDT` which BingX rejected as "symbol not found".
    @Test fun `convertSymbol strips 1000 prefix for memecoin`() =
        assertEquals("SHIB-USDT", service.convertSymbol("1000SHIBUSDT"))
    @Test fun `convertSymbol strips 1000 prefix for PEPE`() =
        assertEquals("PEPE-USDT", service.convertSymbol("1000PEPEUSDT"))
    @Test fun `convertSymbol strips 10000 prefix`() =
        assertEquals("ELON-USDT", service.convertSymbol("10000ELONUSDT"))
    @Test fun `convertSymbol strips 1M prefix`() =
        assertEquals("BABYDOGE-USDT", service.convertSymbol("1MBABYDOGEUSDT"))
    // Don't strip the "1" from 1INCH — it's a regular ticker, not a multiplier.
    @Test fun `convertSymbol leaves 1INCH alone`() =
        assertEquals("1INCH-USDT", service.convertSymbol("1INCHUSDT"))

    // signalScalingFactor handles both signal format and BingX-native format.
    @Test fun `scalingFactor 1000SHIBUSDT is 1000`() =
        assertEquals(1000.0, service.signalScalingFactor("1000SHIBUSDT"))
    @Test fun `scalingFactor SHIB-USDT via whitelist is 1000`() =
        assertEquals(1000.0, service.signalScalingFactor("SHIB-USDT"))
    @Test fun `scalingFactor SHIBUSDT via whitelist is 1000`() =
        assertEquals(1000.0, service.signalScalingFactor("SHIBUSDT"))
    @Test fun `scalingFactor BTCUSDT is 1`() =
        assertEquals(1.0, service.signalScalingFactor("BTCUSDT"))
    @Test fun `scalingFactor 1INCHUSDT is 1 (no scale)`() =
        assertEquals(1.0, service.signalScalingFactor("1INCHUSDT"))
    @Test fun `scalingFactor 10000ELONUSDT is 10000`() =
        assertEquals(10_000.0, service.signalScalingFactor("10000ELONUSDT"))

    // denormalizeSymbol restores `1000` for BingX-native memecoin symbols so
    // polled positions match ActiveTrade.symbol in MainViewModel.
    @Test fun `denormalize SHIB-USDT restores 1000 prefix`() =
        assertEquals("1000SHIBUSDT", service.denormalizeSymbol("SHIB-USDT"))
    @Test fun `denormalize BTC-USDT stays without prefix`() =
        assertEquals("BTCUSDT", service.denormalizeSymbol("BTC-USDT"))
}

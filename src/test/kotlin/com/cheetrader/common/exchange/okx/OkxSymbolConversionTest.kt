package com.cheetrader.common.exchange.okx

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class OkxSymbolConversionTest {
    private val service = OkxExchangeService(
        apiKey = "test",
        secretKey = "test",
        passphrase = "test",
        baseUrlOverride = "http://localhost:1"
    )

    @Test fun `convertSymbol BTCUSDT to BTC-USDT-SWAP`() = assertEquals("BTC-USDT-SWAP", service.convertSymbol("BTCUSDT"))
    @Test fun `convertSymbol already has dash`() = assertEquals("BTC-USDT-SWAP", service.convertSymbol("BTC-USDT"))
    @Test fun `convertSymbol already has SWAP`() = assertEquals("BTC-USDT-SWAP", service.convertSymbol("BTC-USDT-SWAP"))
    @Test fun `convertSymbol with underscore`() = assertEquals("BTC-USDT-SWAP", service.convertSymbol("BTC_USDT"))
    @Test fun `convertSymbol ETHUSDC`() = assertEquals("ETH-USDC-SWAP", service.convertSymbol("ETHUSDC"))
    @Test fun `convertSymbol lowercase`() = assertEquals("BTC-USDT-SWAP", service.convertSymbol("btcusdt"))
    @Test fun `convertSymbol unknown suffix`() {
        val result = service.convertSymbol("SOMETOKEN")
        // No known quote found, returns as-is uppercase
        assertEquals("SOMETOKEN", result)
    }
    @Test fun `convertSymbol strips 1000 prefix`() = assertEquals("BONK-USDT-SWAP", service.convertSymbol("1000BONKUSDT"))
    @Test fun `convertSymbol strips 1000 prefix SHIB`() = assertEquals("SHIB-USDT-SWAP", service.convertSymbol("1000SHIBUSDT"))
    @Test fun `convertSymbol strips 1000 prefix PEPE`() = assertEquals("PEPE-USDT-SWAP", service.convertSymbol("1000PEPEUSDT"))

    @Test fun `sanitizeTpSl valid long`() {
        val (tp, sl) = service.sanitizeTpSl(true, 65000.0, 70000.0, 60000.0)
        assertEquals(70000.0, tp)
        assertEquals(60000.0, sl)
    }

    @Test fun `sanitizeTpSl invalid for short`() {
        val (tp, sl) = service.sanitizeTpSl(false, 3500.0, 4000.0, 3000.0)
        assertNull(tp, "TP above entry invalid for SHORT")
        assertNull(sl, "SL below entry invalid for SHORT")
    }
}

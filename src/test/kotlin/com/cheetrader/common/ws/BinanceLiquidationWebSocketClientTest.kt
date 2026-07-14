package com.cheetrader.common.ws

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BinanceLiquidationWebSocketClientTest {

    private fun clientWith(sink: MutableList<LiquidationEvent>) =
        BinanceLiquidationWebSocketClient(listener = { sink.add(it) })

    // Real Binance !forceOrder@arr payload shape (bare /ws/ delivery).
    private val sellLiq = """
        {"e":"forceOrder","E":1568014460893,"o":{"s":"BTCUSDT","S":"SELL","o":"LIMIT","f":"IOC",
        "q":"0.014","p":"9910","ap":"9910","X":"FILLED","l":"0.014","z":"0.014","T":1568014460893}}
    """.trimIndent()

    @Test
    fun `parses core fields and computes notional`() {
        val e = clientWith(mutableListOf()).parse(sellLiq)!!
        assertEquals("BTCUSDT", e.symbol)
        assertEquals("SELL", e.side)
        assertEquals(9910.0, e.price)
        assertEquals(0.014, e.quantity)
        assertEquals(9910.0 * 0.014, e.notionalUsd, 1e-9)
        assertEquals(1568014460893L, e.tradeTimeMs)
    }

    @Test
    fun `SELL liquidation order means a LONG was liquidated`() {
        val e = clientWith(mutableListOf()).parse(sellLiq)!!
        assertEquals("LONG", e.liquidatedSide)
    }

    @Test
    fun `BUY liquidation order means a SHORT was liquidated`() {
        val buy = sellLiq.replace("\"S\":\"SELL\"", "\"S\":\"BUY\"")
        assertEquals("SHORT", clientWith(mutableListOf()).parse(buy)!!.liquidatedSide)
    }

    @Test
    fun `parses combined-stream envelope under data`() {
        val wrapped = """{"stream":"!forceOrder@arr","data":$sellLiq}"""
        val e = clientWith(mutableListOf()).parse(wrapped)
        assertNotNull(e)
        assertEquals("BTCUSDT", e!!.symbol)
    }

    @Test
    fun `malformed and non-liquidation messages return null`() {
        val c = clientWith(mutableListOf())
        assertNull(c.parse("not json"))
        assertNull(c.parse("""{"e":"other","E":1}"""))
        assertNull(c.parse("""{"o":{"s":"BTCUSDT"}}"""))  // missing side/price/qty
    }

    @Test
    fun `dedup suppresses replayed identical liquidation`() {
        val sink = mutableListOf<LiquidationEvent>()
        val c = clientWith(sink)
        c.handleMessage(sellLiq)
        c.handleMessage(sellLiq)  // network replay
        assertEquals(1, sink.size)
    }

    @Test
    fun `distinct liquidations are both delivered`() {
        val sink = mutableListOf<LiquidationEvent>()
        val c = clientWith(sink)
        c.handleMessage(sellLiq)
        c.handleMessage(sellLiq.replace("1568014460893", "1568014460999"))
        assertEquals(2, sink.size)
    }
}

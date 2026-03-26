package com.cheetrader.common.exchange

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ExchangeUtilsTest {
    @Test
    fun `extractTrailingParams returns params even when trailingActive is false`() {
        // trailingActive is a server-side runtime state, not relevant for exchange order placement
        val params = extractTrailingParams(mapOf(
            "trailingActive" to "false",
            "trailingDeviation" to "0.01",
            "triggerPrice" to "66000.0"
        ))
        assertNotNull(params)
        assertEquals(0.01, params!!.deviation, 0.0001)
        assertEquals(66000.0, params.triggerPrice!!, 0.1)
    }

    @Test
    fun `extractTrailingParams returns null when missing deviation`() {
        assertNull(extractTrailingParams(mapOf("trailingActive" to "true")))
    }

    @Test
    fun `extractTrailingParams returns null when deviation is zero`() {
        assertNull(extractTrailingParams(mapOf("trailingDeviation" to "0.0")))
    }

    @Test
    fun `extractTrailingParams returns null when deviation is negative`() {
        assertNull(extractTrailingParams(mapOf("trailingDeviation" to "-0.01")))
    }

    @Test
    fun `extractTrailingParams with valid params`() {
        val params = extractTrailingParams(mapOf(
            "trailingActive" to "true",
            "trailingDeviation" to "0.01",
            "triggerPrice" to "66000.0"
        ))
        assertNotNull(params)
        assertEquals(0.01, params!!.deviation, 0.0001)
        assertEquals(66000.0, params.triggerPrice!!, 0.1)
    }

    @Test
    fun `extractTrailingParams without trigger price`() {
        val params = extractTrailingParams(mapOf(
            "trailingDeviation" to "0.02"
        ))
        assertNotNull(params)
        assertEquals(0.02, params!!.deviation, 0.0001)
        assertNull(params.triggerPrice)
    }

    @Test
    fun `extractTrailingParams with empty map`() {
        assertNull(extractTrailingParams(emptyMap()))
    }
}

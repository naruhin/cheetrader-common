package com.cheetrader.common.model

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.Locale

class ModelsTest {
    @Test fun `formatPrice zero`() = assertEquals("0", formatPrice(0.0))

    @Test fun `formatPrice BTC-level`() {
        // formatPrice uses String.format which is locale-dependent (comma vs dot)
        // normalize by replacing comma with dot for assertion
        val result = formatPrice(60000.12).replace(',', '.')
        assertEquals("60000.12", result)
    }

    @Test fun `formatPrice ETH-level`() {
        val result = formatPrice(3000.12).replace(',', '.')
        assertEquals("3000.12", result)
    }

    @Test fun `formatPrice SOL-level`() {
        val result = formatPrice(145.12).replace(',', '.')
        assertEquals("145.12", result)
    }

    @Test fun `formatPrice small coin`() {
        val result = formatPrice(0.1534).replace(',', '.')
        assertTrue(result.startsWith("0.15"), "Expected starts with 0.15, got $result")
    }

    @Test fun `formatPrice very small coin`() {
        val result = formatPrice(0.00002134).replace(',', '.')
        assertTrue(result.contains("0.0000213"), "Expected contains 0.0000213, got $result")
    }

    @Test fun `formatPrice negative`() {
        val result = formatPrice(-100.5)
        assertTrue(result.startsWith("-"), "Expected negative, got $result")
    }

    @Test fun `formatPricePlain no scientific notation`() {
        val result = formatPricePlain(0.00001234)
        assertFalse(result.contains("E"), "Should not use scientific notation: $result")
        assertTrue(result.startsWith("0.0000"), "Should start with 0.0000, got $result")
    }

    @Test fun `formatPricePlain large number`() {
        val result = formatPricePlain(100000.0)
        assertEquals("100000", result)
    }

    @Test fun `formatPricePlain strips trailing zeros`() {
        val result = formatPricePlain(1.50)
        assertEquals("1.5", result)
    }
}

package com.cheetrader.common.ws

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.random.Random

class ExponentialBackoffTest {

    @Test
    fun `grows exponentially without jitter`() {
        val b = ExponentialBackoff(initialMs = 1_000, maxMs = 60_000, multiplier = 2.0, jitter = 0.0)
        assertEquals(1_000L, b.next())
        assertEquals(2_000L, b.next())
        assertEquals(4_000L, b.next())
        assertEquals(8_000L, b.next())
    }

    @Test
    fun `caps at maxMs`() {
        val b = ExponentialBackoff(initialMs = 1_000, maxMs = 5_000, multiplier = 2.0, jitter = 0.0)
        repeat(20) { b.next() }
        assertEquals(5_000L, b.next())
    }

    @Test
    fun `reset returns to initial`() {
        val b = ExponentialBackoff(initialMs = 1_000, maxMs = 60_000, multiplier = 2.0, jitter = 0.0)
        b.next(); b.next(); b.next()
        b.reset()
        assertEquals(1_000L, b.next())
    }

    @Test
    fun `jitter stays within bounds and never exceeds maxMs`() {
        val b = ExponentialBackoff(
            initialMs = 1_000, maxMs = 10_000, multiplier = 2.0, jitter = 0.2, random = Random(42),
        )
        repeat(200) {
            val d = b.next()
            assertTrue(d in 0L..10_000L, "delay out of range: $d")
        }
    }
}

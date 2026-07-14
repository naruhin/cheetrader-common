package com.cheetrader.common.ws

import kotlin.math.pow
import kotlin.random.Random

/**
 * Exponential back-off with proportional jitter for WS reconnect scheduling.
 *
 * Not thread-safe by itself: guard [next]/[reset] with the same monitor as the
 * reconnect state machine (a single reconnect executor thread satisfies this).
 */
class ExponentialBackoff(
    private val initialMs: Long = 1_000,
    private val maxMs: Long = 60_000,
    private val multiplier: Double = 2.0,
    /** Proportional jitter, e.g. 0.2 => +/-20% of the base delay. */
    private val jitter: Double = 0.2,
    private val random: Random = Random.Default,
) {
    private var attempt = 0

    /** Next delay in ms, then advances the attempt counter. */
    fun next(): Long {
        val base = (initialMs * multiplier.pow(attempt)).coerceAtMost(maxMs.toDouble())
        if (attempt < MAX_ATTEMPT_EXP) attempt++
        val jitterRange = base * jitter
        val delta = if (jitterRange > 0.0) random.nextDouble(-jitterRange, jitterRange) else 0.0
        return (base + delta).toLong().coerceIn(0L, maxMs)
    }

    /** Reset to the initial delay (call after a stable reconnect). */
    fun reset() {
        attempt = 0
    }

    private companion object {
        // Cap the exponent so multiplier.pow() can't overflow; base is coerced to maxMs anyway.
        const val MAX_ATTEMPT_EXP = 30
    }
}

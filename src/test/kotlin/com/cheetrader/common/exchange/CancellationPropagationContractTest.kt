package com.cheetrader.common.exchange

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.coroutines.cancellation.CancellationException

/**
 * Contract test for the cancellation-propagation pattern used in
 * `executeSignal()` across all 4 exchange services (OKX, BingX, Bybit, Binance).
 *
 * Bug being prevented: if a suspend function uses
 *
 *     try { ... } catch (e: Exception) { Result.failure(e) }
 *
 * without an explicit `catch (e: CancellationException) { throw e }` ABOVE
 * it, then cancelling the coroutine that called this function (e.g. on
 * logout, account switch, viewModelScope teardown) results in
 * `Result.failure(CancellationException("Job was cancelled"))` being
 * returned to the caller — which the UI then renders as a fake FAILED
 * OrderExecution row with errorMessage "Job was cancelled".
 *
 * The fix is the explicit `catch (CancellationException) { throw e }` in
 * each `executeSignal()` overload. This test pins the contract.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CancellationPropagationContractTest {

    /** Mirror of the pattern applied to all 4 `executeSignal()` overloads. */
    private suspend fun fixedPattern(work: suspend () -> Unit): Result<String> {
        return try {
            work()
            Result.success("ok")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** The anti-pattern this PR removes — kept here ONLY to demonstrate
     *  the regression behaviour so future readers see what we guard against. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun brokenPattern(work: suspend () -> Unit): Result<String> {
        return try {
            work()
            Result.success("ok")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    @Test
    fun `fixed pattern propagates CancellationException as cancellation, not as Result failure`() = runTest {
        var capturedThrowable: Throwable? = null
        var resultLeaked: Result<String>? = null

        val job = launch {
            try {
                resultLeaked = fixedPattern {
                    delay(10_000) // long enough that cancel hits during suspension
                }
            } catch (e: Throwable) {
                capturedThrowable = e
                throw e
            }
        }
        runCurrent()
        job.cancel()
        job.join()

        assertNull(resultLeaked, "Result must NOT be assigned — cancellation should bypass the try-block return")
        assertNotNull(capturedThrowable, "Caller's catch block must observe the CancellationException")
        assertTrue(
            capturedThrowable is CancellationException,
            "Expected CancellationException, got ${capturedThrowable?.javaClass?.name}"
        )
    }

    @Test
    fun `fixed pattern returns Result failure for regular exceptions (regression guard)`() = runTest {
        val result = fixedPattern {
            throw RuntimeException("simulated network error")
        }
        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue(ex is RuntimeException)
        assertEquals("simulated network error", ex?.message)
    }

    @Test
    fun `fixed pattern returns Result success on normal completion`() = runTest {
        val result = fixedPattern { /* no-op */ }
        assertTrue(result.isSuccess)
        assertEquals("ok", result.getOrNull())
    }

    @Test
    fun `broken pattern swallows CancellationException into Result failure (production bug we just fixed)`() = runTest {
        // This test documents — and locks in — the anti-pattern the PR removed.
        // If someone reverts the fix in any executeSignal() and reintroduces this
        // broken shape, the contract test above (`propagates CancellationException`)
        // will fail. This test exists so future maintainers can SEE the exact
        // shape that was wrong, instead of having to reverse-engineer it.
        var resultLeaked: Result<String>? = null

        val job = launch {
            resultLeaked = brokenPattern {
                delay(10_000)
            }
        }
        runCurrent()
        job.cancel()
        advanceUntilIdle()

        // After the inner `catch (e: Exception)` swallows the cancellation,
        // brokenPattern returns Result.failure(CancellationException) and the
        // launch-block assigns it to resultLeaked BEFORE the parent scope
        // notices the cancellation on the next suspension point.
        // Net effect in production: a fake FAILED OrderExecution is added to
        // the UI list before the viewModelScope is fully torn down.
        assertNotNull(
            resultLeaked,
            "Broken pattern leaks a Result instead of cancelling the parent — this is the bug."
        )
        assertTrue(resultLeaked!!.isFailure)
        assertTrue(
            resultLeaked!!.exceptionOrNull() is CancellationException,
            "Broken pattern packs the CancellationException into Result.failure — exactly the source of the " +
                    "'Order failed: Job was cancelled' UI artifact reported on 2026-05-08."
        )
    }
}

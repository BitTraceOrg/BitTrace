package org.bittrace.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `catching` exists to *not* be `runCatching`.
 *
 * The distinction only shows up on cancellation, which is exactly the case the
 * git and OAuth layers hit when someone presses Stop: `runCatching` turns it
 * into an ordinary failure, so the operation reports an error for something the
 * user asked for and the scope stops unwinding.
 */
class CatchingTest {

    @Test
    fun `success carries the value`() {
        assertEquals(7, catching { 7 }.getOrNull())
    }

    @Test
    fun `an ordinary failure is captured`() {
        val result = catching { error("boom") }
        assertTrue(result.isFailure)
        assertEquals("boom", result.exceptionOrNull()?.message)
    }

    @Test
    fun `cancellation is rethrown, not captured`() {
        val thrown = runCatching { catching { throw CancellationException("stopped") } }
        assertTrue(thrown.exceptionOrNull() is CancellationException)
    }

    @Test
    fun `a cancelled job stays cancelled`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val outcome = CompletableDeferred<Boolean>()

        val job = launch {
            catching {
                started.complete(Unit)
                // Suspends until cancelled; `catching` must let that through.
                CompletableDeferred<Unit>().await()
            }
            outcome.complete(false)
        }

        started.await()
        job.cancel()
        job.join()

        assertTrue(job.isCancelled, "the job must report cancellation")
        assertFalse(outcome.isCompleted, "`catching` swallowing the cancellation would let the body run on")
    }
}

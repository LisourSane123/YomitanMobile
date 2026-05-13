package com.yomitanmobile.ui.detail

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate's contract:
 *  - awaitDecision() suspends until someone calls resolve() with a value.
 *  - resolve() returns the parked coroutine with the supplied choice.
 *  - resolve() without an awaiter is a harmless no-op.
 *  - Cancelling the awaiter throws CancellationException and clears the
 *    internal state so the next awaitDecision() starts clean.
 */
class AiFailureGateTest {

    @Test
    fun resolveWithContinue_returnsContinueChoice() = runTest {
        val gate = AiFailureGate()
        val awaiter = async { gate.awaitDecision() }

        // Let the coroutine actually park on the deferred before we
        // resolve — without this the awaiter may not have started yet.
        runCurrent()

        gate.resolve(AiFailureChoice.CONTINUE_WITHOUT_AI)
        assertEquals(AiFailureChoice.CONTINUE_WITHOUT_AI, awaiter.await())
    }

    @Test
    fun resolveWithCancel_returnsCancelChoice() = runTest {
        val gate = AiFailureGate()
        val awaiter = async { gate.awaitDecision() }
        runCurrent()

        gate.resolve(AiFailureChoice.CANCEL_EXPORT)
        assertEquals(AiFailureChoice.CANCEL_EXPORT, awaiter.await())
    }

    @Test
    fun resolveWithoutAwaiter_isNoOp() = runTest {
        // No coroutine has parked yet. The dialog dismiss handler may
        // call resolve() during teardown — it must not throw.
        val gate = AiFailureGate()
        gate.resolve(AiFailureChoice.CONTINUE_WITHOUT_AI)
        gate.resolve(AiFailureChoice.CANCEL_EXPORT)
        // No assertion needed — the absence of an exception is the
        // contract.
    }

    @Test
    fun staleResolveBeforeNewAwait_doesNotCompleteFutureAwaiter() = runTest {
        // If a stale resolve() lands before a fresh awaitDecision(), the
        // new awaiter must NOT pick up the old value. (Verifies the gate
        // doesn't accidentally buffer resolve() calls.)
        val gate = AiFailureGate()

        // Complete one round-trip cleanly so any internal state is
        // exercised.
        val first = async { gate.awaitDecision() }
        runCurrent()
        gate.resolve(AiFailureChoice.CONTINUE_WITHOUT_AI)
        first.await()

        // A stale resolve before a new awaiter shows up — should be a
        // no-op since nothing is parked.
        gate.resolve(AiFailureChoice.CANCEL_EXPORT)

        // The new awaiter must NOT see the stale value.
        val second = async { gate.awaitDecision() }
        runCurrent()
        // No resolve() yet → still suspended.
        assertFalse("awaiter completed without resolve()", second.isCompleted)

        gate.resolve(AiFailureChoice.CONTINUE_WITHOUT_AI)
        assertEquals(AiFailureChoice.CONTINUE_WITHOUT_AI, second.await())
    }

    @Test
    fun awaitingCoroutineCancellation_propagatesAndClearsState() = runTest {
        // The user navigates away mid-decision → viewModelScope cancels →
        // awaitDecision() throws CancellationException. After that, a
        // fresh awaitDecision() must work normally.
        val gate = AiFailureGate()

        val firstJob: Job = launch { gate.awaitDecision() }
        runCurrent()
        assertTrue("first awaiter should be active", firstJob.isActive)

        firstJob.cancel()
        runCurrent()
        assertTrue("first awaiter should be cancelled", firstJob.isCancelled)

        // Now a fresh round-trip on the same gate.
        val second = async { gate.awaitDecision() }
        runCurrent()
        gate.resolve(AiFailureChoice.CANCEL_EXPORT)
        assertEquals(AiFailureChoice.CANCEL_EXPORT, second.await())
    }

    // Helper: run any pending tasks on the test dispatcher so the
    // awaiting coroutine actually parks on the deferred before we
    // attempt to resolve it.
    private suspend fun TestScope.runCurrent() {
        testScheduler.runCurrent()
    }
}

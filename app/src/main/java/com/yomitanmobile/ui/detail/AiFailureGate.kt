package com.yomitanmobile.ui.detail

import kotlinx.coroutines.CompletableDeferred

/**
 * Coroutine-friendly handoff between the export-flow coroutine and the
 * user-facing AI-failure dialog.
 *
 * When the AI summary call fails mid-export, the coroutine calls
 * [awaitDecision] to suspend until the UI calls [resolve] with the user's
 * pick. The resolved value tells the coroutine which branch to take —
 * finish the card with an empty summary slot, or abort before AnkiDroid
 * is touched.
 *
 * Extracted from [DetailViewModel] so the parking pattern can be unit
 * tested without standing up the full ViewModel + Android-context graph.
 *
 * Invariants:
 *  - At most one decision is in flight at a time. The export flow is
 *    gated upstream by [DetailViewModel._isExporting], which blocks new
 *    exports while a prior one is parked.
 *  - [resolve] before any [awaitDecision] is a harmless no-op — it just
 *    discards the choice. This matches the UI semantics: the dialog only
 *    appears after the coroutine has parked.
 *  - If the awaiting coroutine is cancelled (user navigates away, scope
 *    cancellation), [awaitDecision] throws CancellationException and the
 *    pending deferred is left dangling but unreferenced, which lets the
 *    GC collect it normally.
 */
class AiFailureGate {

    @Volatile
    private var pending: CompletableDeferred<AiFailureChoice>? = null

    /**
     * Park the calling coroutine until [resolve] is called. Returns the
     * resolved choice. Cancellation of the calling scope propagates as
     * CancellationException through the underlying deferred.
     */
    suspend fun awaitDecision(): AiFailureChoice {
        val deferred = CompletableDeferred<AiFailureChoice>()
        pending = deferred
        return try {
            deferred.await()
        } finally {
            // Drop the reference after await returns (or throws on cancel)
            // so a subsequent stale resolve() doesn't accidentally complete
            // a future awaitDecision().
            if (pending === deferred) pending = null
        }
    }

    /**
     * Complete the pending decision (if any). No-op when no awaiter is
     * parked — the UI may call this in response to a dialog dismiss event
     * fired during teardown, and we don't want that to throw.
     */
    fun resolve(choice: AiFailureChoice) {
        pending?.complete(choice)
    }
}

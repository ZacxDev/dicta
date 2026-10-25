package dev.zacx.dicta

import android.app.StatusBarManager

/**
 * What the system told us about the add-tile prompt.
 *
 * `StatusBarManager.requestAddTileService` reports **nine** distinct values on
 * API 34, not two. They are read off the SDK rather than remembered — see
 * [TileAddOutcome.of] for the enumeration and
 * `android.app.StatusBarManager`'s `TILE_ADD_REQUEST_*` constants for the
 * source.
 */
enum class TileAddOutcome {
    /** The tile is now in the shade. */
    ADDED,

    /**
     * It was already there, so nothing changed. Distinct from [ADDED] because
     * telling a user their tile was *added* when they already had it sends
     * them looking for something new.
     */
    ALREADY_ADDED,

    /**
     * The user saw the prompt and said no. 🔴 **This is the arm the defect in
     * issue #14 reported**: the callback's result was discarded and every
     * outcome, including this one, claimed the tile was added.
     */
    DECLINED,

    /**
     * The prompt never reached the user — a bad component, the wrong package,
     * a request already in flight, the app in the background, no status-bar
     * service, or a non-current user. Six platform error codes collapse here
     * because the *user-visible* remedy is identical for all of them: the
     * dialog did not happen, so add the tile by hand.
     *
     * They are deliberately not one arm each. The distinction between
     * `BAD_COMPONENT` and `NO_STATUS_BAR_SERVICE` is a developer's problem,
     * and a toast cannot act on it.
     */
    FAILED,
}

/**
 * The result-code mapping, as a pure function so it is testable off-device.
 *
 * `requestAddTileService`'s `Consumer<Integer>` is only ever invoked by the
 * platform, so the branch that reads it cannot be exercised on the JVM
 * directly. Keeping the decision here — and leaving [MainActivity] with
 * nothing but a `getString` on the answer — is what makes every one of the
 * nine codes assertable.
 */
object TileAddPrompt {

    /**
     * Classifies one result code.
     *
     * The codes are enumerated rather than bucketed by magnitude (`>= 1000`
     * for the errors) on purpose: the three success-ish values 0, 1 and 2 are
     * not range-shaped, and an `else` that swallowed an unknown *success* code
     * as [TileAddOutcome.FAILED] would be the same class of lie as the one
     * this function exists to fix.
     *
     * An unrecognised code maps to [TileAddOutcome.FAILED] because a result we
     * cannot interpret is not evidence the tile was added — the safe default
     * is the one that does not claim success.
     */
    fun of(result: Int): TileAddOutcome = when (result) {
        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> TileAddOutcome.ADDED
        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED ->
            TileAddOutcome.ALREADY_ADDED
        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> TileAddOutcome.DECLINED
        else -> TileAddOutcome.FAILED
    }

    /** The toast for [outcome]. One string per arm, all four distinct. */
    fun messageFor(outcome: TileAddOutcome): Int = when (outcome) {
        TileAddOutcome.ADDED -> R.string.tile_added
        TileAddOutcome.ALREADY_ADDED -> R.string.tile_add_already
        TileAddOutcome.DECLINED -> R.string.tile_add_declined
        TileAddOutcome.FAILED -> R.string.tile_add_error
    }

    /** The two composed: result code straight to the string to show. */
    fun messageForResult(result: Int): Int = messageFor(of(result))
}

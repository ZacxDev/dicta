package dev.zacx.dicta

import android.app.StatusBarManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The nine result codes of `requestAddTileService`, one arm at a time.
 *
 * ## 🔴 This is new-code coverage, NOT a regression test
 *
 * Stated plainly because the distinction is the whole point of the rule:
 * [TileAddPrompt] does not exist on `f54ea19`, so this file cannot be compiled
 * against pre-change code, let alone watched to fail there. It is an
 * **invariant guard** — it pins the mapping against future drift and it is
 * what the mutation sweep has to kill. The test that was watched to go red on
 * the defect is [TileAddDeclinedTest], and only that one counts as regression
 * coverage.
 *
 * ## Where the codes come from
 *
 * Read off `android.jar` for API 34 with `javap -constants`, not from memory:
 *
 * ```
 * TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED            = 0
 * TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED        = 1
 * TILE_ADD_REQUEST_RESULT_TILE_ADDED                = 2
 * TILE_ADD_REQUEST_ERROR_MISMATCHED_PACKAGE         = 1000
 * TILE_ADD_REQUEST_ERROR_REQUEST_IN_PROGRESS        = 1001
 * TILE_ADD_REQUEST_ERROR_BAD_COMPONENT              = 1002
 * TILE_ADD_REQUEST_ERROR_NOT_CURRENT_USER           = 1003
 * TILE_ADD_REQUEST_ERROR_APP_NOT_IN_FOREGROUND      = 1004
 * TILE_ADD_REQUEST_ERROR_NO_STATUS_BAR_SERVICE      = 1005
 * ```
 *
 * The assertions below reference the **named constants**, so a platform that
 * renumbers them cannot leave this file asserting stale integers — but
 * [theConstantsStillHoldTheValuesTheMappingWasWrittenFor] pins the numbers too,
 * because the three interesting values are 0/1/2 and an `of()` written around
 * "0 is the falsy one" would be silently wrong if they ever moved.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TileAddPromptTest {

    private fun string(res: Int): String = RuntimeEnvironment.getApplication().getString(res)

    private val errorCodes = listOf(
        StatusBarManager.TILE_ADD_REQUEST_ERROR_MISMATCHED_PACKAGE,
        StatusBarManager.TILE_ADD_REQUEST_ERROR_REQUEST_IN_PROGRESS,
        StatusBarManager.TILE_ADD_REQUEST_ERROR_BAD_COMPONENT,
        StatusBarManager.TILE_ADD_REQUEST_ERROR_NOT_CURRENT_USER,
        StatusBarManager.TILE_ADD_REQUEST_ERROR_APP_NOT_IN_FOREGROUND,
        StatusBarManager.TILE_ADD_REQUEST_ERROR_NO_STATUS_BAR_SERVICE,
    )

    @Test
    fun theThreeNonErrorCodesEachMapToTheirOwnOutcome() {
        assertEquals(
            TileAddOutcome.ADDED,
            TileAddPrompt.of(StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED),
        )
        assertEquals(
            TileAddOutcome.ALREADY_ADDED,
            TileAddPrompt.of(StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED),
        )
        assertEquals(
            "a declined prompt must classify as DECLINED — the arm issue #14 " +
                "reported missing.",
            TileAddOutcome.DECLINED,
            TileAddPrompt.of(StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED),
        )
    }

    @Test
    fun allSixPlatformErrorCodesMapToFailed() {
        assertEquals("the SDK's error-code set changed size", 6, errorCodes.size)
        errorCodes.forEach { code ->
            assertEquals(
                "result code $code is a TILE_ADD_REQUEST_ERROR_* value: the " +
                    "prompt never reached the user, so it is neither a " +
                    "success nor a decline.",
                TileAddOutcome.FAILED,
                TileAddPrompt.of(code),
            )
        }
    }

    /**
     * An unknown code must not be read as a success. A future platform value
     * we cannot interpret is not evidence the tile was added, and defaulting
     * the other way would rebuild the exact lie this work removed.
     */
    @Test
    fun anUnrecognisedCodeIsNotTreatedAsAdded() {
        listOf(-1, 3, 42, 999, 1006, Int.MAX_VALUE, Int.MIN_VALUE).forEach { code ->
            assertEquals(
                "unrecognised result code $code must fall back to FAILED",
                TileAddOutcome.FAILED,
                TileAddPrompt.of(code),
            )
            assertNotEquals(
                "unrecognised result code $code must never claim the tile " +
                    "was added",
                string(R.string.tile_added),
                string(TileAddPrompt.messageForResult(code)),
            )
        }
    }

    /**
     * 🔴 The mapping is only meaningful if the four arms say four different
     * things. A `messageFor` collapsed onto one resource would satisfy every
     * "maps to the right outcome" assertion above while showing the user a
     * single message for all nine codes — which is the defect, one layer down.
     */
    @Test
    fun everyOutcomeHasItsOwnDistinctUserVisibleString() {
        val byOutcome = TileAddOutcome.entries.associateWith {
            string(TileAddPrompt.messageFor(it))
        }

        assertEquals(
            "TileAddOutcome gained or lost an arm; this test enumerates them",
            4,
            byOutcome.size,
        )
        assertEquals(
            "two outcomes share a user-visible string, so the user cannot " +
                "tell them apart: $byOutcome",
            4,
            byOutcome.values.toSet().size,
        )
        byOutcome.forEach { (outcome, text) ->
            assertTrue("$outcome maps to a blank string", text.isNotBlank())
        }
    }

    /** The composed helper must agree with its two halves at every code. */
    @Test
    fun messageForResultAgreesWithOfThenMessageFor() {
        val codes = errorCodes + listOf(
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED,
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED,
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED,
            7777,
        )
        codes.forEach { code ->
            assertEquals(
                "messageForResult($code) disagrees with messageFor(of($code))",
                TileAddPrompt.messageFor(TileAddPrompt.of(code)),
                TileAddPrompt.messageForResult(code),
            )
        }
    }

    /**
     * The numbers, pinned. See this class's kdoc: the three outcome codes are
     * 0/1/2 and nothing about them is range-shaped, so a renumbering would
     * quietly invalidate the mapping rather than breaking a compile.
     */
    @Test
    fun theConstantsStillHoldTheValuesTheMappingWasWrittenFor() {
        assertEquals(0, StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED)
        assertEquals(1, StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED)
        assertEquals(2, StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED)
        assertEquals(1000, StatusBarManager.TILE_ADD_REQUEST_ERROR_MISMATCHED_PACKAGE)
        assertEquals(1005, StatusBarManager.TILE_ADD_REQUEST_ERROR_NO_STATUS_BAR_SERVICE)
    }
}

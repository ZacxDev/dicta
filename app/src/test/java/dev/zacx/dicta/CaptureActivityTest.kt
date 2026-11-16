package dev.zacx.dicta

import android.app.Activity
import android.content.Intent
import android.text.SpannableString
import android.text.style.StyleSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/**
 * [CaptureActivity] — the invisible receiver behind the selection toolbar's
 * "Share to clipboard" item and the system share sheet.
 *
 * ## 🔴 What this file is evidence for, and what it is NOT
 *
 * It is evidence that an intent carrying text lands exactly one row in the
 * outbox, that one carrying nothing lands nothing, that a failed write is
 * reported rather than swallowed, and that the activity shows no UI and
 * finishes immediately.
 *
 * 🔴 It is **NOT** evidence that the item appears in the selection toolbar, nor
 * that tapping it delivers this intent. That is the platform's half, it differs
 * per OEM — the target handset is a Galaxy S21, and Samsung reimplements the
 * toolbar — and **no Android device was reachable while this was written**
 * (`adb devices` empty, wireless adb to the handset refused). The pull request
 * carries the exact on-device steps instead of this file implying coverage.
 *
 * 🔴 **There is deliberately NO receiver-side sensitivity check**, and so no
 * test for one. A sender-set `EXTRA_IS_SENSITIVE` is ignored: that check was
 * cut by decision, because the three paths that actually carry a password — a
 * password manager's own copy button, an `android:inputType="textPassword"`
 * field's selection, and an autofill provider's fill — do not come through
 * this intent at all, so the check would have refused nothing while reading as
 * protection. The design document's test list still names such a case; it
 * contradicts the document's own sensitivity section, and this file follows the
 * section. Reported rather than quietly resolved.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CaptureActivityTest {

    /** An in-memory outbox that can be told to fail. */
    private class FakeOutbox(private val failWith: String? = null) : ClipOutbox {
        val appended = mutableListOf<ClipOutbox.Pending>()
        private var next = 1L
        override fun append(body: String): Long {
            failWith?.let { throw ClipOutbox.Failed(it) }
            val id = next++
            appended += ClipOutbox.Pending(id, body)
            return id
        }
        override fun pending(): List<ClipOutbox.Pending> = appended.toList()
        override fun remove(id: Long) { appended.removeAll { it.id == id } }
    }

    private fun launch(
        intent: Intent,
        outbox: FakeOutbox = FakeOutbox(),
    ): Pair<CaptureActivity, FakeOutbox> {
        val c = Robolectric.buildActivity(CaptureActivity::class.java, intent)
        c.get().outbox = outbox
        return c.create().get() to outbox
    }

    private fun processText(text: CharSequence?) =
        Intent(Intent.ACTION_PROCESS_TEXT).apply {
            setClassName(RuntimeEnvironment.getApplication(), CaptureActivity::class.java.name)
            type = "text/plain"
            putExtra(Intent.EXTRA_PROCESS_TEXT, text)
        }

    private fun shared(text: CharSequence?) =
        Intent(Intent.ACTION_SEND).apply {
            setClassName(RuntimeEnvironment.getApplication(), CaptureActivity::class.java.name)
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }

    private fun string(id: Int) = RuntimeEnvironment.getApplication().getString(id)

    @Test
    fun selectedTextArrivesInTheOutboxVERBATIM() {
        val (_, outbox) = launch(processText("the thing I selected"))
        assertEquals(
            listOf(ClipOutbox.Pending(1L, "the thing I selected")),
            outbox.appended,
        )
    }

    @Test
    fun aQueuedRowCarriesTheBODYAndNOTHINGELSE() {
        // 🔴 This REPLACES `theCaptureTimeIsTHISDevicesClockEvenThoughTheServerOverwritesIt`,
        // which asserted a local capture timestamp that deletion candidate D5
        // removed. Its rationale was that the stamp "is what a later fix would
        // need and costs one field now" — which is the justification that can
        // never be disproved, and the field was in fact written to disk, parsed
        // back and consumed by nobody.
        //
        // The claim that survives is narrower and is the one that matters for
        // the only copy of the user's capture: what the receiver queues is the
        // selection, byte-exact, and the queue carries no second opinion about
        // when it happened. [Clip.createdAtMs] is server-assigned and is what
        // every surface renders.
        val body = "x\ty\nz \"quoted\""
        val (_, outbox) = launch(processText(body))
        val row = outbox.appended.single()
        assertEquals(body, row.body)
        assertEquals(
            "a queued row carries a field beyond its local id and its body",
            ClipOutbox.Pending(id = row.id, body = body),
            row,
        )
    }

    @Test
    fun sharedTextFromTheSystemSheetArrivesTheSameWay() {
        val (_, outbox) = launch(shared("shared from a browser"))
        assertEquals(listOf("shared from a browser"), outbox.appended.map { it.body })
    }

    @Test
    fun aSTYLEDSelectionIsStoredAsItsPLAINTextAndNotAsAnObjectToString() {
        // 🔴 EXTRA_PROCESS_TEXT is a CharSequence, and what a browser or a
        // document editor actually puts there is a Spanned. Reading it with
        // getStringExtra returns NULL for one — the capture would silently land
        // nothing — and a naive `toString` on the wrong type would store a
        // class name. This is the case that makes `getCharSequenceExtra` the
        // right reader, so the fixture is a real SpannableString with a span.
        val styled = SpannableString("bold selection").apply {
            setSpan(StyleSpan(android.graphics.Typeface.BOLD), 0, 4, 0)
        }
        val (_, outbox) = launch(processText(styled))
        assertEquals(listOf("bold selection"), outbox.appended.map { it.body })
    }

    @Test
    fun anIntentCarryingNothingLandsNOTHINGAndSaysSo() {
        for (intent in listOf(
            processText(null),
            processText(""),
            processText("   \n  "),
            shared(null),
            Intent(Intent.ACTION_SEND).apply { type = "text/plain" },
        )) {
            val (_, outbox) = launch(intent)
            assertEquals(
                "an empty capture was queued from $intent",
                emptyList<ClipOutbox.Pending>(),
                outbox.appended,
            )
        }
        // 🔴 And the user is TOLD. A silent no-op after tapping a menu item is
        // indistinguishable from a broken feature — the operator would retry it
        // and conclude the app is dead.
        assertEquals(string(R.string.clip_capture_nothing), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun aSuccessfulCaptureTellsTheUserItIsQUEUEDAndNotThatItIsSENT() {
        // 🔴 The wording is the honest claim: at this moment nothing has left
        // the phone. Saying "copied to the shared clipboard" would be a claim
        // about the mesh that this activity has made no attempt to verify —
        // and off the mesh it would be false for as long as the tunnel is down.
        val (_, _) = launch(processText("queued, not sent"))
        assertEquals(string(R.string.clip_capture_queued), ShadowToast.getTextOfLatestToast())
        assertTrue(
            "the success toast claims the clip was sent",
            !string(R.string.clip_capture_queued).contains("sent", ignoreCase = true),
        )
    }

    @Test
    fun aFailedWriteIsREPORTEDRatherThanLookingLikeASuccess() {
        // 🔴 The whole reason ClipOutbox.append throws instead of returning
        // quietly. A full disk, or a directory that cannot be created, must not
        // produce the "queued" toast over a capture that is simply gone.
        val (_, outbox) = launch(
            processText("doomed"),
            FakeOutbox(failWith = "the capture could not be saved"),
        )
        assertEquals(emptyList<ClipOutbox.Pending>(), outbox.appended)
        assertEquals(string(R.string.clip_capture_failed), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun theThreeToastsAreThreeDIFFERENTStrings() {
        // Collapse any two and the operator cannot tell "I selected nothing"
        // from "your disk is full" — two conditions with opposite responses.
        val messages = listOf(
            string(R.string.clip_capture_queued),
            string(R.string.clip_capture_nothing),
            string(R.string.clip_capture_failed),
        )
        assertEquals("two capture outcomes share a message", 3, messages.toSet().size)
    }

    @Test
    fun theActivityShowsNOUIAndFinishesImmediately() {
        // 🔴 It is launched OVER another app from that app's own selection
        // toolbar. Anything it draws — even for a frame — is a window the user
        // did not ask for in the middle of their text selection, and the
        // selection would be lost. No setContentView, no overlay theme, finish
        // inside onCreate.
        val c = Robolectric.buildActivity(CaptureActivity::class.java, processText("x"))
        c.get().outbox = FakeOutbox()
        val activity = c.create().get()
        assertTrue("CaptureActivity did not finish in onCreate", activity.isFinishing)
        assertEquals(
            "CaptureActivity set a content view",
            null,
            activity.findViewById<android.view.View>(android.R.id.content)
                ?.let { it as android.view.ViewGroup }
                ?.getChildAt(0),
        )
    }

    @Test
    fun theResultIsEXPLICITLYCanceledSoTheCallerDoesNotReplaceTheSelection() {
        // 🔴 This is the one genuinely platform-shaped decision in the file.
        // ACTION_PROCESS_TEXT's contract is that returning RESULT_OK with an
        // EXTRA_PROCESS_TEXT value REPLACES the user's selected text in the
        // host app. Dicta is capturing, not editing — so a result of OK, or an
        // accidental default, would silently rewrite the user's document. Set
        // explicitly rather than relied on as a default.
        val c = Robolectric.buildActivity(CaptureActivity::class.java, processText("x"))
        c.get().outbox = FakeOutbox()
        val activity = c.create().get()
        val shadow = shadowOf(activity)
        assertEquals(Activity.RESULT_CANCELED, shadow.resultCode)
        assertEquals(
            "a result intent was returned, which PROCESS_TEXT would apply to " +
                "the user's selection",
            null,
            shadow.resultIntent,
        )
    }

    @Test
    fun theCaptureActivityStartsNOTHINGAndAsksForNoPermission() {
        // 🔴 It is `exported="true"`, so any app on the device can start it.
        // That makes its blast radius its own: it must not be a route by which
        // another app can reach the microphone, the picker, or any other
        // activity in this app. A behavioural check on the strictest claim —
        // it starts nothing at all — alongside ClipSeamLedgerTest's structural
        // one.
        val c = Robolectric.buildActivity(CaptureActivity::class.java, processText("x"))
        c.get().outbox = FakeOutbox()
        val activity = c.create().get()
        assertEquals(
            "CaptureActivity started an activity",
            null,
            shadowOf(activity).peekNextStartedActivity(),
        )
    }

    @Test
    fun anIntentWithNoExtrasAtAllDoesNotCrashTheHostAppsToolbar() {
        // A malformed intent from another app, or from a `am start` by hand. A
        // crash here is a crash dialog over whatever app the user was in.
        val bare = Intent().apply {
            setClassName(RuntimeEnvironment.getApplication(), CaptureActivity::class.java.name)
        }
        val outcome = runCatching { launch(bare) }
        assertTrue("a bare intent crashed: ${outcome.exceptionOrNull()}", outcome.isSuccess)
    }
}

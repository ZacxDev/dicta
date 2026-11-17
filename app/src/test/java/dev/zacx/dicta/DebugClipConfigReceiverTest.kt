package dev.zacx.dicta

import android.content.Context
import android.content.Intent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

/**
 * The debug wrapper: that it parses extras into the seam, that its answer is
 * the readout, and that **nothing it says can carry the token**.
 *
 * ## 🔴 What this file can and cannot establish
 *
 * It drives [DebugClipConfigReceiver.answer] directly rather than sending a
 * broadcast. The decisions all live in [ClipConfigIngress] and
 * [ClipDiagnostics], which have their own tests; what is left here is the
 * parse, the action dispatch, and the end-to-end shape of the answer.
 *
 * ⚠ **The on-device wiring is NOT covered by this or by CI.** That the
 * `<intent-filter>` resolves, that `am broadcast` reaches the receiver, and
 * that `-f 0x00000020` is needed for a stopped package are all facts about a
 * handset. `.github/workflows/tests.yml` runs no emulator by explicit
 * decision, so those are verified by hand or not at all — stated here rather
 * than left for a green suite to imply.
 *
 * 🔴 It writes into the app's REAL preferences file, because that is the file
 * the receiver reaches through `ClipSettings.create`. Each test therefore
 * clears it first; a test that inherited the previous one's token would be a
 * test of leftovers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DebugClipConfigReceiverTest {

    /** Realistic in SHAPE only. Not a credential, and not the operator's. */
    private val fixtureToken =
        "b85f1d7a03c92e641fb70d85a2c6349e07bd5f18c4a93e206db7f1c580a4e392"

    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    private val receiver = DebugClipConfigReceiver()

    /** `TokenStoreTest`'s stand-in: reversible, and genuinely not the identity. */
    private class ReversingCipher : SecretCipher {
        override fun encrypt(plaintext: String): String =
            "enc:" + plaintext.reversed().map { (it.code + 7).toChar() }.joinToString("")

        override fun decrypt(blob: String): String? {
            if (!blob.startsWith("enc:")) return null
            return blob.removePrefix("enc:").map { (it.code - 7).toChar() }
                .joinToString("").reversed()
        }
    }

    @Before
    fun reset() {
        SttSettings.prefs(ctx).edit().clear().commit()
        ClipDiagnostics.clearForTest()
        ClipOutboxHealth.clearForTest()
        // 🔴 `noBackupFilesDir` is process-wide under Robolectric, so a queue
        // left by an earlier test would make the depth assertions below a
        // measurement of leftovers.
        ClipOutboxStore(ctx).let { q -> q.pending().forEach { q.remove(it.id) } }
        // 🔴 The REAL preferences file and the REAL capture queue, with only
        // the CIPHER swapped: AndroidKeyStore has no Robolectric
        // implementation, so the production KeystoreCipher throws here and
        // every configure would observe only the failure path. Everything else
        // the receiver touches is production.
        receiver.surfaces = { c ->
            val prefs = SttSettings.prefs(c)
            DebugClipConfigReceiver.Surfaces(
                clip = ClipSettings(
                    prefs,
                    TokenStore(prefs, ReversingCipher(), ClipSettings.KEY_TOKEN_CIPHERTEXT),
                ),
                stt = SttSettings(
                    prefs,
                    TokenStore(prefs, ReversingCipher(), TokenStore.KEY_TOKEN_CIPHERTEXT),
                ),
                outbox = ClipOutboxStore(c),
                // 🔴 The REAL owner, not a fake. The bubble's rules are
                // asserted against ClipConfigIngress and BubbleEnabled; what
                // is left for this file is that the wrapper is wired to the
                // real one at all — which is precisely the seam a pair of
                // hermetic tests cannot see.
                bubble = BubbleEnabled.control(c),
            )
        }
        ShadowSettings.setCanDrawOverlays(true)
        // 🔴 The bubble flag lives in the `dicta` preferences file, which the
        // clear above does NOT touch (that one is `dicta_stt`). Without this, a
        // test asserting the flag would be measuring the previous test's write.
        BubbleEnabled.turnOff(ctx)
    }

    @After
    fun tearDown() {
        SttSettings.prefs(ctx).edit().clear().commit()
        BubbleEnabled.turnOff(ctx)
        ClipDiagnostics.clearForTest()
        ClipOutboxHealth.clearForTest()
    }

    private fun send(action: String, vararg extras: Pair<String, String>): String =
        receiver.answer(
            ctx,
            Intent(action).apply { extras.forEach { putExtra(it.first, it.second) } },
        )

    // --- configure ------------------------------------------------------------
    //
    // 🔴 DELIBERATELY THIN (round 0's D2). Three tests here —
    // configureAppliesTheExtrasThroughTheSeam, configureNeverEchoesTheTokenBack
    // and aRefusedUrlComesBackAsTheSettingsScreensOwnReason — were deleted
    // because they re-asserted `ClipConfigIngressTest`'s own claims through a
    // three-line parse wrapper. `configure()` reads three extras and returns
    // `Outcome.message`; the apply rules, the no-plaintext guards, the pinned
    // messages and the names-fields-never-values rule are all asserted against
    // the ingress itself, where they live. What is tested HERE is only what the
    // wrapper decides: action dispatch, the real surfaces, and the absences.
    //
    // 🔴 `configure()` now reads FOUR extras, not three — `bubble_enabled`
    // joined them, and its rules are likewise asserted against the ingress. The
    // one claim that belongs here is that the wrapper is wired to the REAL
    // BubbleEnabled and to the readout that reports it, which is
    // configureSetsTheBubbleAndDiagnoseReadsTheSameValueBack below. That is a
    // SEAM, not a re-assertion: no other test builds the combined state.
    //
    // 🔴 The second such claim is
    // aRefusedPromotionAfterCONFIGUREClearsTheFlagItReportedAsApplied: what
    // `applied:` promises is a property of this DOOR plus the owner plus the
    // platform's asynchronous start, and it is non-monotonic. The ingress
    // cannot see it (its `Control` is a fake), BootRestoreTest reaches the same
    // refusal through `turnOn` rather than through a broadcast, and
    // BubbleEnabledWritersSeamTest has no door in sight.

    // --- diagnose -------------------------------------------------------------

    @Test
    fun diagnoseAnswersTheReadoutAndCarriesNoToken() {
        send(
            DebugClipConfigReceiver.ACTION_CONFIGURE,
            ClipConfigIngress.KEY_CLIP_TOKEN to fixtureToken,
        )
        val answer = send(DebugClipConfigReceiver.ACTION_DIAGNOSE)

        assertFalse("the readout carries the token: <$answer>", answer.contains(fixtureToken))
        for (n in listOf(4, 8, 16)) {
            assertFalse(answer, answer.contains(fixtureToken.take(n)))
        }
        // 🔴 POSITIVE CONTROL: fields that MUST be present are present, so the
        // clean result above is not a check reading an empty string. The
        // ciphertext line is the one that proves the token really was stored
        // when the absence was measured.
        assertTrue(answer, answer.contains("tokenCiphertextStored: true"))
        assertTrue(answer, answer.contains("clipBaseUrl: "))
        assertTrue(answer, answer.contains("queueDepth: "))
        assertTrue(answer, answer.contains("lastFetch: "))
    }

    @Test
    fun diagnoseReportsTheQueueDepthFromTheRealOutbox() {
        // 🔴 The one thing the receiver does that the seam cannot: it opens the
        // capture queue. A readout that always said `unreadable` would look
        // like a diagnostic and report nothing, and `unreadable` is what a
        // wrong directory produces.
        ClipOutboxStore(ctx).append("a capture waiting to be flushed")
        assertTrue(
            send(DebugClipConfigReceiver.ACTION_DIAGNOSE).contains("queueDepth: 1")
        )
    }

    @Test
    fun theReceiverNeverAPPENDSToOrREMOVESFromTheQueue() {
        // 🔴 The queue holds the ONLY copy of a capture. A diagnostic that
        // mutated it is a diagnostic nobody could afford to run, and this is
        // the behavioural half of the ledger entry in ClipSeamLedgerTest — a
        // structural scan type-checks straight past a wrong call.
        val outbox = ClipOutboxStore(ctx)
        outbox.append("first")
        outbox.append("second")
        val before = outbox.pending().map { it.body }

        send(DebugClipConfigReceiver.ACTION_DIAGNOSE)
        send(
            DebugClipConfigReceiver.ACTION_CONFIGURE,
            ClipConfigIngress.KEY_CLIP_TOKEN to fixtureToken,
        )

        assertEquals(
            "the diagnostics surface changed the capture queue",
            before,
            ClipOutboxStore(ctx).pending().map { it.body },
        )
    }

    /**
     * 🔴 **THE SEAM: CONFIGURE writes the bubble, the owner stores it, and
     * DIAGNOSE reads back the same answer — in ONE fixture.**
     *
     * Every other guard on this feature loads one surface:
     * `ClipConfigIngressTest` drives the rules against a fake owner,
     * `ClipDiagnosticsTest` drives the readout against a passed-in boolean, and
     * `BubbleEnabledWritersSeamTest` drives the owner with no door in sight. All
     * three can be green while the wrapper hands the readout the wrong source —
     * a `false` literal, `isUp` instead of `isOn`, or a fake left wired in —
     * because none of them ever builds the combined state. This does, through
     * the real `BubbleEnabled`, the real preferences file and the real
     * `ClipDiagnostics`.
     *
     * Both directions, because a mutant hard-coding either answer passes a
     * one-way assertion.
     */
    @Test
    fun configureSetsTheBubbleAndDiagnoseReadsTheSameValueBack() {
        assertTrue(
            "the fixture failed: the bubble must start OFF or the ON assertion " +
                "below cannot distinguish a write from a leftover",
            send(DebugClipConfigReceiver.ACTION_DIAGNOSE)
                .lines().contains("bubbleEnabled: false"),
        )

        assertEquals(
            "applied: ${ClipConfigIngress.KEY_BUBBLE_ENABLED}",
            send(
                DebugClipConfigReceiver.ACTION_CONFIGURE,
                ClipConfigIngress.KEY_BUBBLE_ENABLED to "true",
            ),
        )
        assertTrue(
            "CONFIGURE did not reach BubbleEnabled: the flag the app's own " +
                "switch reads is still off after the door reported success",
            BubbleEnabled.isOn(ctx),
        )
        assertTrue(
            "DIAGNOSE does not report the flag CONFIGURE just set, so an agent " +
                "cannot read back whether its own write landed",
            send(DebugClipConfigReceiver.ACTION_DIAGNOSE)
                .lines().contains("bubbleEnabled: true"),
        )

        assertEquals(
            "applied: ${ClipConfigIngress.KEY_BUBBLE_ENABLED}",
            send(
                DebugClipConfigReceiver.ACTION_CONFIGURE,
                ClipConfigIngress.KEY_BUBBLE_ENABLED to "false",
            ),
        )
        assertFalse("CONFIGURE could not turn the bubble back off", BubbleEnabled.isOn(ctx))
        assertTrue(
            "DIAGNOSE still reports the bubble as on after it was turned off",
            send(DebugClipConfigReceiver.ACTION_DIAGNOSE)
                .lines().contains("bubbleEnabled: false"),
        )
    }

    /**
     * 🔴 **`applied:` is a receipt for a WRITE, not a claim that it is still
     * true — driven through the door that says so.**
     *
     * `CONFIGURE bubble_enabled=true` answers `applied: bubble_enabled` and
     * means it: the flag is written. It then asks Android for the foreground
     * service, and `startForegroundService` returns before that service
     * exists — so the answer goes out while the start is in flight. If the
     * platform refuses to promote the service,
     * [BubbleEnabled.foregroundPromotionRefused] clears the flag again, because
     * a start the caller asked for that produced no bubble must not leave every
     * surface claiming one (issue #14). `DIAGNOSE` then truthfully reports
     * `false` for a write that was truthfully reported as applied.
     *
     * ## Why this is a test and not a fix
     *
     * There is no synchronous answer available about a service that does not
     * exist yet, so the door cannot report the outcome of the promotion; and
     * keeping the flag set would be the issue-#14 lie. The behaviour is right.
     * What was missing is that **nothing told an agent**, and nothing drove the
     * sequence end to end: `BootRestoreTest` reaches the same refusal through
     * `turnOn`, which is not the door, and no test went through an actual
     * `CONFIGURE`. The contract is now written on
     * [ClipConfigIngress.Outcome.Applied] and in this receiver's usage kdoc,
     * and this pins it.
     *
     * 🔴 Both halves asserted, so neither can pass for the wrong reason: the
     * `applied:` answer AND the flag immediately after it (a mutant that never
     * wrote the flag would pass a `DIAGNOSE`-only assertion, since the refusal
     * clears it anyway), then the cleared flag AND the readout that reports it
     * (a mutant whose readout always said `false` would pass the flag-only
     * assertion).
     *
     * ⚠ Cannot establish that Android ever refuses the promotion on a real
     * start — see [refuseTheForegroundPromotion].
     */
    @Test
    fun aRefusedPromotionAfterCONFIGUREClearsTheFlagItReportedAsApplied() {
        assertEquals(
            "the fixture failed: CONFIGURE must report the bubble as applied, " +
                "or the non-monotonicity this test is about never started",
            "applied: ${ClipConfigIngress.KEY_BUBBLE_ENABLED}",
            send(
                DebugClipConfigReceiver.ACTION_CONFIGURE,
                ClipConfigIngress.KEY_BUBBLE_ENABLED to "true",
            ),
        )
        assertTrue(
            "the fixture failed: `applied: bubble_enabled` was reported and the " +
                "flag was never written, which is a different defect from the " +
                "one below — `applied:` would not even be a receipt for a write",
            BubbleEnabled.isOn(ctx),
        )

        refuseTheForegroundPromotion()

        assertFalse(
            "the platform refused the foreground promotion of a start the " +
                "CONFIGURE door asked for and the flag stayed set. With the " +
                "overlay permission granted nothing masks it, so every surface " +
                "claims a bubble that does not exist and the app's switch needs " +
                "two taps to recover: issue #14 verbatim. A refused start the " +
                "caller asked for must clear the flag.",
            BubbleEnabled.isOn(ctx),
        )
        assertTrue(
            "DIAGNOSE still reports bubbleEnabled: true after the refusal " +
                "cleared the flag. The read-back is the ONLY authority an agent " +
                "has on the current state — `applied:` is a receipt for the " +
                "write and can be stale by the time it is read — so a readout " +
                "that does not track the clear leaves no way to find out.",
            send(DebugClipConfigReceiver.ACTION_DIAGNOSE)
                .lines().contains("bubbleEnabled: false"),
        )
    }

    // --- anything else --------------------------------------------------------

    @Test
    fun anUnknownActionIsREFUSEDAndNotSilence() {
        // 🔴 An agent that mistypes the action would otherwise watch nothing
        // happen, with no way to tell that from a broadcast dropped for a
        // stopped package — the empty result that the most causes share.
        assertEquals(
            "rejected: unknown action dev.zacx.dicta.debug.NOPE",
            send("dev.zacx.dicta.debug.NOPE"),
        )
    }

    @Test
    fun noLogCallExistsInTheReceiversSource() {
        // 🔴 Asserted on the SOURCE, because the property is an absence and an
        // absence cannot be observed by driving the class: a token in logcat is
        // readable by anything holding READ_LOGS and survives in a bug report.
        // Comments and string literals are stripped first — this file's own
        // kdoc discusses logging, and so does the receiver's.
        val source = requireNotNull(
            listOf(
                java.io.File("app/src/debug/java/dev/zacx/dicta/DebugClipConfigReceiver.kt"),
                java.io.File("src/debug/java/dev/zacx/dicta/DebugClipConfigReceiver.kt"),
                java.io.File("../app/src/debug/java/dev/zacx/dicta/DebugClipConfigReceiver.kt"),
            ).firstOrNull { it.isFile }
        ) { "could not read the receiver's source — refusing to report a pass" }
            .readText()
        val code = source
            .replace(Regex("\"\"\".*?\"\"\"", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("\"[^\"\\n]*\""), " ")
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("//[^\\n]*"), " ")

        assertFalse(
            "the receiver logs. Nothing in this surface may reach logcat.",
            Regex("""\bLog\s*\.\s*[a-z]""").containsMatchIn(code),
        )
        // Positive control on the stripping pipeline: the real code is still
        // there after it, so the absence above is not the absence of all code.
        assertTrue(
            "the comment/literal stripping ate the whole file, so the check " +
                "above proves nothing",
            code.contains("ClipConfigIngress"),
        )
        // And the pipeline would SEE a log call if one existed.
        assertTrue(
            Regex("""\bLog\s*\.\s*[a-z]""").containsMatchIn("val x = Log.d(TAG, token)")
        )
    }
}

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
            )
        }
    }

    @After
    fun tearDown() {
        SttSettings.prefs(ctx).edit().clear().commit()
        ClipDiagnostics.clearForTest()
        ClipOutboxHealth.clearForTest()
    }

    private fun send(action: String, vararg extras: Pair<String, String>): String =
        receiver.answer(
            ctx,
            Intent(action).apply { extras.forEach { putExtra(it.first, it.second) } },
        )

    // --- configure ------------------------------------------------------------

    @Test
    fun configureAppliesTheExtrasThroughTheSeam() {
        val answer = send(
            DebugClipConfigReceiver.ACTION_CONFIGURE,
            ClipConfigIngress.KEY_CLIP_BASE_URL to "http://10.42.0.10:8139",
            ClipConfigIngress.KEY_CLIP_TOKEN to fixtureToken,
        )
        assertEquals("applied: clip_base_url,clip_token", answer)

        val prefs = SttSettings.prefs(ctx)
        assertEquals("http://10.42.0.10:8139", prefs.getString(ClipSettings.KEY_BASE_URL, null))
        assertEquals(
            "the token did not round-trip through the cipher",
            fixtureToken,
            TokenStore(prefs, ReversingCipher(), ClipSettings.KEY_TOKEN_CIPHERTEXT).token(),
        )
        // 🔴 And the ciphertext on disk is NOT the plaintext. The ingress is a
        // new DOOR and must never be a new STORAGE path.
        assertFalse(
            "the stored blob IS the plaintext token",
            prefs.all.values.any { it?.toString()?.contains(fixtureToken) == true },
        )
    }

    @Test
    fun configureNeverEchoesTheTokenBack() {
        // 🔴 The answer goes to whatever sent the broadcast. On a debug build
        // that can be another installed app, so this is the boundary that
        // decides whether this surface can be used to READ the credential
        // rather than only to set one.
        val answer = send(
            DebugClipConfigReceiver.ACTION_CONFIGURE,
            ClipConfigIngress.KEY_CLIP_TOKEN to fixtureToken,
        )
        assertFalse("the answer echoes the token: <$answer>", answer.contains(fixtureToken))
        for (n in listOf(4, 8, 16)) {
            assertFalse(answer, answer.contains(fixtureToken.take(n)))
            assertFalse(answer, answer.contains(fixtureToken.takeLast(n)))
        }
        // Positive control: an answer WAS produced, so the absence above is not
        // the absence of any output.
        assertEquals("applied: clip_token", answer)
    }

    @Test
    fun aRefusedUrlComesBackAsTheSettingsScreensOwnReason() {
        assertEquals(
            "rejected: it must be a host and port only, with no path — " +
                "Dicta appends /v1/clips itself",
            send(
                DebugClipConfigReceiver.ACTION_CONFIGURE,
                ClipConfigIngress.KEY_CLIP_BASE_URL to "http://10.42.0.10:8139/v1/clips",
            ),
        )
    }

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
    fun configureWithNoExtrasChangesNothingAndSaysSo() {
        val before = SttSettings.prefs(ctx).all.toMap()
        assertEquals(
            "rejected: nothing to set",
            send(DebugClipConfigReceiver.ACTION_CONFIGURE),
        )
        assertEquals(before, SttSettings.prefs(ctx).all.toMap())
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

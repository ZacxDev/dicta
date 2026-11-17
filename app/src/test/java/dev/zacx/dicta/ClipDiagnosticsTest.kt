package dev.zacx.dicta

import android.content.Context
import android.content.SharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The machine-readable readout: what it says, and what it must never say.
 *
 * ## 🔴 Invariant guard AND regression coverage, and they are labelled
 *
 *  * **Regression**: [theReadoutDistinguishesTheFourFetchFailures] — the
 *    collapse of four failure classes into one `null` is the measured defect,
 *    and a readout built on `rows()` could not have reported it. (The failure
 *    at the base ref is a compile error there, not a watched red; see
 *    `ClipFetchClassificationTest`'s kdoc and `ClipPickerFailureMessageTest`
 *    for the runnable twin.)
 *  * **Invariant**: everything about the token's absence. No version of this
 *    readout ever carried the token — these guards exist because a readout is
 *    exactly the kind of surface that grows a "just the first four characters,
 *    for support" field, and the bug they prevent is unrecoverable rather than
 *    inconvenient.
 *
 * 🔴 **The absence guards were MUTATION-TESTED**, because an assertion that a
 * string is not somewhere passes just as happily when it is reading nothing.
 * `ClipDiagnostics.readout` was mutated to append `" token=" + token()` to the
 * reported base URL — a realistic leak shape, a credential riding along on a
 * field that is meant to be printed — and **3 tests failed**:
 * [theReadoutNeverContainsTheTokenValue], [theRenderedReadoutIsPinnedAsAWHOLEString]
 * and `DebugClipConfigReceiverTest.diagnoseAnswersTheReadoutAndCarriesNoToken`.
 * Full output in the commit message.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClipDiagnosticsTest {

    /**
     * Realistic in SHAPE only. Not a credential, and not the operator's.
     *
     * 🔴 Deliberately a string with no substring in common with any field NAME
     * or any constant the readout prints, so "the token is absent" cannot pass
     * because the needle happens to be unspellable.
     */
    private val fixtureToken =
        "7e51c0a9f4b8d2360ae71c45fb9d8306b2e4a71f59c0d83e6471ba205fc9e384"

    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun reset() {
        ClipDiagnostics.clearForTest()
        ClipOutboxHealth.clearForTest()
    }

    @After
    fun tearDown() {
        ClipDiagnostics.clearForTest()
        ClipOutboxHealth.clearForTest()
    }

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

    /** A cipher that cannot decrypt — the keystore-invalidated handset. */
    private class WriteOnlyCipher : SecretCipher {
        override fun encrypt(plaintext: String): String = "opaque-blob"
        override fun decrypt(blob: String): String? = null
    }

    private var prefsCounter = 0

    private fun prefs(): SharedPreferences =
        ctx.getSharedPreferences("diag_${prefsCounter++}", Context.MODE_PRIVATE)
            .also { it.edit().clear().commit() }

    private fun settings(
        cipher: SecretCipher = ReversingCipher(),
        p: SharedPreferences = prefs(),
    ) = ClipSettings(
        p,
        TokenStore(p, cipher, ClipSettings.KEY_TOKEN_CIPHERTEXT),
    )

    /** An in-memory queue, so a readout test is not a test of a file. */
    private class FakeOutbox(private val depth: Int, private val throws: Boolean = false) :
        ClipOutbox {
        override fun append(body: String): Long = 0L
        override fun pending(): List<ClipOutbox.Pending> {
            if (throws) throw ClipOutbox.Failed("the queue file is unreadable")
            return (1..depth).map { ClipOutbox.Pending(it.toLong(), "body $it") }
        }
        override fun remove(id: Long) = Unit
    }

    // --- the token is never in here -----------------------------------------

    @Test
    fun theReadoutNeverContainsTheTokenValue() {
        val settings = settings()
        settings.tokens.putToken(fixtureToken)
        settings.baseUrl = "http://10.42.0.10:8139"
        ClipDiagnostics.recordFetch(ClipFetch.Status(401))
        ClipDiagnostics.recordFlush(ClipFlusher.Report(kept = 3, unreachable = true))

        val readout = ClipDiagnostics.readout(settings, FakeOutbox(3))
        val rendered = readout.render()

        assertFalse(
            "🔴 the readout contains the token. This surface is read over adb " +
                "and pasted into issues; a token in it is unrecoverable.",
            rendered.contains(fixtureToken),
        )
        // 🔴 And no PREFIX or SUFFIX either, which is how such a field is
        // usually justified ("just enough to identify it"). Eight characters is
        // already enough to confirm a guess.
        for (n in listOf(4, 8, 16)) {
            assertFalse(
                "the readout contains the token's first $n characters",
                rendered.contains(fixtureToken.take(n)),
            )
            assertFalse(
                "the readout contains the token's last $n characters",
                rendered.contains(fixtureToken.takeLast(n)),
            )
        }
        // Nor through the data class's own toString, which is what a logging
        // call or a crash report would print.
        assertFalse(
            "the Readout's toString carries the token",
            readout.toString().contains(fixtureToken),
        )

        // 🔴 POSITIVE CONTROL — without this the clean result above is
        // indistinguishable from a check wired to an empty string. A field
        // that SHOULD be present is present, and it is the one that answers
        // the question the token value would have been used to answer.
        assertTrue(
            "the readout does not report that the token decrypts, so the " +
                "absence of the token above proves nothing — this check could " +
                "be reading nothing at all. Rendered:\n$rendered",
            rendered.contains("tokenDecrypts: true"),
        )
        assertTrue(
            "the readout does not carry the base URL either, so it is not " +
                "reporting on this configuration at all",
            rendered.contains("clipBaseUrl: http://10.42.0.10:8139"),
        )
    }

    @Test
    fun theControlWouldSeeATokenIfOneWereThere() {
        // 🔴 NEGATIVE CONTROL for the search itself: the same `contains` call,
        // over text that DOES hold the token, must find it. Without this, the
        // absence assertions above are a claim about the needle rather than
        // about the readout.
        val poisoned = "tokenDecrypts: true\nclipToken: $fixtureToken"
        assertTrue(
            "the search cannot find a token that is present, so finding none " +
                "in the real readout establishes nothing",
            poisoned.contains(fixtureToken),
        )
        assertTrue(poisoned.contains(fixtureToken.take(8)))
    }

    // --- hasToken is NOT hasCiphertext --------------------------------------

    @Test
    fun aStoredButUndecryptableTokenIsItsOwnReportedState() {
        // 🔴 The state that cost the most to diagnose: KeystoreCipher fails
        // closed, so removing the lock screen or restoring onto a new handset
        // leaves the ciphertext behind while token() returns null. The app then
        // reads as "never configured" on a phone configured weeks ago. One
        // boolean cannot say this; two can.
        val p = prefs()
        ClipSettings(p, TokenStore(p, ReversingCipher(), ClipSettings.KEY_TOKEN_CIPHERTEXT))
            .tokens.putToken(fixtureToken)
        val broken = settings(cipher = WriteOnlyCipher(), p = p)

        val rendered = ClipDiagnostics.readout(broken, null).render()
        assertTrue(rendered, rendered.contains("tokenCiphertextStored: true"))
        assertTrue(rendered, rendered.contains("tokenDecrypts: false"))
        assertTrue(rendered, rendered.contains("clipConfigured: false"))
    }

    @Test
    fun aFreshInstallIsDistinguishableFromABrokenKeystore() {
        val rendered = ClipDiagnostics.readout(settings(), null).render()
        assertTrue(rendered, rendered.contains("tokenCiphertextStored: false"))
        assertTrue(rendered, rendered.contains("tokenDecrypts: false"))
    }

    // --- the fetch reasons ---------------------------------------------------

    @Test
    fun theReadoutDistinguishesTheFourFetchFailures() {
        // 🔴 THE REGRESSION. At the base ref all four of these were one `null`
        // and the app said "usually being off-mesh" for every one.
        val settings = settings()
        val reasons = listOf(
            ClipFetch.Status(401),
            ClipFetch.Status(404),
            ClipFetch.Malformed,
            ClipFetch.Transport("ECONNREFUSED"),
        ).map {
            ClipDiagnostics.recordFetch(it)
            ClipDiagnostics.readout(settings, null).render()
                .lines().first { line -> line.startsWith("lastFetch:") }
        }
        assertEquals(
            listOf(
                "lastFetch: http:401",
                "lastFetch: http:404",
                "lastFetch: malformed",
                "lastFetch: io:ECONNREFUSED",
            ),
            reasons,
        )
    }

    @Test
    fun aFetchThatHasNotHappenedIsNONEAndNotAFailure() {
        // 🔴 Process-lifetime only, and it must not read as "nothing works".
        // An absence is the observable the most causes share.
        val rendered = ClipDiagnostics.readout(settings(), null).render()
        assertTrue(rendered, rendered.contains("lastFetch: none"))
        assertTrue(rendered, rendered.contains("lastFlush: none"))
        assertNull(ClipDiagnostics.lastFetch())
        assertNull(ClipDiagnostics.lastFlush())
    }

    // --- the flush, through the one channel every drain already uses ---------

    @Test
    fun theLastFlushArrivesFromClipOutboxHealthAndNotFromASecondCallSite() {
        // 🔴 The readout's flush line comes from ClipOutboxHealth.record,
        // which is where every drain site already reports — so the readout and
        // the bubble's verdict are computed from the SAME Report and cannot
        // disagree. Recording it at the call sites instead would be a second
        // opinion about what the last flush was.
        ClipOutboxHealth.record(
            ClipFlusher.Report(stored = 2, dropped = 1, kept = 4, unreachable = true)
        )
        val rendered = ClipDiagnostics.readout(settings(), null).render()
        assertTrue(
            rendered,
            rendered.contains(
                "lastFlush: stored=2 destroyed=1 kept=4 cannotDrain=true why=unreachable"
            ),
        )
        // The verdict the bubble renders is in the same readout, from the same
        // Report: a destroyed capture outranks everything else.
        assertTrue(rendered, rendered.contains("outboxHealth: DESTROYED"))
    }

    @Test
    fun aFailedQueueReadIsReportedAsAFailureAndNotAsAnEmptyQueue() {
        ClipOutboxHealth.record(ClipFlusher.Report(failure = "the queue file is unreadable"))
        val rendered = ClipDiagnostics.readout(settings(), null).render()
        assertTrue(
            rendered,
            rendered.contains("cannotDrain=true why=the queue file is unreadable"),
        )
    }

    @Test
    fun theWordIsDESTROYEDAndNotDropped() {
        // 🔴 A dropped row left the outbox without reaching the store, and the
        // outbox was the only copy. "dropped" reads like a tidy-up; the fact
        // is that text the user was told had been taken no longer exists.
        ClipDiagnostics.recordFlush(ClipFlusher.Report(dropped = 2))
        val rendered = ClipDiagnostics.readout(settings(), null).render()
        assertTrue(rendered, rendered.contains("destroyed=2"))
        assertFalse(rendered, rendered.contains("dropped="))
    }

    // --- the queue depth -----------------------------------------------------

    @Test
    fun anUnreadableQueueIsNotZero() {
        // 🔴 Zero would report the opposite of an unreadable queue: "nothing is
        // waiting" against "I cannot tell what is waiting". The empty result
        // is the observable the most causes share.
        val readout = ClipDiagnostics.readout(settings(), FakeOutbox(0, throws = true))
        assertNull(readout.queueDepth)
        assertTrue(readout.render().contains("queueDepth: unreadable"))

        // Positive control: the same code path reports a real depth, so the
        // `unreadable` above is about the throw and not about the plumbing.
        assertEquals(3, ClipDiagnostics.readout(settings(), FakeOutbox(3)).queueDepth)
        assertTrue(
            ClipDiagnostics.readout(settings(), FakeOutbox(3)).render()
                .contains("queueDepth: 3")
        )
    }

    @Test
    fun aMisconfiguredBaseUrlIsReportedWithTheSettingsScreensOwnReason() {
        // 🔴 Through ClipEndpoints.rejectionFor — the SAME predicate the
        // settings screen refuses with. A readout that applied a second rule
        // could call a URL fine that the screen refuses, or the reverse, and
        // the operator would have two answers about one field.
        val settings = settings()
        settings.baseUrl = "http://10.42.0.10:8139/v1/clips"
        val readout = ClipDiagnostics.readout(settings, null)
        assertEquals(
            ClipEndpoints.rejectionFor("http://10.42.0.10:8139/v1/clips"),
            readout.baseUrlRejection,
        )
        assertTrue(readout.render().contains("clipBaseUrlRejection: it must be a host and port"))

        settings.baseUrl = "http://10.42.0.10:8139"
        assertTrue(
            ClipDiagnostics.readout(settings, null).render()
                .contains("clipBaseUrlRejection: none")
        )
    }

    // --- the whole rendered string ------------------------------------------

    @Test
    fun theRenderedReadoutIsPinnedAsAWHOLEString() {
        // 🔴 The artifact under test is PROSE, so a guard on words is walkable
        // by rewording. This pins the entire normalised output: a field
        // renamed, reordered, added or removed fails here, which is the price
        // of a line format an agent can parse.
        val settings = settings()
        settings.tokens.putToken(fixtureToken)
        settings.baseUrl = "http://10.42.0.10:8139"
        ClipOutboxHealth.record(ClipFlusher.Report(stored = 1, kept = 0))
        ClipDiagnostics.recordFetch(ClipFetch.Rows(emptyList()))

        assertEquals(
            """
            clipConfigured: true
            tokenCiphertextStored: true
            tokenDecrypts: true
            clipBaseUrl: http://10.42.0.10:8139
            clipBaseUrlRejection: none
            queueDepth: 2
            outboxHealth: OK
            lastFlush: stored=1 destroyed=0 kept=0 cannotDrain=false why=none
            lastFetch: rows:0
            """.trimIndent(),
            ClipDiagnostics.readout(settings, FakeOutbox(2)).render(),
        )
    }
}

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
 *  * **Regression**: [theLastOutcomesSURVIVEAProcessRestart] — the first
 *    version of this object kept both outcomes for the process lifetime only,
 *    so the first `DIAGNOSE` after a reboot or a force-stop reported `none` in
 *    exactly the situation the readout exists for. Red on the previous commit
 *    of this branch (`ClipDiagnostics` had no `install` to compile against) and
 *    green here; the operator answered round 0's Q2 with "persist".
 *  * **Invariant**: everything about the token's absence, and
 *    [whatIsPERSISTEDIsAReasonAndATimeAndNOTHINGElse]. No version of this
 *    readout ever carried the token — these guards exist because a readout is
 *    exactly the kind of surface that grows a "just the first four characters,
 *    for support" field, and the bug they prevent is unrecoverable rather than
 *    inconvenient. The persisted-form guard is the same argument one step
 *    further: that text outlives the process and ends up in a bug report.
 *
 * 🔴 **The absence guards were MUTATION-TESTED**, because an assertion that a
 * string is not somewhere passes just as happily when it is reading nothing.
 * `ClipDiagnostics.readout` was mutated to append `" token=" + token()` to the
 * reported base URL — a realistic leak shape, a credential riding along on a
 * field that is meant to be printed — and **3 tests failed**:
 * [theReadoutNeverContainsTheTokenValue], [theRenderedReadoutIsPinnedAsAWHOLEString]
 * and `DebugClipConfigReceiverTest.diagnoseAnswersTheReadoutAndCarriesNoToken`.
 * Full output in the commit message.
 *
 * 🔴 **This is also the surviving token-absence guard of the three round 0
 * found**, and the choice was made on strength rather than on count:
 * `ClipPickerFailureMessageTest.noFailureSentenceCanCarryTheToken` was deleted
 * because the TYPES already make a token impossible there — those sentences
 * come from string resources taking an `Int` or nothing — so it pinned an
 * impossibility. This one covers the route a mutation proved REACHABLE: a
 * String-valued field, printed on purpose, with a credential appended to it.
 * `DebugClipConfigReceiverTest`'s survives too, for a different claim: what
 * leaves the app through the exported component.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClipDiagnosticsTest {

    /** A fixed wall clock, so a persisted timestamp is an asserted value. */
    private val AT_MS = 1_763_000_000_000L

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
        // 🔴 A FIXED clock, so a recorded timestamp is an asserted value rather
        // than something only checked for being non-zero. `clearForTest` puts
        // the real one back.
        ClipDiagnostics.clock = { AT_MS }
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
        // 🔴 `none` must not read as "nothing works" — an absence is the
        // observable the most causes share. With no store installed it means
        // "nothing has been attempted in this process"; with one, "nothing has
        // ever been recorded", which is the stronger and more useful claim and
        // is why persistence was added. See the persistence tests below.
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
    fun aFlushThatCouldNotDrainReportsItsOwnReasonAndNotJustAFailureFlag() {
        // 🔴 RENAMED (round 0's nit): this was called
        // `aFailedQueueReadIsReportedAsAFailureAndNotAsAnEmptyQueue`, which
        // describes `anUnreadableQueueIsNotZero` — a different test, which
        // exists. What this one asserts is that the flush line carries the
        // REASON the drain stopped, not merely that it did.
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

    // --- persistence across a process restart (round 0's Q2) ----------------

    /**
     * A [ClipDiagnostics.Store] over a plain map, with every write visible.
     *
     * 🔴 A map rather than real preferences for the rule tests, so what is
     * persisted can be read as the exact bytes the production store would have
     * put on disk. [thePersistedFormIsWhatRealPreferencesHold] is the pair to
     * this, over `PrefsStore`.
     */
    private class MapStore(val map: MutableMap<String, String> = mutableMapOf()) :
        ClipDiagnostics.Store {
        var writes = 0
        override fun read(key: String): String? = map[key]
        override fun write(key: String, value: String) {
            writes++
            map[key] = value
        }
    }

    @Test
    fun theLastOutcomesSURVIVEAProcessRestart() {
        // 🔴 THE REGRESSION Q2 was answered to fix. Before this, both outcomes
        // were held in memory only, so the FIRST DIAGNOSE after a reboot or a
        // force-stop reported `none` — in exactly the situation the readout
        // exists to explain. The investigation that motivated this whole change
        // involved repeated force-stops, each of which would have wiped the
        // evidence it was looking for.
        val store = MapStore()
        ClipDiagnostics.install(store)
        ClipOutboxHealth.record(ClipFlusher.Report(stored = 2, kept = 5, unreachable = true))
        ClipDiagnostics.recordFetch(ClipFetch.Status(401))

        // The process dies. Everything in memory goes with it; the store does
        // not. `clearForTest` is exactly that much of a restart — it drops both
        // fields, the typed outcome and the installed store.
        ClipDiagnostics.clearForTest()
        assertNull("a restart that kept state in memory is not a restart", ClipDiagnostics.lastFlush())

        ClipDiagnostics.install(store)
        val rendered = ClipDiagnostics.readout(settings(), null).render()
        assertTrue(
            "the last flush did not survive the restart:\n$rendered",
            rendered.contains(
                "lastFlush: stored=2 destroyed=0 kept=5 cannotDrain=true why=unreachable"
            ),
        )
        assertTrue("the last fetch did not survive:\n$rendered", rendered.contains("lastFetch: http:401"))
        assertTrue("the timestamps did not survive:\n$rendered", rendered.contains("lastFetchAtMs: $AT_MS"))
    }

    @Test
    fun aRestartWithNOStoredOutcomeStillSaysNONE() {
        // 🔴 NEGATIVE CONTROL for the test above: hydration from an EMPTY store
        // must produce `none`, or "it survived" would be indistinguishable from
        // a readout that reports the last outcome of whatever ran before it in
        // the same JVM. It also pins the fail-closed decode — a store holding
        // junk is `none`, never a half-read outcome.
        ClipDiagnostics.install(MapStore())
        var rendered = ClipDiagnostics.readout(settings(), null).render()
        assertTrue(rendered, rendered.contains("lastFlush: none"))
        assertTrue(rendered, rendered.contains("lastFlushAtMs: none"))

        ClipDiagnostics.clearForTest()
        ClipDiagnostics.clock = { AT_MS }
        ClipDiagnostics.install(
            MapStore(mutableMapOf(ClipDiagnostics.KEY_LAST_FETCH to "not-a-timestamp|rows:3"))
        )
        rendered = ClipDiagnostics.readout(settings(), null).render()
        assertTrue(
            "an unparseable stored outcome was reported as a real one:\n$rendered",
            rendered.contains("lastFetch: none"),
        )
    }

    @Test
    fun theTypedFetchOutcomeIsNotHydratedBecauseAReasonCannotBecomeOneAgain() {
        // 🔴 The distinction the picker's sentence depends on, asserted rather
        // than left as a comment. `lastFetch()` is the PERSISTED reason, which
        // survives; `lastFetchOutcome()` is the TYPE, which cannot be rebuilt
        // from a string and is not claimed to be.
        //
        // 🔴 CORRECTED (audit round 1, finding 3). This comment used to add
        // "the picker always fetches before it renders, so it never reads the
        // typed one cold — if that ever changes, this is the test that should
        // be failing". It was FALSE and this was not the test that failed: the
        // picker's read can THROW, recording nothing while the shell still
        // renders, so the typed outcome was read cold-ish — holding the
        // PREVIOUS open's value. `ClipDiagnostics.beginFetch` is the fix and
        // `ClipPickerFailureMessageTest.aThrowingReadDoesNotInheritThePreviousOpensSentence`
        // is the test that pins it.
        val store = MapStore()
        ClipDiagnostics.install(store)
        ClipDiagnostics.recordFetch(ClipFetch.Malformed)
        assertEquals(ClipFetch.Malformed, ClipDiagnostics.lastFetchOutcome())

        ClipDiagnostics.clearForTest()
        ClipDiagnostics.install(store)
        assertEquals("malformed", ClipDiagnostics.lastFetch()?.summary)
        assertNull(
            "a typed outcome was invented from a persisted reason string",
            ClipDiagnostics.lastFetchOutcome(),
        )
    }

    @Test
    fun whatIsPERSISTEDIsAReasonAndATimeAndNOTHINGElse() {
        // 🔴 THE GUARD ON THE PERSISTED FORM, which the operator required
        // explicitly: outcome, reason and timestamp only — never a clip body,
        // never the token. Asserted on the exact strings handed to the store,
        // because that is what ends up on disk and outlives the process.
        //
        // The inputs are REALISTIC rather than minimal: a flush that destroyed
        // a capture, over a queue holding a body, with a token configured and a
        // failure string that an exception could plausibly carry.
        val settings = settings()
        settings.tokens.putToken(fixtureToken)
        val store = MapStore()
        ClipDiagnostics.install(store)

        ClipOutboxHealth.record(
            ClipFlusher.Report(stored = 1, dropped = 1, kept = 2, failure = "ETIMEDOUT")
        )
        ClipDiagnostics.recordFetch(ClipFetch.Transport("Connection refused"))
        ClipDiagnostics.readout(settings, FakeOutbox(2))

        val persisted = store.map.values.joinToString("\n")
        for (n in listOf(4, 8, 16)) {
            assertFalse(
                "the persisted form carries ${n} characters of the token: <$persisted>",
                persisted.contains(fixtureToken.take(n)) ||
                    persisted.contains(fixtureToken.takeLast(n)),
            )
        }
        assertFalse(
            "the persisted form carries a clip BODY. The outbox holds the only " +
                "copy of a capture and this file outlives the process: a body " +
                "here is the user's text written to a second place nobody " +
                "asked for. <$persisted>",
            persisted.contains("body "),
        )
        // 🔴 Every persisted value is `<epoch millis>|<reason>` and nothing
        // else — pinned as WHOLE strings, so a third field cannot be added
        // without this failing. A guard on words would be walkable by renaming.
        assertEquals(
            mapOf(
                ClipDiagnostics.KEY_LAST_FLUSH to
                    "$AT_MS|stored=1 destroyed=1 kept=2 cannotDrain=true why=ETIMEDOUT",
                ClipDiagnostics.KEY_LAST_FETCH to "$AT_MS|io:Connection refused",
            ),
            store.map.toMap(),
        )

        // 🔴 POSITIVE CONTROL, two of them, because every assertion above is an
        // absence and an absence passes just as happily when nothing was
        // written at all. (a) writes HAPPENED; (b) the same search finds both
        // needles in text that does hold them, so the clean result above is
        // about the persisted form and not about an unspellable needle.
        assertEquals("nothing was persisted, so the absences prove nothing", 2, store.writes)
        val poisoned = "$AT_MS|io:Connection refused token=$fixtureToken body 1"
        assertTrue(poisoned.contains(fixtureToken.take(8)))
        assertTrue(poisoned.contains("body "))
    }

    @Test
    fun thePersistedFormIsWhatRealPreferencesHold() {
        // 🔴 The map store above is a stand-in, and a stand-in is a claim about
        // the stand-in. This drives the PRODUCTION store over Robolectric's
        // preferences and reads the value back, so the persisted shape is
        // measured on the implementation the handset runs.
        val p = prefs()
        ClipDiagnostics.install(ClipDiagnostics.PrefsStore(p))
        ClipDiagnostics.recordFetch(ClipFetch.Status(403))
        assertEquals(
            "$AT_MS|http:403",
            p.getString(ClipDiagnostics.KEY_LAST_FETCH, null),
        )

        // And it hydrates from a FRESH PrefsStore over the same file, which is
        // what a new process does.
        ClipDiagnostics.clearForTest()
        ClipDiagnostics.install(ClipDiagnostics.PrefsStore(p))
        assertEquals("http:403", ClipDiagnostics.lastFetch()?.summary)
    }

    @Test
    fun aStoreThatTHROWSCostsThePersistenceAndNotTheFlush() {
        // 🔴 A diagnostic is worth less than a capture. A full disk or a
        // preferences file the system has locked is a real state, and a record
        // that propagated it would take down the drain it was reporting on.
        ClipDiagnostics.install(object : ClipDiagnostics.Store {
            override fun read(key: String): String? = null
            override fun write(key: String, value: String): Unit =
                throw IllegalStateException("no space left on device")
        })
        ClipDiagnostics.recordFetch(ClipFetch.Malformed)
        // In memory regardless, so the readout is exactly as good as it was
        // before anything was persisted at all.
        assertEquals("malformed", ClipDiagnostics.lastFetch()?.summary)
    }

    @Test
    fun hydrationDOESNOTOverwriteAnOutcomeAlreadyRecordedInThisProcess() {
        // 🔴 Audit round 1's finding 6. `install` was an unsynchronized
        // check-then-assign reachable from three threads — BubbleService.onCreate
        // (main), the picker's worker, and the debug receiver's DIAGNOSE — so a
        // record landing between the check and the assign was replaced by the
        // older value sitting on disk, contrary to install's own kdoc. This is
        // the single-threaded half of that claim: the ORDER alone must be
        // enough, with no timing involved.
        val store = MapStore(
            mutableMapOf(
                ClipDiagnostics.KEY_LAST_FETCH to "${AT_MS - 90_000}|http:503",
                ClipDiagnostics.KEY_LAST_FLUSH to "${AT_MS - 90_000}|stale",
            )
        )
        ClipDiagnostics.recordFetch(ClipFetch.Status(401))
        ClipDiagnostics.recordFlush(ClipFlusher.Report(stored = 7))

        ClipDiagnostics.install(store)

        assertEquals(
            "hydration replaced an outcome recorded in THIS process with an " +
                "older one from disk. install's kdoc promises the opposite, " +
                "and the fresher outcome is the one a diagnosis needs.",
            "http:401",
            ClipDiagnostics.lastFetch()?.summary,
        )
        assertEquals(AT_MS, ClipDiagnostics.lastFetch()?.atMs)
        assertTrue(
            "the flush outcome was replaced by the stored one",
            ClipDiagnostics.lastFlush()?.summary?.contains("stored=7") == true,
        )

        // 🔴 POSITIVE CONTROL: the very same store DOES hydrate when nothing
        // has been recorded, so the two assertions above are about the
        // compare-and-set and not about a store that was never read.
        ClipDiagnostics.clearForTest()
        ClipDiagnostics.clock = { AT_MS }
        ClipDiagnostics.install(store)
        assertEquals("http:503", ClipDiagnostics.lastFetch()?.summary)
    }

    // --- the persisted summary is ONE BOUNDED LINE ---------------------------

    @Test
    fun aSummaryWithANewlineInItCannotBreakTheKeyValueContract() {
        // 🔴 Audit round 1's finding 5. `render()` promises an agent one
        // `key: value` per line and the whole-string pin below uses SINGLE-LINE
        // fixtures only — so neither could see this. Both summaries are built
        // from an exception `message`: ClipFlusher.Report.failure is one
        // directly, and ClipFetch.Transport's detail is one. A wrapped
        // IOException's message routinely carries a newline, and the line after
        // it parses as a key that does not exist.
        //
        // 🔴 The fixture is a realistic message, not a bare "\n": a nested
        // cause on its own line is what the platform actually produces.
        ClipOutboxHealth.record(
            ClipFlusher.Report(
                failure = "java.io.IOException: unexpected end of stream\n\tat " +
                    "okio.RealBufferedSource.read\r\nCaused by: ETIMEDOUT"
            )
        )
        ClipDiagnostics.recordFetch(
            ClipFetch.Transport("Connection reset\nby peer")
        )

        val rendered = ClipDiagnostics.readout(settings(), null).render()
        // The contract, asserted as the contract: EVERY line is `key: value`
        // with a key this readout declares. A broken summary shows up as a line
        // whose key is a fragment of an exception message.
        val keys = rendered.lines().map { it.substringBefore(": ") }
        assertEquals(
            "a summary broke the one-fact-per-line contract. Rendered:\n$rendered",
            listOf(
                "clipConfigured",
                "tokenCiphertextStored",
                "tokenDecrypts",
                "clipBaseUrl",
                "clipBaseUrlRejection",
                "queueDepth",
                "outboxHealth",
                "lastFlush",
                "lastFlushAtMs",
                "lastFetch",
                "lastFetchAtMs",
            ),
            keys,
        )
        assertEquals(
            "the readout grew or lost a line, so the key list above is not " +
                "the whole of it:\n$rendered",
            11,
            rendered.lines().size,
        )
        // The reason is still THERE — flattened, not discarded. A sanitiser
        // that dropped the detail would have passed everything above while
        // removing the only thing the line is for.
        assertTrue(rendered, rendered.contains("ETIMEDOUT"))
        assertTrue(rendered, rendered.contains("lastFetch: io:Connection reset by peer"))

        // 🔴 NEGATIVE CONTROL for the instrument: the same line-splitting
        // check, fed an UNSANITISED summary, must fail. Without this, the clean
        // result above is indistinguishable from a check that cannot see a
        // newline at all.
        val poisoned = "lastFetch: io:Connection reset\nby peer"
        assertEquals(2, poisoned.lines().size)
        assertEquals("by peer", poisoned.lines()[1].substringBefore(": "))
    }

    @Test
    fun anOverLongSummaryIsBoundedAndSaysThatItWasCut() {
        // 🔴 The other half of finding 5, and it is not cosmetic: the summary is
        // PERSISTED, so an unbounded one is an unbounded write to the app's
        // preferences file driven by whatever string a failing server returned.
        val store = MapStore()
        ClipDiagnostics.install(store)
        ClipDiagnostics.recordFetch(ClipFetch.Transport("x".repeat(5_000)))

        val summary = ClipDiagnostics.lastFetch()?.summary ?: ""
        assertEquals(
            "the summary was not bounded: ${summary.length} characters",
            ClipDiagnostics.MAX_SUMMARY_CHARS,
            summary.length,
        )
        assertTrue(
            "a truncated summary does not say it was cut, so a reader cannot " +
                "tell a bounded reason from a complete one: <$summary>",
            summary.endsWith(ClipDiagnostics.TRUNCATED),
        )
        // What is persisted is bounded too — that is the write this bounds.
        val persisted = store.map[ClipDiagnostics.KEY_LAST_FETCH] ?: ""
        assertTrue(
            "the persisted form is ${persisted.length} characters",
            persisted.length <= ClipDiagnostics.MAX_SUMMARY_CHARS + 32,
        )

        // 🔴 POSITIVE CONTROL, two directions. (a) An ORDINARY summary is
        // untouched, so the bound cannot be firing on everything — a sanitiser
        // that truncated normal output would satisfy the assertions above while
        // discarding every reason this surface exists to report. (b) The input
        // really was longer than the bound.
        ClipDiagnostics.recordFetch(ClipFetch.Transport("ECONNREFUSED"))
        assertEquals("io:ECONNREFUSED", ClipDiagnostics.lastFetch()?.summary)
        assertTrue(5_000 > ClipDiagnostics.MAX_SUMMARY_CHARS)
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
            lastFlushAtMs: 1763000000000
            lastFetch: rows:0
            lastFetchAtMs: 1763000000000
            """.trimIndent(),
            ClipDiagnostics.readout(settings, FakeOutbox(2)).render(),
        )
    }
}

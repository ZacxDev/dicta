package dev.zacx.dicta

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The programmatic configuration door: what it accepts, what it refuses, and
 * what it must never leave behind.
 *
 * ## 🔴 These are INVARIANT guards, with one exception, and the labels matter
 *
 * [ClipConfigIngress] is a new file, so nothing here is regression coverage —
 * no version of it ever wrote a plaintext token or accepted a URL the screen
 * refuses. They are here because the two failures they prevent are
 * unrecoverable rather than inconvenient: a credential written in the clear
 * stays written, and a door that accepts a base URL the screen refuses
 * reopens the capture-loss defect of PR #49 for anything driving the door.
 *
 * The exception is [theValidatorIsTheSettingsScreensOwnAndNotACopy], which is
 * a **seam** guard: it pins a RELATIONSHIP (this door and that screen refuse
 * the same strings) rather than either side's behaviour, and a relationship is
 * the thing no component test can see.
 *
 * ## 🔴 The plaintext guards were mutation-tested, because they are absences
 *
 * An assertion that a string is NOT somewhere passes just as happily when it
 * is reading the wrong file, the wrong key, or nothing at all. So
 * `TokenStore.putToken` was mutated to store the trimmed token **in the clear**
 * instead of `cipher.encrypt(trimmed)`, and this class was run: **6 of its 11
 * tests failed**, including both halves of the plaintext claim —
 * [noPlaintextTokenReachesThePreferencesUnderANYKey] (the in-memory map) and
 * [noPlaintextTokenReachesThePreferencesFileONDISK] (the XML). Full output in
 * the commit message.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClipConfigIngressTest {

    /** Realistic in SHAPE only. Not a credential, and not the operator's. */
    private val fixtureToken =
        "d3a0f81b6c54972ef0b3a1d78c62094e5fb7310da82c6594e07fb13c8a4d2650"

    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    private class ReversingCipher : SecretCipher {
        override fun encrypt(plaintext: String): String =
            "enc:" + plaintext.reversed().map { (it.code + 7).toChar() }.joinToString("")

        override fun decrypt(blob: String): String? {
            if (!blob.startsWith("enc:")) return null
            return blob.removePrefix("enc:").map { (it.code - 7).toChar() }
                .joinToString("").reversed()
        }
    }

    /** Fails closed, like the real [KeystoreCipher] on an invalidated key. */
    private class ThrowingCipher : SecretCipher {
        override fun encrypt(plaintext: String): String =
            throw IllegalStateException("the keystore key is gone")

        override fun decrypt(blob: String): String? = null
    }

    private var counter = 0

    private fun prefs(name: String): SharedPreferences =
        ctx.getSharedPreferences(name, Context.MODE_PRIVATE)
            .also { it.edit().clear().commit() }

    /**
     * [BubbleEnabled.Control] without Android under it.
     *
     * 🔴 The point of the interface: the real owner calls
     * `Settings.canDrawOverlays` and `startForegroundService`, so a test
     * driving it would be asserting Robolectric's shadows. This records what
     * the door **asked for**, which is the only thing the door decides.
     *
     * @param overlayGranted `false` makes `turn(true)` refuse exactly as the
     *   owner does with the permission missing — writing nothing.
     */
    private class FakeBubble(
        private var on: Boolean = false,
        private val overlayGranted: Boolean = true,
    ) : BubbleEnabled.Control {
        /** Every [turn] argument, in order. Empty means the door never asked. */
        val asked = mutableListOf<Boolean>()

        override fun isOn(): Boolean = on

        override fun turn(on: Boolean): Boolean {
            asked += on
            if (on && !overlayGranted) return false
            this.on = on
            return true
        }
    }

    private class Fixture(
        val prefs: SharedPreferences,
        val clip: ClipSettings,
        val stt: SttSettings,
        val bubble: FakeBubble,
    )

    private fun fixture(
        name: String = "ingress_${counter++}",
        cipher: SecretCipher = ReversingCipher(),
        bubble: FakeBubble = FakeBubble(),
    ): Fixture {
        val p = prefs(name)
        return Fixture(
            p,
            ClipSettings(p, TokenStore(p, cipher, ClipSettings.KEY_TOKEN_CIPHERTEXT)),
            SttSettings(p, TokenStore(p, cipher, TokenStore.KEY_TOKEN_CIPHERTEXT)),
            bubble,
        )
    }

    private fun apply(f: Fixture, vararg args: Pair<String, String>) =
        ClipConfigIngress.apply(
            ClipConfigIngress.parse { key -> args.toMap()[key] },
            f.clip,
            f.stt,
            f.bubble,
        )

    // --- validation, through the shared predicate ----------------------------

    @Test
    fun theValidatorIsTheSettingsScreensOwnAndNotACopy() {
        // 🔴 THE SEAM. One rule, one place: a predicate open-coded at two
        // sites is wrong at one of them, and the consequence here is specific —
        // the capture-loss defect of PR #49 was reached through this very
        // field, so a door that accepted what the screen refuses would reopen
        // it for anything driving the door.
        //
        // Asserted as a RELATIONSHIP over a table rather than by naming one bad
        // URL: the claim is "these two agree", and a guard that checked one
        // string would pass against a second, subtly different validator.
        val cases = listOf(
            "ftp://10.42.0.10:8139",
            "10.42.0.10:8139",
            "http://",
            "http:///v1",
            "http://10.42.0.10:8139/v1/clips",
            "http://10.42.0.10:8139?limit=5",
            "http://10.42.0.10:8139#frag",
            "http://10.42.0.10 :8139",
            "http://" + "a".repeat(ClipEndpoints.MAX_BASE_URL_CHARS),
            // ... and the ones that must be ACCEPTED, so the table is not a
            // list of things that happen to be refused by anything at all.
            "http://10.42.0.10:8139",
            "https://clips.example:8443",
            "http://10.42.0.10:8139/",
            "",
        )
        for (url in cases) {
            val expected = ClipEndpoints.rejectionFor(url)
            val outcome = apply(fixture(), ClipConfigIngress.KEY_CLIP_BASE_URL to url)
            if (expected == null) {
                assertTrue(
                    "<$url> is accepted by the settings screen's predicate and " +
                        "refused by this door: $outcome",
                    outcome is ClipConfigIngress.Outcome.Applied,
                )
            } else {
                assertEquals(
                    "<$url> is refused by the settings screen's predicate with " +
                        "\"$expected\" and this door does not say the same thing",
                    ClipConfigIngress.Outcome.Rejected(expected),
                    outcome,
                )
            }
        }
    }

    @Test
    fun aRefusedUrlWritesNOTHINGAtAll() {
        // 🔴 SettingsActivity.save()'s rule: a refusal must leave the
        // configuration exactly as it was, so the message can truthfully say
        // nothing was saved. Here that matters more than on the screen — the
        // caller is a script that will read the message and move on.
        val f = fixture()
        f.clip.baseUrl = "http://10.42.0.10:8139"
        val before = f.prefs.all.toMap()

        val outcome = apply(
            f,
            ClipConfigIngress.KEY_CLIP_BASE_URL to "http://10.42.0.10:8139/v1/clips",
            ClipConfigIngress.KEY_CLIP_TOKEN to fixtureToken,
        )

        assertTrue(outcome is ClipConfigIngress.Outcome.Rejected)
        assertEquals(
            "a rejected request wrote something anyway, so the message that " +
                "says nothing was saved is a lie",
            before,
            f.prefs.all.toMap(),
        )
        assertFalse("a token was stored despite the refusal", f.clip.tokens.hasCiphertext)
    }

    // --- the token's storage path --------------------------------------------

    @Test
    fun aTokenSetThroughTheDoorIsReadableBackThroughTheCipherAndNowhereElse() {
        val f = fixture()
        assertEquals(
            ClipConfigIngress.Outcome.Applied(listOf(ClipConfigIngress.KEY_CLIP_TOKEN)),
            apply(f, ClipConfigIngress.KEY_CLIP_TOKEN to fixtureToken),
        )
        assertEquals(fixtureToken, f.clip.tokens.token())
        // The stored blob is not the input.
        assertNotEquals(
            fixtureToken,
            f.prefs.getString(ClipSettings.KEY_TOKEN_CIPHERTEXT, null),
        )
    }

    @Test
    fun noPlaintextTokenReachesThePreferencesUnderANYKey() {
        // 🔴 Mirrors TokenStoreTest's invariant through the NEW entry point.
        // SttSettings pins "the plaintext token is never written to the
        // preferences file" — an ingress is a new DOOR, never a new STORAGE
        // path, and the only way to know this door did not open one is to
        // re-measure it here. Every key AND every value, so a future
        // "convenience" mirror field turns this red.
        val f = fixture(name = "ingress_plaintext")
        apply(
            f,
            ClipConfigIngress.KEY_CLIP_TOKEN to fixtureToken,
            ClipConfigIngress.KEY_STT_TOKEN to fixtureToken,
        )
        for ((key, value) in f.prefs.all) {
            assertNotEquals("the plaintext token is stored at \"$key\"", fixtureToken, value)
            assertFalse(
                "the plaintext token appears inside the value at \"$key\"",
                value?.toString()?.contains(fixtureToken) == true,
            )
        }
    }

    @Test
    fun noPlaintextTokenReachesThePreferencesFileONDISK() {
        // 🔴 The second, different claim: the in-memory map is what the API
        // exposes, the XML is what anyone with filesystem access reads. Only
        // this one is about data at rest. TokenStoreTest makes the same split.
        val name = "ingress_ondisk"
        val f = fixture(name = name)
        apply(f, ClipConfigIngress.KEY_CLIP_TOKEN to fixtureToken)
        // commit(), so the file exists before it is read — an apply() here
        // would make this test pass by measuring nothing.
        f.prefs.edit().commit()

        val file = findPrefsFile(name)
        assertTrue(
            "could not locate the preferences file, so this test measured " +
                "nothing — refusing to report a pass",
            file != null && file.exists() && file.length() > 0,
        )
        val contents = file!!.readText()
        assertFalse(
            "the plaintext token is present in ${file.absolutePath}",
            contents.contains(fixtureToken),
        )
        // Positive control: the file really does hold OUR record, so the
        // absence above is about the token and not about an empty file.
        assertTrue(
            "the preferences file does not contain the clip ciphertext key, " +
                "so the absence of the plaintext proves nothing",
            contents.contains(ClipSettings.KEY_TOKEN_CIPHERTEXT),
        )
    }

    @Test
    fun aCipherThatThrowsIsREPORTEDAndTheTokenIsNotStored() {
        // 🔴 TokenStore.putToken's rule: a caller told "saved" when nothing was
        // encrypted would send no token and see an unexplained 401 for ever.
        // The CLASS NAME only — a cipher's message could in principle quote
        // its input.
        val f = fixture(cipher = ThrowingCipher())
        val outcome = apply(
            f,
            ClipConfigIngress.KEY_CLIP_BASE_URL to "http://10.42.0.10:8139",
            ClipConfigIngress.KEY_CLIP_TOKEN to fixtureToken,
        )
        assertEquals(
            ClipConfigIngress.Outcome.EncryptionFailed(
                "IllegalStateException",
                listOf(ClipConfigIngress.KEY_CLIP_BASE_URL),
            ),
            outcome,
        )
        assertFalse(f.clip.tokens.hasCiphertext)
        // 🔴 And it says what HAD been applied: the URL is written before the
        // token, so "nothing changed" would be false.
        assertEquals(
            "failed: token not encrypted (IllegalStateException); " +
                "applied: clip_base_url",
            outcome.message,
        )
        assertFalse(
            "the failure message carries the token",
            outcome.message.contains(fixtureToken),
        )
    }

    // --- what the outcomes say -----------------------------------------------

    @Test
    fun theOutcomeMessagesArePinnedAsWholeStrings() {
        // An agent branches on these, so they are a contract. Pinned whole,
        // because a guard on a word is walkable by rewording it.
        val f = fixture()
        assertEquals(
            "applied: clip_base_url,clip_token,stt_token",
            apply(
                f,
                ClipConfigIngress.KEY_CLIP_BASE_URL to "http://10.42.0.10:8139",
                ClipConfigIngress.KEY_CLIP_TOKEN to fixtureToken,
                ClipConfigIngress.KEY_STT_TOKEN to fixtureToken,
            ).message,
        )
        assertEquals("rejected: nothing to set", apply(fixture()).message)
        assertEquals(
            "rejected: it must start with http:// or https://",
            apply(
                fixture(),
                ClipConfigIngress.KEY_CLIP_BASE_URL to "ftp://10.42.0.10:8139",
            ).message,
        )
    }

    @Test
    fun anAppliedMessageNamesTheFIELDSAndNeverTheValues() {
        // 🔴 `clipToken` in the message says a token was stored and says
        // nothing about what it was. This is the one place the outcome and the
        // credential are in the same method, so it is worth measuring.
        val message = apply(
            fixture(),
            ClipConfigIngress.KEY_CLIP_TOKEN to fixtureToken,
        ).message
        assertEquals("applied: clip_token", message)
        assertFalse(message.contains(fixtureToken))
        assertFalse(message.contains(fixtureToken.take(6)))
    }

    // --- null is not blank ---------------------------------------------------

    @Test
    fun anAbsentFieldIsLeftALONEAndABlankOneCLEARSIt() {
        // 🔴 The token cannot be read back, so "set the URL and keep the
        // token" has to be expressible — that is what an absent field means.
        // And a door with no way back to the default is how an operator ends
        // up re-installing the app, so a blank field clears.
        val f = fixture()
        apply(f, ClipConfigIngress.KEY_CLIP_TOKEN to fixtureToken)
        apply(f, ClipConfigIngress.KEY_CLIP_BASE_URL to "https://clips.example:8443")
        assertEquals(
            "setting the URL cleared the token, which no caller asked for",
            fixtureToken,
            f.clip.tokens.token(),
        )
        assertEquals("https://clips.example:8443", f.clip.baseUrl)

        apply(f, ClipConfigIngress.KEY_CLIP_TOKEN to "")
        assertNull("a blank token did not clear the stored one", f.clip.tokens.token())
        assertFalse(f.clip.tokens.hasCiphertext)

        apply(f, ClipConfigIngress.KEY_CLIP_BASE_URL to "")
        assertEquals(
            "a blank URL did not fall back to the default",
            ClipEndpoints.DEFAULT_BASE_URL,
            f.clip.baseUrl,
        )
    }

    @Test
    fun theTwoTokensAreIndependentlySettable() {
        // 🔴 ClipSettings' whole reason for existing: the two tokens reach
        // services with different blast radii, so rotating one must not rotate
        // the other. A door that wrote both from one field would undo that.
        val f = fixture()
        val other = "a7c41f0e92b85d36714fc0a28de53b906f1a4c87d205e63b9a0fc418725d3e6b"
        apply(
            f,
            ClipConfigIngress.KEY_CLIP_TOKEN to fixtureToken,
            ClipConfigIngress.KEY_STT_TOKEN to other,
        )
        assertEquals(fixtureToken, f.clip.tokens.token())
        assertEquals(other, f.stt.tokens.token())

        apply(f, ClipConfigIngress.KEY_CLIP_TOKEN to "")
        assertNull(f.clip.tokens.token())
        assertEquals("clearing the clip token cleared the STT one", other, f.stt.tokens.token())
    }

    @Test
    fun theDoorReadsEXACTLYThreeFieldsAndTheSttBaseUrlIsNotOneOfThem() {
        // 🔴 STRUCTURAL, and the previous version of this test was not —
        // round 0 named it: `theSttBaseUrlIsDeliberatelyNotAFieldOfThisDoor`
        // asserted only that an unrecognised extra was ignored, which passes
        // identically for an extra named "banana". It pinned a generic property
        // of `parse` and said nothing about the decision in its name.
        //
        // This asks `parse` what it reads, by recording every key it requests.
        // A LEDGER, so it fails in both directions: GROWTH — a fourth field,
        // `stt_base_url` among them, arriving without someone reading why the
        // decision was taken; SHRINK — a field quietly dropped, which would
        // make the door stop configuring something a caller depends on.
        val requested = mutableListOf<String>()
        ClipConfigIngress.parse { key ->
            requested += key
            null
        }
        assertEquals(
            "🔴 the set of fields this door reads changed. If you are adding " +
                "stt_base_url, read ClipConfigIngress.parse's kdoc first: it " +
                "is absent because nobody asked for it, and the cheap way in " +
                "is to hoist ClipEndpoints.rejectionFor with the appended " +
                "path as a parameter rather than to write an unvalidated URL.",
            listOf(
                ClipConfigIngress.KEY_CLIP_BASE_URL,
                ClipConfigIngress.KEY_CLIP_TOKEN,
                ClipConfigIngress.KEY_STT_TOKEN,
                ClipConfigIngress.KEY_BUBBLE_ENABLED,
            ),
            requested,
        )

        // 🔴 POSITIVE CONTROL for the recorder itself: a lambda that was never
        // invoked would leave the list empty, and an empty list compared
        // against an empty expectation is the vacuous pass this shape invites.
        // Four keys, named above, and the ledger is not empty.
        assertEquals("parse asked for no keys at all", 4, requested.size)

        // And the behavioural half, which the structural check cannot make: an
        // stt_base_url extra really is inert, not merely unlisted.
        val f = fixture()
        val before = f.stt.baseUrl
        assertEquals(
            ClipConfigIngress.Outcome.NothingRequested,
            apply(f, SttSettings.KEY_BASE_URL to "http://10.42.0.10:9999"),
        )
        assertEquals(before, f.stt.baseUrl)
    }

    // --- bubble_enabled ------------------------------------------------------

    /**
     * The whole of the happy path, as a WHOLE result string.
     *
     * 🔴 The `applied:` line is what an agent branches on, so it is pinned
     * entirely rather than matched on the word `bubble_enabled` — a guard on a
     * word is walkable by rewording, and this one also has to pin the ORDER,
     * which is argued in [ClipConfigIngress.apply] (the bubble is the other
     * write that can be declined, so it goes first while `Rejected`'s
     * "nothing was written" promise is still true).
     */
    @Test
    fun theBubbleExtraTurnsTheBubbleOnAndSaysSoInTheAppliedList() {
        val f = fixture()

        val outcome = apply(
            f,
            ClipConfigIngress.KEY_BUBBLE_ENABLED to "true",
            ClipConfigIngress.KEY_CLIP_BASE_URL to "http://example.invalid:8139",
        )

        assertEquals(
            "the applied list did not name bubble_enabled, or named it in a " +
                "different position: an agent reads this line to find out " +
                "whether its write landed",
            "applied: bubble_enabled,clip_base_url",
            outcome.message,
        )
        assertEquals(
            "the door did not ask the owner to turn the bubble ON exactly once",
            listOf(true),
            f.bubble.asked,
        )
        assertTrue("the bubble was not left on", f.bubble.isOn())
    }

    /**
     * 🔴 Every accepted spelling, **both ways round**, as a table.
     *
     * A single `"true"` case cannot see the mutant that matters most: Kotlin's
     * `String.toBoolean()` maps every unrecognised string to `false`, so a door
     * written with it would turn the bubble OFF for `ture` and report success.
     * The table therefore pins the mapping, and the garbage half pins that an
     * unreadable value is REFUSED rather than guessed at — with the owner never
     * asked, which is the half a message-only assertion would miss.
     */
    @Test
    fun everySpellingOfTheBubbleExtraIsReadTheRightWayRoundAndGarbageIsRefused() {
        for (raw in ClipConfigIngress.BUBBLE_TRUE + setOf("TRUE", " true ", "On", "YES")) {
            val f = fixture()
            apply(f, ClipConfigIngress.KEY_BUBBLE_ENABLED to raw)
            assertEquals("<$raw> was not read as ON", listOf(true), f.bubble.asked)
        }
        for (raw in ClipConfigIngress.BUBBLE_FALSE + setOf("FALSE", " off ", "No")) {
            val f = fixture(bubble = FakeBubble(on = true))
            apply(f, ClipConfigIngress.KEY_BUBBLE_ENABLED to raw)
            assertEquals("<$raw> was not read as OFF", listOf(false), f.bubble.asked)
        }
        for (raw in listOf("ture", "", " ", "maybe", "2", "TRUE!", "enabled")) {
            val f = fixture()
            val outcome = apply(f, ClipConfigIngress.KEY_BUBBLE_ENABLED to raw)
            assertEquals(
                "<$raw> was not refused with the door's own reason. A value " +
                    "this door cannot read must be refused, never guessed: " +
                    "String.toBoolean() would silently turn the bubble OFF for " +
                    "every one of these and report success.",
                ClipConfigIngress.Outcome.Rejected(ClipConfigIngress.bubbleRejection(raw)),
                outcome,
            )
            assertEquals(
                "the door asked the owner to change the bubble on a value it " +
                    "had already refused",
                emptyList<Boolean>(),
                f.bubble.asked,
            )
        }
    }

    /**
     * 🔴 A refused bubble value leaves **everything** as it was, which is
     * [ClipConfigIngress.Outcome.Rejected]'s documented promise.
     *
     * The URL and the token are requested in the same broadcast, so a door that
     * validated the bubble at its write site instead of up front would have
     * stored both before refusing — and would report "nothing was saved" having
     * saved two things.
     */
    @Test
    fun anUnreadableBubbleValueIsRefusedBeforeAnythingElseIsWritten() {
        val f = fixture()
        val before = f.clip.baseUrl

        val outcome = apply(
            f,
            ClipConfigIngress.KEY_BUBBLE_ENABLED to "ture",
            ClipConfigIngress.KEY_CLIP_BASE_URL to "http://example.invalid:8139",
            ClipConfigIngress.KEY_CLIP_TOKEN to "a-token",
        )

        assertEquals(
            ClipConfigIngress.Outcome.Rejected(ClipConfigIngress.bubbleRejection("ture")),
            outcome,
        )
        assertEquals(
            "a rejected request wrote the clip base URL anyway, so the " +
                "\"nothing was saved\" promise is false",
            before,
            f.clip.baseUrl,
        )
        assertFalse(
            "a rejected request stored the token anyway",
            f.clip.tokens.hasCiphertext,
        )
    }

    /**
     * 🔴 Turning the bubble on without the overlay permission is refused, and
     * the refusal is honest: nothing at all was written.
     *
     * This is the one failure the request cannot be validated against, because
     * the permission is not in it — which is why the bubble is applied first.
     * A URL in the same broadcast is the instrument: if the bubble write had
     * been last, this would report `Rejected` with the URL already stored.
     */
    @Test
    fun turningTheBubbleOnWithNoOverlayPermissionRefusesAndWritesNothing() {
        val f = fixture(bubble = FakeBubble(overlayGranted = false))
        val before = f.clip.baseUrl

        val outcome = apply(
            f,
            ClipConfigIngress.KEY_BUBBLE_ENABLED to "true",
            ClipConfigIngress.KEY_CLIP_BASE_URL to "http://example.invalid:8139",
        )

        assertEquals(
            "rejected: ${ClipConfigIngress.BUBBLE_NO_OVERLAY}",
            outcome.message,
        )
        assertEquals(
            "the refusal reported that nothing was written, but the clip base " +
                "URL in the same broadcast had already been stored",
            before,
            f.clip.baseUrl,
        )
        assertFalse("the bubble was recorded as on after a refusal", f.bubble.isOn())
    }

    /**
     * An absent `bubble_enabled` extra means **leave it alone** — the same rule
     * every other field on this door follows, and the reason the field is
     * nullable rather than defaulted.
     */
    @Test
    fun aRequestWithoutTheBubbleExtraNeverAsksTheOwnerAnything() {
        val f = fixture(bubble = FakeBubble(on = true))

        val outcome = apply(f, ClipConfigIngress.KEY_CLIP_TOKEN to "a-token")

        assertEquals("applied: clip_token", outcome.message)
        assertEquals(
            "a request that said nothing about the bubble still reached the " +
                "owner, so configuring the clipboard would move the bubble",
            emptyList<Boolean>(),
            f.bubble.asked,
        )
        assertTrue("the bubble was changed by a request that did not ask", f.bubble.isOn())
    }

    /** `TokenStoreTest`'s locator: search rather than hardcode, and refuse to guess. */
    private fun findPrefsFile(name: String): File? {
        for (dir in listOf(
            File(ctx.applicationInfo.dataDir, "shared_prefs"),
            File(ctx.filesDir.parentFile, "shared_prefs"),
        )) {
            val f = File(dir, "$name.xml")
            if (f.exists()) return f
        }
        return null
    }
}

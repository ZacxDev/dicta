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

    private class Fixture(
        val prefs: SharedPreferences,
        val clip: ClipSettings,
        val stt: SttSettings,
    )

    private fun fixture(
        name: String = "ingress_${counter++}",
        cipher: SecretCipher = ReversingCipher(),
    ): Fixture {
        val p = prefs(name)
        return Fixture(
            p,
            ClipSettings(p, TokenStore(p, cipher, ClipSettings.KEY_TOKEN_CIPHERTEXT)),
            SttSettings(p, TokenStore(p, cipher, TokenStore.KEY_TOKEN_CIPHERTEXT)),
        )
    }

    private fun apply(f: Fixture, vararg args: Pair<String, String>) =
        ClipConfigIngress.apply(
            ClipConfigIngress.parse { key -> args.toMap()[key] },
            f.clip,
            f.stt,
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
    fun theSttBaseUrlIsDeliberatelyNotAFieldOfThisDoor() {
        // 🔴 The absence is a DECISION, pinned so it cannot be quietly added
        // without reading why: there is no SttEndpoints.rejectionFor, so
        // accepting it would mean either writing an unvalidated URL through a
        // programmatic door, or growing a second copy of
        // ClipEndpoints.rejectionFor whose message names /v1/clips for a
        // service that does not serve it.
        val f = fixture()
        val before = f.stt.baseUrl
        val outcome = apply(f, "stt_base_url" to "http://10.42.0.10:9999")
        assertEquals(ClipConfigIngress.Outcome.NothingRequested, outcome)
        assertEquals(before, f.stt.baseUrl)
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

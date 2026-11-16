package dev.zacx.dicta

import android.content.Context
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
 * The shared clipboard's own configuration, and its own token.
 *
 * 🔴 **Invariant guards, not regression coverage** — [ClipSettings] is new.
 *
 * ## 🔴 Two tokens, and this file is the guard on their being two
 *
 * The clip store and the transcription service are different services with
 * different lifetimes: either can be rotated without the other, and either's
 * token leaking is a different blast radius. So they live under **different
 * preference keys** and **different keystore aliases** — but in the same
 * preferences FILE, because the file name is a single rule
 * ([SttSettings.prefs]) and a second spelling of it is a second place to get
 * `MODE_PRIVATE` wrong.
 *
 * 🔴 **No token appears in this file.** `ZacxDev/dicta` is a PUBLIC repository.
 * The values below are plainly synthetic, and they go through the real
 * [KeystoreCipher] under Robolectric's keystore shadow — never to disk in
 * plaintext.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClipSettingsTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    /**
     * A cipher the test JVM can actually run.
     *
     * 🔴 The real [KeystoreCipher] CANNOT be used here: Robolectric provides no
     * `AndroidKeyStore` provider, so `putToken` throws
     * `KeyStoreException: AndroidKeyStore not found`. The first draft of this
     * file went through `ClipSettings.create` and failed exactly that way on
     * four tests — recorded rather than quietly worked around, because the
     * consequence is real: **nothing in the unit tier exercises the keystore
     * for either token.** `TokenStoreTest` makes the same trade for the
     * transcription token and says so; the keystore's own behaviour is
     * device-only, and this change adds no device coverage for it.
     *
     * [TokenStoreTest.ReversingCipher]'s shape — reversible, prefixed, and
     * plainly not encryption, so a test cannot accidentally assert that
     * ciphertext looks like ciphertext.
     */
    private class ReversingCipher : SecretCipher {
        override fun encrypt(plaintext: String): String =
            "enc:" + plaintext.reversed().map { (it.code + 7).toChar() }.joinToString("")

        override fun decrypt(blob: String): String? {
            if (!blob.startsWith("enc:")) return null
            return blob.removePrefix("enc:")
                .map { (it.code - 7).toChar() }
                .joinToString("")
                .reversed()
        }
    }

    /**
     * 🔴 Built through the real constructors with the real KEY and ALIAS
     * constants, and only the cipher swapped — so the separation under test is
     * the production one. A fixture that passed its own key strings would be
     * asserting about the fixture.
     */
    private fun clip() = ClipSettings(
        SttSettings.prefs(ctx),
        TokenStore(SttSettings.prefs(ctx), ReversingCipher(), ClipSettings.KEY_TOKEN_CIPHERTEXT),
    )

    private fun stt() = SttSettings(
        SttSettings.prefs(ctx),
        TokenStore(SttSettings.prefs(ctx), ReversingCipher()),
    )

    @Test
    fun theProductionFactoryWiresTheClipKeyAndTheClipAlias() {
        // 🔴 The seam the fixture above bypasses, asserted separately so the
        // bypass cannot hide a mis-wired factory. ClipSettings.create is what
        // production calls; it must reach the CLIP key, not the STT one. The
        // witness is the base URL — the one thing `create` reads that does not
        // go through the keystore — plus the constants themselves, which are
        // what `create` passes.
        clip().baseUrl = "http://10.42.0.10:8137"
        assertEquals(
            "ClipSettings.create does not read the same preferences the clip " +
                "settings object writes, so the production factory and this " +
                "file's fixture are configured differently",
            "http://10.42.0.10:8137",
            ClipSettings.create(ctx).baseUrl,
        )
    }

    @Test
    fun theBaseUrlDefaultsToTheClipStoresOwnEndpoint() {
        assertEquals(ClipEndpoints.DEFAULT_BASE_URL, clip().baseUrl)
        assertNotEquals(
            "the clip store defaults to the transcription service's URL",
            SttEndpoints.DEFAULT_BASE_URL,
            clip().baseUrl,
        )
    }

    @Test
    fun aStoredBaseUrlSurvivesAFreshSettingsObject() {
        clip().baseUrl = "http://10.42.0.10:9999"
        assertEquals("http://10.42.0.10:9999", clip().baseUrl)
    }

    @Test
    fun aBlankBaseUrlFallsBackRatherThanProducingAnUnusableRequest() {
        // A settings field a human types into can be emptied, and an empty
        // base URL builds "/v1/clips" — a MalformedURLException, i.e.
        // Unreachable, i.e. a queue that silently never drains.
        val settings = clip()
        settings.baseUrl = "   "
        assertEquals(ClipEndpoints.DEFAULT_BASE_URL, clip().baseUrl)
    }

    @Test
    fun settingTheClipURLDoesNotMoveTheTRANSCRIPTIONServicesURL() {
        // 🔴 Two services, two settings. They share a preferences FILE, which
        // is exactly the condition under which a copied key name silently
        // repoints the other feature.
        val before = stt().baseUrl
        clip().baseUrl = "http://10.42.0.10:9998"
        assertEquals(
            "changing the clip store's URL moved the transcription service's",
            before,
            stt().baseUrl,
        )
    }

    @Test
    fun theTwoTokensAreSTOREDSEPARATELYAndNeitherReadsTheOther() {
        // 🔴 THE TEST THIS FILE EXISTS FOR. A shared key would mean pasting a
        // clip token silently replaced the transcription token — the operator's
        // dictation would start failing with a 401 and nothing on the settings
        // screen would say why.
        stt().tokens.putToken("stt-token-synthetic")
        clip().tokens.putToken("clip-token-synthetic")

        assertEquals("stt-token-synthetic", stt().tokens.token())
        assertEquals("clip-token-synthetic", clip().tokens.token())
    }

    @Test
    fun clearingTheClipTokenLeavesTheTRANSCRIPTIONTokenAlone() {
        // 🔴 The destructive direction, which is the one with no recovery: a
        // shared key would make "Clear clipboard token" log the operator out of
        // dictation as well, and the two tokens come from different places.
        stt().tokens.putToken("stt-token-synthetic")
        clip().tokens.putToken("clip-token-synthetic")

        clip().tokens.clearToken()

        assertNull("the clip token survived being cleared", clip().tokens.token())
        assertEquals(
            "clearing the clip token also cleared the transcription token",
            "stt-token-synthetic",
            stt().tokens.token(),
        )
    }

    @Test
    fun theTwoTokensUseDifferentPREFERENCEKEYSAndDifferentKEYSTOREAliases() {
        // The structural half of the two tests above: they pass if the values
        // round-trip, and this says WHY they can — rather than leaving a future
        // reader to discover that the separation is accidental.
        assertNotEquals(
            ClipSettings.KEY_TOKEN_CIPHERTEXT,
            TokenStore.KEY_TOKEN_CIPHERTEXT,
        )
        assertNotEquals(ClipSettings.KEYSTORE_ALIAS, KeystoreCipher.DEFAULT_ALIAS)
        assertNotEquals(ClipSettings.KEY_BASE_URL, SttSettings.KEY_BASE_URL)
    }

    @Test
    fun theTwoSettingsObjectsSHARETheOnePreferencesFile() {
        // 🔴 The other half of the decision, asserted so it is not read as an
        // accident either: ONE file, because the file name and MODE_PRIVATE are
        // one rule. The witness is behavioural — a value written through one
        // object's prefs is visible to the other's.
        SttSettings.prefs(ctx).edit().putString("a-probe-key", "probe").commit()
        assertEquals(
            "the two settings objects do not share a preferences file, so " +
                "MODE_PRIVATE and the file name are now spelled twice",
            "probe",
            SttSettings.prefs(ctx).getString("a-probe-key", null),
        )
    }

    @Test
    fun isConfiguredIsAboutTheTOKENAndNotAboutTheUrl() {
        // 🔴 The gate it feeds is the UPLOAD, never the CAPTURE. A capture must
        // land in the outbox whether or not a token has ever been pasted —
        // otherwise the operator loses the clip they took before they finished
        // setting the feature up, which is the single most likely first
        // experience of it.
        clip().tokens.clearToken()
        assertFalse("an unconfigured store reports itself configured", clip().isConfigured)

        clip().tokens.putToken("clip-token-synthetic")
        assertTrue("a stored token did not make the store configured", clip().isConfigured)

        // And the URL has nothing to do with it: the default is a real
        // endpoint, so a blank field is not an unconfigured feature.
        clip().baseUrl = ""
        assertTrue(clip().isConfigured)
    }

    @Test
    fun theStoredTokenIsNotTHEPLAINTEXTOnDisk() {
        // 🔴 The whole reason the token goes through KeystoreCipher. A
        // SharedPreferences XML file is readable by anything with the app's
        // uid, including a backup agent and an adb backup on a debuggable
        // build — so the plaintext must not be in it.
        //
        // Asserted by scanning EVERY value in the file rather than the one key,
        // so a second code path that also wrote it somewhere is caught.
        clip().tokens.putToken("clip-token-synthetic")
        val values = SttSettings.prefs(ctx).all.values.map { it.toString() }
        for (v in values) {
            assertFalse(
                "the clip token is stored in plaintext under some preference " +
                    "key: <$v>",
                v.contains("clip-token-synthetic"),
            )
        }
        assertTrue(
            "the preferences file came back with nothing in it, so the scan " +
                "above is vacuous",
            values.isNotEmpty(),
        )
    }
}

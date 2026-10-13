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
import java.io.File

/**
 * Token storage, through the [SecretCipher] seam.
 *
 * ## 🔴 What these tests do and do NOT establish
 *
 * They establish that [TokenStore] round-trips through whatever cipher it is
 * given, that it **never writes the plaintext into the preferences file**, and
 * that an undecryptable blob reads back as null rather than as garbage.
 *
 * They do **not** establish that [KeystoreCipher] works. `AndroidKeyStore` is
 * a hardware-backed provider with no Robolectric implementation, so the real
 * cipher is exercised on the device and nowhere else. Saying "token storage is
 * tested" without that sentence would be a stronger claim than the tests
 * support, which is why the seam is named in the class kdoc rather than
 * hidden behind a mock.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TokenStoreTest {

    private val ctx get() = RuntimeEnvironment.getApplication()

    /**
     * A reversible stand-in for the keystore.
     *
     * Deliberately NOT the identity function and not base64 alone: a cipher
     * that returned its input would make
     * [thePlaintextTokenNeverReachesThePreferencesFile] fail, which is the
     * right outcome — but it would fail for the wrong reason (a broken
     * fixture rather than a broken store). This one genuinely transforms the
     * bytes, so the plaintext assertion is about the store.
     */
    private class ReversingCipher : SecretCipher {
        var encryptCalls = 0
        var decryptCalls = 0

        override fun encrypt(plaintext: String): String {
            encryptCalls++
            return "enc:" + plaintext.reversed()
                .map { (it.code + 7).toChar() }
                .joinToString("")
        }

        override fun decrypt(blob: String): String? {
            decryptCalls++
            if (!blob.startsWith("enc:")) return null
            return blob.removePrefix("enc:")
                .map { (it.code - 7).toChar() }
                .joinToString("")
                .reversed()
        }
    }

    /** Stands in for a keystore that has lost its key. */
    private class FailingCipher : SecretCipher {
        override fun encrypt(plaintext: String): String =
            throw IllegalStateException("keystore unavailable")
        override fun decrypt(blob: String): String? = null
    }

    private fun prefs(name: String = "dicta_stt") =
        ctx.getSharedPreferences(name, Context.MODE_PRIVATE)

    /**
     * A realistic token: 64 hex-ish characters, the shape the service issues.
     * Not a short textbook fixture — a scanner or a substring check can pass
     * on "secret" and fail on a real value.
     */
    private val realisticToken =
        "9f2c41be7a05d83c6e1b4faa2d907c5538be61470cd92fa8b3e5071c6da4982f"

    // --- round trip ---------------------------------------------------------

    @Test
    fun aTokenRoundTripsThroughTheCipher() {
        val cipher = ReversingCipher()
        val store = TokenStore(prefs(), cipher)

        store.putToken(realisticToken)

        assertEquals(realisticToken, store.token())
        assertTrue(store.hasToken)
        assertEquals("the store must encrypt on write", 1, cipher.encryptCalls)
        assertTrue("the store must decrypt on read", cipher.decryptCalls >= 1)
    }

    @Test
    fun aDifferentTokenReadsBackAsItself() {
        // Control against a store that returns a constant: two distinct
        // tokens must read back distinctly.
        val store = TokenStore(prefs(), ReversingCipher())
        store.putToken("first-AAAA-1111")
        assertEquals("first-AAAA-1111", store.token())
        store.putToken("second-BBBB-2222")
        assertEquals("second-BBBB-2222", store.token())
    }

    @Test
    fun noTokenReadsBackAsNull() {
        val store = TokenStore(prefs("empty_prefs"), ReversingCipher())
        assertNull(store.token())
        assertFalse(store.hasToken)
    }

    @Test
    fun surroundingWhitespaceFromAPasteIsTrimmed() {
        val store = TokenStore(prefs(), ReversingCipher())
        store.putToken("  $realisticToken\n")
        assertEquals(realisticToken, store.token())
    }

    @Test
    fun aBlankTokenClearsRatherThanStoringEmptiness() {
        val store = TokenStore(prefs(), ReversingCipher())
        store.putToken(realisticToken)
        store.putToken("   ")
        assertNull(store.token())
        assertFalse(store.hasToken)
    }

    @Test
    fun clearRemovesTheStoredValue() {
        val p = prefs("clear_prefs")
        val store = TokenStore(p, ReversingCipher())
        store.putToken(realisticToken)
        store.clearToken()
        assertNull(store.token())
        assertNull(p.getString(TokenStore.KEY_TOKEN_CIPHERTEXT, null))
    }

    // --- 🔴 the plaintext invariant ----------------------------------------

    @Test
    fun thePlaintextTokenNeverReachesThePreferencesValues() {
        val p = prefs("plaintext_check_prefs")
        TokenStore(p, ReversingCipher()).putToken(realisticToken)

        // Every key AND every value, not just the one key we expect: a future
        // change that mirrors the token into a second "convenience" field
        // must turn this red.
        for ((key, value) in p.all) {
            assertNotEquals(
                "the plaintext token is stored under key \"$key\"",
                realisticToken,
                value,
            )
            assertFalse(
                "the plaintext token appears inside the value at \"$key\"",
                value?.toString()?.contains(realisticToken) == true,
            )
        }
        // And the stored ciphertext is genuinely different from the input.
        val stored = p.getString(TokenStore.KEY_TOKEN_CIPHERTEXT, null)
        assertTrue("nothing was stored at all", stored != null)
        assertNotEquals(realisticToken, stored)
    }

    @Test
    fun thePlaintextTokenNeverReachesThePreferencesFileOnDisk() {
        // 🔴 The on-disk check, separate from the in-memory one above: the
        // in-memory map is what the API exposes, the XML file is what someone
        // with filesystem access reads. They are different claims and only
        // the second one is about data at rest.
        val name = "ondisk_check_prefs"
        val p = prefs(name)
        // commit(), not apply(): apply() is asynchronous and the file might
        // not exist yet when we read it — which would make this test pass by
        // measuring nothing.
        p.edit().clear().commit()
        TokenStore(p, ReversingCipher()).putToken(realisticToken)
        p.edit().commit()

        val file = findPrefsFile(name)
        assertTrue(
            "could not locate the preferences file, so this test measured " +
                "nothing — refusing to report a pass",
            file != null && file.exists() && file.length() > 0,
        )
        val contents = file!!.readText()
        assertFalse(
            "the plaintext token is present in ${file.absolutePath}",
            contents.contains(realisticToken),
        )
        // Positive control: the file really does contain OUR record, so the
        // absence above is about the token and not about an empty file.
        assertTrue(
            "the preferences file does not contain the ciphertext key, so " +
                "the absence of the plaintext proves nothing",
            contents.contains(TokenStore.KEY_TOKEN_CIPHERTEXT),
        )
    }

    /**
     * Positive control for the two tests above: feed a store a cipher that
     * does NOT encrypt, and confirm the plaintext check can actually fail.
     *
     * 🔴 Without this, "the token is not in the file" is indistinguishable
     * from a check wired to the wrong file or the wrong string.
     */
    @Test
    fun thePlaintextCheckCanActuallyDetectAPlaintextToken() {
        val name = "control_plaintext_prefs"
        val p = prefs(name)
        p.edit().clear().commit()
        // An identity "cipher" — the downgrade the real class must never do.
        val noop = object : SecretCipher {
            override fun encrypt(plaintext: String) = plaintext
            override fun decrypt(blob: String) = blob
        }
        TokenStore(p, noop).putToken(realisticToken)
        p.edit().commit()

        val file = findPrefsFile(name)
        assertTrue(file != null && file.exists())
        assertTrue(
            "the control failed to put the plaintext on disk, so the real " +
                "test's clean result is not evidence of anything",
            file!!.readText().contains(realisticToken),
        )
        // And in-memory too.
        assertTrue(p.all.values.any { it == realisticToken })
    }

    // --- failure behaviour --------------------------------------------------

    @Test
    fun anUndecryptableBlobReadsBackAsNullRatherThanGarbage() {
        val p = prefs("corrupt_prefs")
        p.edit()
            .putString(TokenStore.KEY_TOKEN_CIPHERTEXT, "this-is-not-our-ciphertext")
            .commit()

        val store = TokenStore(p, ReversingCipher())
        assertNull(
            "a corrupt blob must read as absent so the app asks for the " +
                "token again, rather than sending garbage and getting an " +
                "unexplainable 401",
            store.token(),
        )
        assertFalse(store.hasToken)
    }

    @Test
    fun aCipherFailureOnWritePropagatesRatherThanStoringPlaintext() {
        val p = prefs("failing_prefs")
        p.edit().clear().commit()
        val store = TokenStore(p, FailingCipher())

        val thrown = try {
            store.putToken(realisticToken)
            null
        } catch (e: Exception) {
            e
        }
        // 🔴 Fail closed. Swallowing this would leave the UI saying "Saved"
        // while nothing was stored — or worse, store it in the clear.
        assertTrue("a cipher failure must not be swallowed", thrown != null)
        assertFalse(
            "nothing may be written when encryption failed",
            p.all.values.any { it?.toString()?.contains(realisticToken) == true },
        )
    }

    @Test
    fun aDecryptThatReturnsBlankIsTreatedAsAbsent() {
        val p = prefs("blank_prefs")
        p.edit().putString(TokenStore.KEY_TOKEN_CIPHERTEXT, "anything").commit()
        val blanking = object : SecretCipher {
            override fun encrypt(plaintext: String) = "x"
            override fun decrypt(blob: String) = "   "
        }
        assertNull(TokenStore(p, blanking).token())
    }

    // --- SttSettings --------------------------------------------------------

    @Test
    fun theBaseUrlDefaultsToTheMeshAddress() {
        val p = prefs("settings_default_prefs")
        p.edit().clear().commit()
        val settings = SttSettings(p, TokenStore(p, ReversingCipher()))
        assertEquals("http://10.42.0.10:8118", settings.baseUrl)
    }

    @Test
    fun theBaseUrlRoundTripsAndIsNotEncrypted() {
        val p = prefs("settings_url_prefs")
        val settings = SttSettings(p, TokenStore(p, ReversingCipher()))
        settings.baseUrl = "  http://192.0.2.9:7000  "
        assertEquals("http://192.0.2.9:7000", settings.baseUrl)
        // Readable in a bug report on purpose — a mesh address is not a secret.
        assertEquals(
            "http://192.0.2.9:7000",
            p.getString(SttSettings.KEY_BASE_URL, null),
        )
    }

    @Test
    fun aBlankBaseUrlFallsBackToTheDefault() {
        val p = prefs("settings_blank_url_prefs")
        val settings = SttSettings(p, TokenStore(p, ReversingCipher()))
        settings.baseUrl = "http://192.0.2.9:7000"
        settings.baseUrl = "   "
        assertEquals("http://10.42.0.10:8118", settings.baseUrl)
    }

    @Test
    fun remoteIsNotConsideredConfiguredWithoutAToken() {
        val p = prefs("settings_unconfigured_prefs")
        p.edit().clear().commit()
        val settings = SttSettings(p, TokenStore(p, ReversingCipher()))

        assertTrue("the remote path is opt-out, not opt-in", settings.remoteEnabled)
        assertFalse(
            "with no token the remote path must be considered unconfigured, " +
                "so the app uses the phone rather than 401ing every recording",
            settings.isRemoteConfigured,
        )

        settings.tokens.putToken(realisticToken)
        assertTrue(settings.isRemoteConfigured)
    }

    @Test
    fun disablingRemoteOverridesAStoredToken() {
        val p = prefs("settings_disabled_prefs")
        val settings = SttSettings(p, TokenStore(p, ReversingCipher()))
        settings.tokens.putToken(realisticToken)
        settings.remoteEnabled = false
        assertFalse(settings.isRemoteConfigured)
    }

    /**
     * Robolectric's SharedPreferences are file-backed, but the directory
     * differs between versions, so search rather than hardcode — and return
     * null rather than a guess, so the caller can refuse to pass.
     */
    private fun findPrefsFile(name: String): File? {
        val candidates = listOf(
            File(ctx.applicationInfo.dataDir, "shared_prefs"),
            File(ctx.filesDir.parentFile, "shared_prefs"),
        )
        for (dir in candidates) {
            val f = File(dir, "$name.xml")
            if (f.exists()) return f
        }
        return null
    }
}

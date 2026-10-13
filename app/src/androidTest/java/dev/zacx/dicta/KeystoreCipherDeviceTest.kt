package dev.zacx.dicta

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore

/**
 * The **real** [KeystoreCipher], against the **real** `AndroidKeyStore`.
 *
 * This is the only place that can exist. `AndroidKeyStore` is a
 * hardware-backed provider with no Robolectric implementation, so
 * [TokenStoreTest] necessarily exercises the [SecretCipher] *seam* with a
 * stand-in and says so in its kdoc. Without this file, "the token is
 * encrypted at rest under a non-exportable key" would be a claim about code
 * nothing had ever run.
 *
 * Run with:
 * ```
 * gradle :app:connectedDebugAndroidTest
 * ```
 */
@RunWith(AndroidJUnit4::class)
class KeystoreCipherDeviceTest {

    private val ctx: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** A distinct alias per run, so one test cannot inherit another's key. */
    private fun alias(tag: String) = "dicta.test.$tag.${System.nanoTime()}"

    private val realisticToken =
        "9f2c41be7a05d83c6e1b4faa2d907c5538be61470cd92fa8b3e5071c6da4982f"

    @Test
    fun aTokenRoundTripsThroughTheRealKeystore() {
        val cipher = KeystoreCipher(alias("roundtrip"))
        val blob = cipher.encrypt(realisticToken)

        assertNotEquals(
            "the ciphertext must not be the plaintext",
            realisticToken, blob,
        )
        assertFalse(
            "the plaintext must not be a substring of the ciphertext",
            blob.contains(realisticToken),
        )
        assertEquals(realisticToken, cipher.decrypt(blob))
    }

    @Test
    fun theKeyIsGeneratedInTheKeystoreAndIsNotExportable() {
        val a = alias("nonexportable")
        KeystoreCipher(a).encrypt("x")

        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val entry = ks.getEntry(a, null) as? KeyStore.SecretKeyEntry
        assertTrue("the key was not stored in AndroidKeyStore", entry != null)
        // 🔴 The actual guarantee that replaces EncryptedSharedPreferences:
        // the key material never leaves the keystore.
        assertNull(
            "the key material is exportable — that is not Keystore-backed " +
                "encryption at rest",
            entry!!.secretKey.encoded,
        )
        assertEquals("AES", entry.secretKey.algorithm)
    }

    @Test
    fun eachEncryptionUsesAFreshIvSoTheSamePlaintextDiffers() {
        // setRandomizedEncryptionRequired(true) is what makes this hold. Two
        // identical ciphertexts would mean a reused IV, which for GCM is a
        // catastrophic failure, not a cosmetic one.
        val cipher = KeystoreCipher(alias("iv"))
        val first = cipher.encrypt(realisticToken)
        val second = cipher.encrypt(realisticToken)
        assertNotEquals(
            "the same plaintext encrypted twice produced identical bytes — " +
                "the GCM IV is being reused",
            first, second,
        )
        assertEquals(realisticToken, cipher.decrypt(first))
        assertEquals(realisticToken, cipher.decrypt(second))
    }

    @Test
    fun aDifferentKeyCannotDecryptAnothersCiphertext() {
        val blob = KeystoreCipher(alias("keyA")).encrypt(realisticToken)
        // GCM's authentication tag fails, and the cipher reports null rather
        // than returning garbage that would be sent as a bearer token.
        assertNull(KeystoreCipher(alias("keyB")).decrypt(blob))
    }

    @Test
    fun aTamperedCiphertextIsRejectedRatherThanDecryptedToGarbage() {
        val a = alias("tamper")
        val cipher = KeystoreCipher(a)
        val blob = cipher.encrypt(realisticToken)
        // Flip one base64 character in the body (not the IV prefix).
        val bytes = blob.toCharArray()
        val at = bytes.size - 4
        bytes[at] = if (bytes[at] == 'A') 'B' else 'A'
        assertNull(cipher.decrypt(String(bytes)))
    }

    @Test
    fun aCorruptOrForeignBlobIsNullRatherThanAThrow() {
        val cipher = KeystoreCipher(alias("corrupt"))
        assertNull(cipher.decrypt("not base64 at all !!!"))
        assertNull(cipher.decrypt(""))
        // Valid base64 but shorter than the IV.
        assertNull(cipher.decrypt("AAAA"))
    }

    /**
     * 🔴 The data-at-rest claim, end to end through the **real** cipher and
     * the **real** preferences file on the device.
     *
     * [TokenStoreTest] asserts the same property against a stand-in cipher on
     * the JVM. This asserts it against the shipping configuration.
     */
    @Test
    fun theRealStoreNeverWritesThePlaintextToTheRealPreferencesFile() {
        val name = "dicta_stt_devicetest"
        val prefs = ctx.getSharedPreferences(name, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        TokenStore(prefs, KeystoreCipher(alias("atrest"))).putToken(realisticToken)
        prefs.edit().commit()

        val file = File(File(ctx.applicationInfo.dataDir, "shared_prefs"), "$name.xml")
        assertTrue(
            "the preferences file was not written, so this test measured " +
                "nothing: ${file.absolutePath}",
            file.exists() && file.length() > 0,
        )
        val contents = file.readText()
        assertFalse(
            "the plaintext token is on disk at ${file.absolutePath}",
            contents.contains(realisticToken),
        )
        // Positive control: the record IS there, so the absence above is about
        // the token and not about an empty file.
        assertTrue(
            "the ciphertext key is missing from the file, so the absence of " +
                "the plaintext proves nothing",
            contents.contains(TokenStore.KEY_TOKEN_CIPHERTEXT),
        )
        prefs.edit().clear().commit()
    }

    @Test
    fun theStoreReadsBackWhatTheRealCipherWrote() {
        val name = "dicta_stt_devicetest_rt"
        val prefs = ctx.getSharedPreferences(name, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val store = TokenStore(prefs, KeystoreCipher(alias("storert")))
        store.putToken(realisticToken)
        assertEquals(realisticToken, store.token())
        store.clearToken()
        assertNull(store.token())
    }
}

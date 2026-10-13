package dev.zacx.dicta

import android.content.Context
import android.content.SharedPreferences

/**
 * Where the bearer token lives.
 *
 * The *ciphertext* goes into ordinary [SharedPreferences]; the key that
 * protects it lives in `AndroidKeyStore` and is not exportable. That is the
 * same guarantee `EncryptedSharedPreferences` offers, without the AndroidX
 * runtime dependency — see [KeystoreCipher]'s kdoc.
 *
 * 🔴 Two invariants, both load-bearing and both pinned by tests:
 *
 *  1. The plaintext token is **never** written to the preferences file under
 *     any key. The test reads the on-disk XML back and greps it.
 *  2. A token that cannot be decrypted reads back as null rather than as a
 *     corrupt string, so the app asks the user to re-enter it instead of
 *     sending garbage and getting a 401 it cannot explain.
 */
class TokenStore(
    private val prefs: SharedPreferences,
    private val cipher: SecretCipher,
) {

    /**
     * @return the token, or null when none is stored or it will not decrypt.
     */
    fun token(): String? {
        val blob = prefs.getString(KEY_TOKEN_CIPHERTEXT, null) ?: return null
        return cipher.decrypt(blob)?.takeIf { it.isNotBlank() }
    }

    val hasToken: Boolean get() = token() != null

    /**
     * Stores [token], or clears it when blank.
     *
     * @throws RuntimeException propagated from the cipher. Deliberately not
     *   swallowed: a caller told "saved" when nothing was encrypted would
     *   send no token and see a 401 with no explanation.
     */
    fun putToken(token: String) {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) {
            clearToken()
            return
        }
        prefs.edit()
            .putString(KEY_TOKEN_CIPHERTEXT, cipher.encrypt(trimmed))
            .apply()
    }

    fun clearToken() {
        prefs.edit().remove(KEY_TOKEN_CIPHERTEXT).apply()
    }

    companion object {
        /**
         * The suffix says what the value is. A future reader must not be able
         * to mistake this for a plaintext field and "simplify" the cipher
         * away.
         */
        const val KEY_TOKEN_CIPHERTEXT: String = "stt_token_ciphertext_v1"
    }
}

/**
 * The user-visible configuration: the service base URL, and the token via
 * [TokenStore].
 *
 * The base URL is **not** encrypted — it is a LAN/mesh address, not a secret,
 * and encrypting it would only make it unreadable in a bug report.
 */
class SttSettings(
    private val prefs: SharedPreferences,
    val tokens: TokenStore,
) {

    /** Defaults to the mesh address; whatever the user typed otherwise. */
    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: SttEndpoints.DEFAULT_BASE_URL
        set(value) {
            val trimmed = value.trim()
            if (trimmed.isEmpty()) {
                prefs.edit().remove(KEY_BASE_URL).apply()
            } else {
                prefs.edit().putString(KEY_BASE_URL, trimmed).apply()
            }
        }

    /**
     * Whether the remote path should be attempted at all.
     *
     * Defaults to true, but a configuration with no token is treated as "off"
     * by [isRemoteConfigured] rather than producing a 401 on every recording.
     */
    var remoteEnabled: Boolean
        get() = prefs.getBoolean(KEY_REMOTE_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_REMOTE_ENABLED, value).apply()

    /**
     * The dictation language the user explicitly chose, or **null** to follow
     * the device locale.
     *
     * 🔴 Null is a real, reachable state and not merely "unset": "follow the
     * device" is the default and the first entry in the picker, so a user can
     * deliberately come back to it. Storing the resolved code instead would
     * freeze whatever the locale happened to be on the day they first opened
     * Settings.
     *
     * The getter filters through [SttLanguage.SUPPORTED] rather than trusting
     * the file: a code outside the allowlist is an unhandled **500** on the
     * service, so a preference written by an older build (or hand-edited) must
     * degrade to "follow the device" rather than break every request.
     */
    var language: String?
        get() = prefs.getString(KEY_LANGUAGE, null)
            ?.trim()
            ?.lowercase()
            ?.takeIf { it in SttLanguage.SUPPORTED }
        set(value) {
            val normalised = value?.trim()?.lowercase()
            if (normalised == null || normalised !in SttLanguage.SUPPORTED) {
                prefs.edit().remove(KEY_LANGUAGE).apply()
            } else {
                prefs.edit().putString(KEY_LANGUAGE, normalised).apply()
            }
        }

    val isRemoteConfigured: Boolean
        get() = remoteEnabled && tokens.hasToken

    companion object {
        const val KEY_BASE_URL: String = "stt_base_url"
        const val KEY_REMOTE_ENABLED: String = "stt_remote_enabled"
        const val KEY_LANGUAGE: String = "stt_language"
        private const val PREFS_NAME = "dicta_stt"

        /** The one place the preferences file name is spelled. */
        fun create(context: Context): SttSettings {
            val prefs = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return SttSettings(prefs, TokenStore(prefs, KeystoreCipher()))
        }

        fun prefsFileName(): String = "$PREFS_NAME.xml"
    }
}

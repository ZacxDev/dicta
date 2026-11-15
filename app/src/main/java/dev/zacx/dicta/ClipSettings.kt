package dev.zacx.dicta

import android.content.Context
import android.content.SharedPreferences

/**
 * The shared clipboard's configuration: its base URL, and its own bearer token.
 *
 * ## 🔴 A SEPARATE KEY from the transcription token, and that is the decision
 *
 * [KeystoreCipher] is reused — AES-256/GCM, a non-exportable `AndroidKeyStore`
 * key, **fails closed** — but under its own alias, and the ciphertext goes into
 * its own preference key. [TokenStore] keys on one constant per instance, so one
 * shared token would mean **rotating either rotates both**, and the two have
 * different blast radii: the transcription token reaches a GPU box that returns
 * text, the clip token reaches a store holding everything the operator has
 * copied on any device. Nothing is committed on any path; both are pasted at
 * runtime.
 *
 * 🔴 A separate class rather than three more fields on [SttSettings], for the
 * same reason the keys are separate: the two are configured, cleared and
 * rotated independently, and a single object would make "clear the token" an
 * ambiguous act. They **share the preferences FILE**, through
 * [SttSettings.prefs] — one place spells that name, which is the rule
 * `SttSettings.create`'s kdoc already states.
 *
 * The base URL is **not** encrypted, for [SttSettings]'s stated reason: *"it is
 * a LAN/mesh address, not a secret, and encrypting it would only make it
 * unreadable in a bug report"*.
 */
class ClipSettings(
    private val prefs: SharedPreferences,
    val tokens: TokenStore,
) {

    /** Defaults to the mesh address; whatever the operator typed otherwise. */
    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: ClipEndpoints.DEFAULT_BASE_URL
        set(value) {
            val trimmed = value.trim()
            if (trimmed.isEmpty()) {
                prefs.edit().remove(KEY_BASE_URL).apply()
            } else {
                prefs.edit().putString(KEY_BASE_URL, trimmed).apply()
            }
        }

    /**
     * Whether a flush can be attempted at all.
     *
     * 🔴 This gates the UPLOAD and never the CAPTURE. A capture with no token
     * configured still lands in the outbox and still reports success, because
     * the outbox is the primary path: the operator pastes a token later and the
     * queue drains. Gating the capture on configuration would discard the
     * user's selection for a setting they can fix in ten seconds.
     */
    val isConfigured: Boolean get() = tokens.hasToken

    companion object {

        const val KEY_BASE_URL: String = "clip_base_url"

        /**
         * 🔴 Distinct from [TokenStore.KEY_TOKEN_CIPHERTEXT] — see the class
         * kdoc. The `_v1` suffix and the `_ciphertext` infix are both
         * deliberate: the suffix leaves room for a format change, and the infix
         * stops a future reader mistaking the value for a plaintext field and
         * "simplifying" the cipher away.
         */
        const val KEY_TOKEN_CIPHERTEXT: String = "clip_token_ciphertext_v1"

        /** 🔴 Distinct from [KeystoreCipher.DEFAULT_ALIAS]. */
        const val KEYSTORE_ALIAS: String = "dicta.clip.token.v1"

        fun create(context: Context): ClipSettings {
            val prefs = SttSettings.prefs(context)
            return ClipSettings(
                prefs,
                TokenStore(
                    prefs,
                    KeystoreCipher(KEYSTORE_ALIAS),
                    KEY_TOKEN_CIPHERTEXT,
                ),
            )
        }
    }
}

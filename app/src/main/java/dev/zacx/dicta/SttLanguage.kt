package dev.zacx.dicta

import java.util.Locale

/**
 * Maps a platform locale onto the **bare ISO code** the transcription service
 * accepts, and onto nothing else.
 *
 * ## Why this class exists at all
 *
 * The service hands the `language` form field straight to its audio processor
 * **without validating it**, and the processor raises `ValueError` for an
 * unknown value *outside* the handler's try/except. The observable consequence
 * is not a 400 — it is an **unhandled HTTP 500 with a traceback**.
 *
 * The trap is that the obvious thing to send is exactly the thing that breaks
 * it: `Locale.getDefault().toLanguageTag()` on this handset returns the BCP-47
 * tag `"en-US"`, which is *not* in the accepted set. So every request would
 * 500 on a device in the US English locale — i.e. always, by default.
 *
 * Therefore: the only strings that may ever reach the wire are members of
 * [SUPPORTED]. That is an allowlist, not a sanitiser — a value that cannot be
 * mapped degrades to [DEFAULT] rather than being passed through in the hope
 * that the server copes.
 *
 * ## The degradation choice
 *
 * An unsupported language (say `hi-IN`) returns [DEFAULT] — English — rather
 * than omitting the field. Both are *safe*; the difference is only that one is
 * explicit on the wire. Transcribing Hindi speech as English is wrong, but it
 * is a wrong *transcript*, recoverable by the user, whereas passing `"hi"`
 * through is a 500 and no transcript at all. The server's own default for a
 * missing field is also `"en"`, so this is the same outcome stated out loud.
 */
object SttLanguage {

    /**
     * The service's accepted set, verbatim. Anything outside it is a 500, so
     * this is a hard boundary and not a hint.
     */
    val SUPPORTED: Set<String> = setOf(
        "ar", "de", "el", "en", "es", "fr", "it",
        "ja", "ko", "nl", "pl", "pt", "vi", "zh",
    )

    /** Also the server's own default when `language` is absent. */
    const val DEFAULT: String = "en"

    /**
     * Reduces an arbitrary locale tag to a member of [SUPPORTED].
     *
     * Accepts anything the platform might hand over: a BCP-47 tag
     * (`en-US`, `zh-Hans-CN`), an underscore-separated legacy locale
     * (`en_GB`), a bare code (`de`), odd casing (`PT-br`), and null/blank.
     *
     * @return always a member of [SUPPORTED]; never a region-qualified tag.
     */
    fun fromTag(tag: String?): String {
        val primary = primarySubtag(tag) ?: return DEFAULT
        return if (primary in SUPPORTED) primary else DEFAULT
    }

    /** Convenience for the real call site. */
    fun fromLocale(locale: Locale): String = fromTag(locale.toLanguageTag())

    /**
     * The first subtag, lower-cased, or null when there isn't one.
     *
     * Splits on both `-` and `_` because the two spellings arrive from
     * different platform APIs (`toLanguageTag()` vs `Locale.toString()`), and a
     * mapper that only understands one of them silently passes `en_GB`
     * through as a whole — which is a 500.
     */
    private fun primarySubtag(tag: String?): String? {
        if (tag.isNullOrBlank()) return null
        val first = tag.trim().split('-', '_').firstOrNull()?.lowercase()
        return if (first.isNullOrBlank()) null else first
    }
}

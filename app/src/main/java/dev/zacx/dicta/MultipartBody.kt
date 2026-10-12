package dev.zacx.dicta

import java.io.ByteArrayOutputStream

/**
 * Builds the `multipart/form-data` body for an upload.
 *
 * Hand-rolled because the APK ships no HTTP library (see
 * `app/build.gradle.kts` — the runtime dependency set is empty on purpose),
 * and because the field *names* are part of the service contract: the handler
 * reads `file`, `language` and `punctuation`, and a misspelling is a 422 at
 * best and a silently-defaulted parameter at worst.
 */
object MultipartBody {

    const val FIELD_FILE: String = "file"
    const val FIELD_LANGUAGE: String = "language"
    const val FIELD_PUNCTUATION: String = "punctuation"

    private const val CRLF = "\r\n"

    /** The `Content-Type` request header that must accompany [build]'s output. */
    fun contentType(boundary: String): String =
        "multipart/form-data; boundary=$boundary"

    /**
     * @param language MUST already be a bare ISO code from
     *   [SttLanguage.SUPPORTED]. Passing a BCP-47 tag here is an unhandled
     *   **500** on the server, so callers go through [SttLanguage.fromTag].
     *   Pass null to omit the field entirely and let the server default.
     * @param punctuation null omits the field and lets the server default
     *   (which is `true`).
     */
    fun build(
        boundary: String,
        fileName: String,
        fileContentType: String,
        audio: ByteArray,
        language: String?,
        punctuation: Boolean?,
    ): ByteArray {
        require(language == null || language in SttLanguage.SUPPORTED) {
            // A precondition rather than a silent fix: a caller that reaches
            // here with "en-US" has a bug upstream, and quietly rewriting it
            // would hide the bug while the next caller re-introduces it.
            "language must be a bare code from SttLanguage.SUPPORTED"
        }
        val out = ByteArrayOutputStream(audio.size + 512)

        language?.let { out.writeTextField(boundary, FIELD_LANGUAGE, it) }
        punctuation?.let {
            // Python's form parsing treats the strings "true"/"false"
            // case-insensitively; Kotlin's Boolean.toString() already emits
            // exactly those.
            out.writeTextField(boundary, FIELD_PUNCTUATION, it.toString())
        }

        out.ascii("--$boundary$CRLF")
        out.ascii(
            "Content-Disposition: form-data; name=\"$FIELD_FILE\"; " +
                "filename=\"$fileName\"$CRLF"
        )
        out.ascii("Content-Type: $fileContentType$CRLF$CRLF")
        out.write(audio)
        out.ascii(CRLF)
        out.ascii("--$boundary--$CRLF")

        return out.toByteArray()
    }

    /** A fresh boundary per request; the token must not occur in the payload. */
    fun newBoundary(nonce: String): String = "----DictaBoundary$nonce"

    private fun ByteArrayOutputStream.writeTextField(
        boundary: String,
        name: String,
        value: String,
    ) {
        ascii("--$boundary$CRLF")
        ascii("Content-Disposition: form-data; name=\"$name\"$CRLF$CRLF")
        ascii(value)
        ascii(CRLF)
    }

    private fun ByteArrayOutputStream.ascii(s: String) =
        write(s.toByteArray(Charsets.UTF_8))
}

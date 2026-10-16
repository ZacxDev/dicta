package dev.zacx.dicta

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/**
 * The actual HTTP calls, on `HttpURLConnection`.
 *
 * `HttpURLConnection` rather than OkHttp/Retrofit because the APK has no
 * runtime dependencies (see `app/build.gradle.kts`). The upload is a single
 * fixed-length `POST` of a few tens of kilobytes, which is comfortably inside
 * what the platform client does well.
 *
 * Everything policy-shaped is elsewhere: this class turns a request into an
 * [SttOutcome] and makes no decisions about what to do next.
 */
class SttHttp(
    private val baseUrl: String,
    private val token: String,
    /** Injected for the test; the production value opens a real connection. */
    private val opener: (String) -> HttpURLConnection = { url ->
        URL(url).openConnection() as HttpURLConnection
    },
) {

    /**
     * One upload.
     *
     * Never throws: a transport failure is an [SttOutcome.Network], because
     * the ladder discriminates on it and an exception would lose the
     * distinction between "no response" and "a response we didn't like".
     */
    fun transcribe(attempt: TranscriptionCoordinator.UploadAttempt): SttOutcome {
        val boundary = MultipartBody.newBoundary(
            // Not security-relevant; only has to not occur in the payload.
            java.lang.Long.toHexString(System.nanoTime())
        )
        val body = MultipartBody.build(
            boundary = boundary,
            fileName = AUDIO_FILE_NAME,
            fileContentType = AUDIO_CONTENT_TYPE,
            audio = attempt.audio,
            language = attempt.language,
            punctuation = true,
        )
        var conn: HttpURLConnection? = null
        return try {
            conn = opener(SttEndpoints.transcriptions(baseUrl)).apply {
                requestMethod = "POST"
                connectTimeout = SttRetryPolicy.CONNECT_TIMEOUT_MS
                readTimeout = SttRetryPolicy.readTimeoutMs(attempt.audioSeconds)
                doOutput = true
                // 🔴 Fixed-length rather than chunked: the server's form
                // parser is happier with a Content-Length, and a fixed length
                // also means the platform client will not silently retry the
                // request itself on a dropped connection — which would be an
                // upload the budget never authorised.
                setFixedLengthStreamingMode(body.size)
                setRequestProperty("Authorization", SttEndpoints.authorization(token))
                setRequestProperty("Content-Type", MultipartBody.contentType(boundary))
                setRequestProperty("Accept", "application/json")
            }
            conn.outputStream.use { it.write(body) }
            readOutcome(conn)
        } catch (e: IOException) {
            // 🔴 e.message only. The exception's toString() on some platform
            // clients includes the request URL, and while the URL carries no
            // token today, "the reason string is derived from something that
            // has never contained a secret" is a weaker invariant than "the
            // reason string is the message".
            SttOutcome.Network(e.message ?: e.javaClass.simpleName)
        } catch (e: RuntimeException) {
            SttOutcome.Network(e.message ?: e.javaClass.simpleName)
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * `GET /healthz`.
     *
     * 🔴 Sends **no** `Authorization` header: the endpoint is unauthenticated,
     * and it is the readiness probe used precisely when the token might be the
     * problem. Attaching one would make a token fault look like an outage.
     */
    fun healthz(): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = opener(SttEndpoints.healthz(baseUrl)).apply {
                requestMethod = "GET"
                connectTimeout = SttRetryPolicy.CONNECT_TIMEOUT_MS
                readTimeout = HEALTH_READ_TIMEOUT_MS
            }
            conn.responseCode == 200
        } catch (_: IOException) {
            false
        } catch (_: RuntimeException) {
            false
        } finally {
            conn?.disconnect()
        }
    }

    private fun readOutcome(conn: HttpURLConnection): SttOutcome {
        val status = conn.responseCode
        val bodyStream: InputStream? =
            if (status in 200..299) conn.inputStream else conn.errorStream
        val body = bodyStream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()

        if (status in 200..299) {
            val text = JsonLite.stringField(body, "text")
            // A 200 whose body has no "text" is not a success — treating it as
            // one would put an empty transcript on screen and report done.
            return if (text != null) {
                // 🔴 Three-valued, and null is NOT false: the server reports
                // null when it had no EOS id to look for, i.e. it could not
                // tell. Collapsing that to false would turn "unknown" into
                // "checked and fine".
                SttOutcome.Success(text, JsonLite.boolField(body, "truncated"))
            } else {
                SttOutcome.Http(status, "200 with no \"text\" field")
            }
        }
        return SttOutcome.Http(status, JsonLite.stringField(body, "detail"))
    }

    companion object {
        /**
         * The server identifies the container by content, via ffmpeg, so the
         * filename is cosmetic — but it must have *an* extension or some form
         * parsers drop the part.
         */
        const val AUDIO_FILE_NAME: String = "dictation.ogg"
        const val AUDIO_CONTENT_TYPE: String = "audio/ogg"

        private const val HEALTH_READ_TIMEOUT_MS = 5_000
    }
}

/**
 * The smallest JSON string-field reader that will do.
 *
 * A dependency-free APK means no Gson/Moshi, and `org.json` is on the
 * platform — but `org.json` is **not** on the JVM unit-test classpath without
 * Robolectric, and the parsing of `detail` is policy-relevant enough
 * ([SttRetryPolicy.PERMANENT_503_MARKER] is matched against it) that it
 * deserves plain unit tests. So: a tiny hand-rolled extractor, fully tested,
 * that only claims to read top-level string and boolean fields.
 *
 * 🔴 It returns null rather than throwing on anything it does not understand,
 * including a non-JSON body — which is the common case during a pod roll,
 * when the proxy answers with HTML. Null then flows into the classifier as
 * "503 with no parseable detail", which is correctly treated as transient.
 */
object JsonLite {

    /**
     * Extracts a top-level string field.
     *
     * Handles the escapes that actually occur in these responses (`\"`, `\\`,
     * `\n`, `\t`, `\r`, `\/`, `\uXXXX`). Does not attempt to be a JSON parser:
     * it finds `"name"`, expects a `:` and then a quoted string, and gives up
     * on anything else.
     */
    fun stringField(json: String, name: String): String? {
        val needle = "\"$name\""
        var from = 0
        while (true) {
            val keyAt = json.indexOf(needle, from)
            if (keyAt < 0) return null
            var i = keyAt + needle.length
            while (i < json.length && json[i].isWhitespace()) i++
            if (i >= json.length || json[i] != ':') {
                // "text" occurred somewhere that is not a key position.
                from = keyAt + needle.length
                continue
            }
            i++
            while (i < json.length && json[i].isWhitespace()) i++
            if (i >= json.length || json[i] != '"') {
                // A non-string value (null, a number, an object). Not ours.
                return null
            }
            return readQuoted(json, i + 1)
        }
    }

    /**
     * Extracts a top-level JSON **boolean** field.
     *
     * Three-valued on purpose, and the third value is load-bearing: `null`
     * means **no signal** — the field was absent, was JSON `null`, or was not
     * a boolean literal — and a caller must never read it as "checked, and
     * fine". `truncated: null` from the service means the server *could not
     * tell*, which is not the same claim as `truncated: false`.
     *
     * 🔴 Uses [stringField]'s key-position scan rather than a second approach:
     * a bare `indexOf` would be fooled by the name appearing in a *value*
     * (`{"text":"truncated","truncated":false}` reads as true-ish to a naive
     * matcher). A match whose next non-space character is not `:` is skipped
     * and the scan continues, exactly as above.
     *
     * Only a real `true` / `false` literal counts, and only when what follows
     * it is a JSON delimiter — `truest` is not `true`.
     */
    fun boolField(json: String, name: String): Boolean? {
        val needle = "\"$name\""
        var from = 0
        while (true) {
            val keyAt = json.indexOf(needle, from)
            if (keyAt < 0) return null
            var i = keyAt + needle.length
            while (i < json.length && json[i].isWhitespace()) i++
            if (i >= json.length || json[i] != ':') {
                // The name occurred somewhere that is not a key position.
                from = keyAt + needle.length
                continue
            }
            i++
            while (i < json.length && json[i].isWhitespace()) i++
            return when {
                isLiteralAt(json, i, "true") -> true
                isLiteralAt(json, i, "false") -> false
                // null, a number, a string, an object: no signal.
                else -> null
            }
        }
    }

    /**
     * True iff [literal] starts at [at] and is not merely the prefix of a
     * longer bare word, so `truest` and `falsey` are rejected rather than
     * read as the literal they begin with.
     */
    private fun isLiteralAt(json: String, at: Int, literal: String): Boolean {
        if (!json.startsWith(literal, at)) return false
        val next = at + literal.length
        if (next >= json.length) return true
        val c = json[next]
        return !(c.isLetterOrDigit() || c == '_' || c == '.' || c == '-' || c == '+')
    }

    /** @param start index of the first character *inside* the quotes. */
    private fun readQuoted(json: String, start: Int): String? {
        val sb = StringBuilder()
        var i = start
        while (i < json.length) {
            when (val c = json[i]) {
                '"' -> return sb.toString()
                '\\' -> {
                    i++
                    if (i >= json.length) return null
                    when (val esc = json[i]) {
                        '"', '\\', '/' -> sb.append(esc)
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        'b' -> sb.append('\b')
                        // Kotlin has no '\\f' escape; form feed by code point.
                        'f' -> sb.append('\u000C')
                        'u' -> {
                            if (i + 4 >= json.length) return null
                            val hex = json.substring(i + 1, i + 5)
                            val code = hex.toIntOrNull(16) ?: return null
                            sb.append(code.toChar())
                            i += 4
                        }
                        else -> return null
                    }
                }
                else -> sb.append(c)
            }
            i++
        }
        // Unterminated string: a truncated body, not a value.
        return null
    }
}

/**
 * 🔴 The one-in-flight guarantee, made observable.
 *
 * The service is a synchronous handler on a **single GPU replica with no
 * admission control**, so client-side serialisation is the only backpressure
 * in the system. A single-thread executor already serialises, but "the
 * executor has one thread" is a fact about the wiring that a later refactor
 * can change without anything going red. This wrapper makes the invariant
 * assertable: [maxObservedConcurrency] is read by a test that fires
 * overlapping submissions, and [concurrencyViolations] is non-zero if the
 * invariant was ever broken at runtime.
 */
class SingleFlightUploader(
    private val delegate: (TranscriptionCoordinator.UploadAttempt) -> SttOutcome,
) {
    private val inFlight = AtomicInteger(0)
    private val maxSeen = AtomicInteger(0)
    private val violations = AtomicInteger(0)
    private val lock = Any()

    val maxObservedConcurrency: Int get() = maxSeen.get()
    val concurrencyViolations: Int get() = violations.get()

    fun upload(attempt: TranscriptionCoordinator.UploadAttempt): SttOutcome =
        synchronized(lock) {
            val now = inFlight.incrementAndGet()
            maxSeen.updateAndGet { maxOf(it, now) }
            if (now > 1) violations.incrementAndGet()
            try {
                delegate(attempt)
            } finally {
                inFlight.decrementAndGet()
            }
        }
}

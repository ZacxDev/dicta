package dev.zacx.dicta

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Where the shared clipboard's rows come from, as a seam.
 *
 * ## 🔴 A sibling of [SnipSource], not a widening of it
 *
 * `SnipSource` is `fun interface { fun catalog(): SnipStore.Catalog? }` — **one
 * nullary getter**. Delete and dedup do not fit behind it, and widening it would
 * drag the snippet picker into a change it has no interest in. Two seams, two
 * consumers.
 *
 * ## 🔴 `null` means UNREADABLE and is never empty-on-error
 *
 * [rows] returns `null` when the store could not be read and an empty list only
 * when the store is genuinely empty. `SnipUsageStore.kt:48-52` records the
 * asymmetry this inherits as already decided: the mesh endpoint *"fails loud
 * with a 503 rather than serve `[]` … an empty result is indistinguishable from
 * a working feature with nothing in it"*. [FloatingPickerActivity] spends three
 * distinct empty-state strings on exactly this distinction, so collapsing the
 * two here would make two of them unreachable.
 */
interface SharedClipSource {

    /** Newest-first, in the server's order. `null` when it could not be read. */
    fun rows(): List<Clip>?

    /**
     * Deletes one clip, from any device, and reports whether it is gone.
     *
     * 🔴 **`true` for a 404 as well as for a 204, deliberately.** The caller's
     * question is "is this row gone", and a row another device already deleted
     * answers yes. Reporting failure would make the ordinary case — two devices
     * tidying the same clip — look like an outage.
     */
    fun delete(id: Long): Boolean
}

/**
 * What a `POST /v1/clips` did. Never an exception; see [ClipHttp].
 *
 * 🔴 Three outcomes rather than a `Boolean`, because the flush has three
 * genuinely different jobs to do and a boolean would merge the two that must
 * not be merged: a clip the server will never accept has to be DROPPED, and a
 * clip the server did not hear about has to be KEPT. Collapsing them either
 * loses a capture or queues one that fails forever.
 */
sealed interface ClipPost {

    /**
     * The store holds it.
     *
     * 🔴 **No `deduped` field, and it was DELETED rather than never written.**
     * It carried the server's own verdict — this exact body was already there
     * and its timestamp was bumped instead, measured on the live service as
     * **200 + `deduped:true`** against **201 + `deduped:false`** — and the
     * justification for carrying it was that it is "the only client-visible
     * evidence that the server's dedup ran". Nothing read it: the row leaves
     * the outbox either way, [ClipFlusher] counts both as `stored`, and no
     * surface spells the word. Deletion candidate D4 of this branch's audit
     * round 0, accepted. The dedup is still exercised and still asserted — by
     * `ClipHttpTest`, over the STATUS CODE, which is what this client actually
     * acts on.
     */
    data class Stored(val id: Long) : ClipPost

    /**
     * The server refused it and always will. **Drop the item.**
     *
     * Reachable: a 400 `empty_body`, measured. An item that cannot be accepted
     * must leave the queue or it blocks every clip behind it forever — the
     * flush stops at the first [Unreachable], so a permanently-refused head is
     * a dead queue.
     */
    data class Refused(val status: Int, val detail: String?) : ClipPost

    /**
     * No usable answer. **Keep the item.**
     *
     * Transport failures, 5xx, and 401 — a wrong or missing token is a
     * configuration fault the operator fixes, not a reason to destroy the
     * capture.
     */
    data class Unreachable(val reason: String) : ClipPost
}

/**
 * The clip store's HTTP calls, on `HttpURLConnection`.
 *
 * ## 🔴 A sibling of [SttHttp], not a reuse of it
 *
 * The shape is copied deliberately — injected `opener`,
 * [SttRetryPolicy.CONNECT_TIMEOUT_MS], `setFixedLengthStreamingMode`,
 * `disconnect()` in `finally`, **never throws** — because those are the things
 * that are easy to get wrong and are already right there. What `SttHttp` has
 * none of: a JSON `POST` body (`MultipartBody` is audio-shaped), a `GET` that
 * returns a body (its `healthz()` returns `Boolean`, **and is ledgered** by
 * `SttReachabilityLedgerTest`), a `DELETE`, and array parsing.
 *
 * 🔴 **Nothing here probes `/healthz`, and that is enforced rather than
 * intended.** `onlyTheDeclaredFilesPerformAHealthProbe` pins the zero-argument
 * `.healthz()` callers to exactly `BubbleService.kt`, `RemoteTranscriber.kt`
 * and `SettingsActivity.kt`; a probe added here turns it red. It would also be
 * useless: the clip store's `/healthz` is a **schema** probe, measured to
 * answer 200 with its database file unlinked, truncated, overwritten or its
 * directory removed. A 200 is not evidence the store has data.
 *
 * 🔴 **[SingleFlightUploader] is deliberately NOT used.** Its justification is
 * *"a synchronous handler on a single GPU replica with no admission control"*.
 * The clip store is a Go binary in front of SQLite answering in milliseconds;
 * importing that serialisation would be importing a constraint that does not
 * exist here.
 *
 * ## Never throws
 *
 * Every transport failure maps to an outcome. [SttHttp]'s rule on the reason
 * string is kept exactly: **`e.message` only, never `toString()`** — some
 * platform clients put the request URL in `toString()`, and "the reason string
 * is the message" is a stronger invariant than "the reason string is derived
 * from something that has never contained a secret".
 */
class ClipHttp(
    private val baseUrl: String,
    private val token: String,
    /** How many rows [rows] asks for. See [ClipEndpoints.clips]. */
    private val limit: Int = DEFAULT_LIMIT,
    /** Injected for the test; the production value opens a real connection. */
    private val opener: (String) -> HttpURLConnection = { url ->
        URL(url).openConnection() as HttpURLConnection
    },
) : SharedClipSource {

    /** One capture, appended or deduped. */
    fun post(body: String, device: String): ClipPost {
        val payload = ClipJson.postBody(body, device).toByteArray(Charsets.UTF_8)
        var conn: HttpURLConnection? = null
        return try {
            conn = opener(ClipEndpoints.clips(baseUrl)).apply {
                requestMethod = "POST"
                connectTimeout = SttRetryPolicy.CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                // 🔴 Fixed-length rather than chunked, for SttHttp's measured
                // reason: a fixed length also means the platform client will
                // not silently retry the request itself on a dropped
                // connection — which here would be a capture the dedup has to
                // clean up after.
                setFixedLengthStreamingMode(payload.size)
                setRequestProperty("Authorization", SttEndpoints.authorization(token))
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }
            conn.outputStream.use { it.write(payload) }
            readPost(conn)
        } catch (e: IOException) {
            ClipPost.Unreachable(e.message ?: e.javaClass.simpleName)
        } catch (e: RuntimeException) {
            ClipPost.Unreachable(e.message ?: e.javaClass.simpleName)
        } finally {
            conn?.disconnect()
        }
    }

    override fun rows(): List<Clip>? {
        var conn: HttpURLConnection? = null
        return try {
            conn = opener(ClipEndpoints.clips(baseUrl, limit)).apply {
                requestMethod = "GET"
                connectTimeout = SttRetryPolicy.CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Authorization", SttEndpoints.authorization(token))
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode != 200) return null
            ClipJson.parseList(bodyOf(conn, ok = true))
        } catch (_: ClipJson.Malformed) {
            // 🔴 First, and it must be caught at all: `Malformed` extends
            // `Exception`, not `RuntimeException`, so the two clauses below
            // would let it escape — and this method's whole contract is that
            // it never throws. A malformed body reads as "unreadable", never
            // as "empty"; see [SharedClipSource.rows].
            null
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    override fun delete(id: Long): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = opener(ClipEndpoints.clip(baseUrl, id)).apply {
                requestMethod = "DELETE"
                connectTimeout = SttRetryPolicy.CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Authorization", SttEndpoints.authorization(token))
            }
            // 🔴 204 OR 404 — see [SharedClipSource.delete]. Measured on the
            // live service: a second DELETE of the same id answers 404 with
            // `error: no_such_clip`.
            conn.responseCode == 204 || conn.responseCode == 404
        } catch (_: IOException) {
            false
        } catch (_: RuntimeException) {
            false
        } finally {
            conn?.disconnect()
        }
    }

    private fun readPost(conn: HttpURLConnection): ClipPost {
        val status = conn.responseCode
        val body = bodyOf(conn, ok = status in 200..299)
        if (status in 200..299) {
            val id = ClipJson.parseIdOrZero(body)
            if (id <= 0L) {
                // A 2xx whose body carries no id is not a success we can act
                // on: the item would leave the queue with nothing proving the
                // store took it. SttHttp makes the same call about a 200 with
                // no "text".
                return ClipPost.Unreachable("$status with no \"${ClipJson.KEY_ID}\"")
            }
            // 🔴 The id is the whole of the outcome. The server's `deduped`
            // field WAS read here and folded into the Stored outcome; nothing
            // consumed it, so both the field and `ClipJson.deduped` are gone
            // (D4). A 200-deduped and a 201-new are the same thing to this
            // client: the store holds the body, so the local row must go.
            return ClipPost.Stored(id)
        }
        return if (status in PERMANENT_STATUSES) {
            ClipPost.Refused(status, ClipJson.detail(body))
        } else {
            ClipPost.Unreachable("HTTP $status")
        }
    }

    private fun bodyOf(conn: HttpURLConnection, ok: Boolean): String {
        val stream: InputStream? = if (ok) conn.inputStream else conn.errorStream
        return stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
    }

    companion object {

        /**
         * How many rows the picker loads.
         *
         * 🔴 `limit` is the only growth bound in the system: settled decision
         * 7 is **no expiry, manual delete only**, so the store is unbounded.
         * 100 is the service's own default (measured: a `GET` with no `limit`
         * reports `"limit":100`), so this agrees with the server rather than
         * inventing a second number.
         */
        const val DEFAULT_LIMIT: Int = 100

        /**
         * Statuses that mean "this request is wrong and always will be", so the
         * item must be DROPPED rather than retried forever.
         *
         * 🔴 **401 is deliberately NOT in here**, and nor is 403: a missing or
         * stale token is a configuration fault the operator fixes in Settings,
         * and dropping the queue over it would destroy captures for a
         * five-second repair. 429 and 408 are absent for the obvious reason —
         * they are the two statuses that explicitly mean "try again".
         */
        val PERMANENT_STATUSES: Set<Int> = setOf(400, 404, 405, 413, 414, 415, 422)

        /**
         * 🔴 Not [SttRetryPolicy.readTimeoutMs]. That function scales the wait
         * with the number of audio seconds being transcribed on a GPU; a clip
         * POST is a few hundred bytes into SQLite. A short fixed read timeout
         * is what keeps the flush off the main thread's back and keeps a
         * half-open mesh tunnel from holding the worker for a minute.
         */
        private const val READ_TIMEOUT_MS = 8_000
    }
}

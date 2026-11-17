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
     * The same read as [rows], **with the failure classified** — see [ClipFetch].
     *
     * 🔴 This is the member a SURFACE should call. [rows] collapsed a refused
     * token, a body that did not parse and a dead socket into one `null`, and
     * [ClipPickerActivity] rendered all three as *"usually being off-mesh"*.
     * That is the defect [ClipFetch] exists to close, and it is closed here
     * rather than in the picker so that both consumers — the empty-state copy
     * and [ClipDiagnostics] — read **one** classification.
     *
     * 🔴 The default is deliberately LOSSY and says so: a source that
     * overrides only [rows] cannot distinguish anything, so it reports
     * [ClipFetch.Unclassified] rather than fabricating a transport failure it
     * never observed. [ClipHttp] overrides this, and `src/main` holds no other
     * implementation — the default serves the test fakes, which exist to
     * supply rows and have no failure to classify.
     */
    fun fetch(): ClipFetch =
        rows()?.let { ClipFetch.Rows(it) } ?: ClipFetch.Unclassified

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
     * The server refused it and always will. **Drop the item — which DESTROYS
     * the user's only copy of it.**
     *
     * 🔴 Reaching this is a capture-loss event and is reported as one. See
     * [ClipFlusher.Report.destroyed] and [ClipOutboxHealth]: a drop raises a
     * fault in its own right rather than resetting the stuck counter, because
     * a destroyed capture is strictly worse than a stuck queue and until this
     * branch it had **less** visibility than nothing.
     *
     * An item that cannot be accepted must leave the queue or it blocks every
     * clip behind it forever — the flush stops at the first [Unreachable], so
     * a permanently-refused head is a dead queue. That is why the membership of
     * [ClipHttp.PERMANENT_STATUSES] is now only the two statuses that are a
     * property of the BYTES.
     */
    data class Refused(val status: Int, val detail: String?) : ClipPost

    /**
     * No usable answer. **Keep the item.**
     *
     * Transport failures, 5xx, 401/403 — and, since this branch, every status
     * that is a property of the URL CONFIGURATION rather than of the bytes
     * (404, 405, 414, 415) plus 413. A configuration fault is one the operator
     * fixes in Settings; destroying the capture for it is a five-second repair
     * paid for with the user's data.
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

    /**
     * 🔴 **Derived from [fetch], which is the ONE place the read is
     * classified.** Kept so every existing caller and every existing test is
     * unchanged — and those tests are what prove the classification did not
     * alter the behaviour this method already had: a non-200, a malformed
     * body and a dead socket are all still `null` here, and an empty store is
     * still an empty list.
     *
     * A surface must call [fetch] instead. This getter is the lossy view, and
     * the loss is the defect — see [ClipFetch].
     */
    override fun rows(): List<Clip>? = (fetch() as? ClipFetch.Rows)?.rows

    /**
     * The read, with each failure class carried out of here intact.
     *
     * 🔴 **Never throws**, exactly as before: the four `catch` clauses below
     * are the same four, mapping to the four outcomes they used to flatten
     * into `null`. `Malformed` is caught FIRST and it must be caught at all —
     * it extends `Exception`, not `RuntimeException`, so the clauses below
     * would let it escape.
     */
    override fun fetch(): ClipFetch {
        var conn: HttpURLConnection? = null
        return try {
            conn = opener(ClipEndpoints.clips(baseUrl, limit)).apply {
                requestMethod = "GET"
                connectTimeout = SttRetryPolicy.CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Authorization", SttEndpoints.authorization(token))
                setRequestProperty("Accept", "application/json")
            }
            val status = conn.responseCode
            // 🔴 The status is CARRIED. This `return null` is where a 401
            // became "you are off-mesh" for both the operator and an agent.
            if (status != 200) return ClipFetch.Status(status)
            ClipFetch.Rows(ClipJson.parseList(bodyOf(conn, ok = true)))
        } catch (_: ClipJson.Malformed) {
            ClipFetch.Malformed
        } catch (e: IOException) {
            // `e.message` only, never `toString()` — the class kdoc's rule.
            ClipFetch.Transport(e.message ?: e.javaClass.simpleName)
        } catch (e: RuntimeException) {
            ClipFetch.Transport(e.message ?: e.javaClass.simpleName)
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
         * Statuses that mean **these BYTES are wrong and always will be**, so
         * the item must be DROPPED rather than retried forever.
         *
         * 🔴 **The membership test is "is it a property of the BODY", not "is
         * it a 4xx".** Dropping is how a capture is destroyed — the outbox is
         * the only copy — so a status earns a place here only if correcting
         * the app's own configuration could not possibly make the same bytes
         * succeed.
         *
         * 🔴 **CAPTURE-LOSS DEFECT, FIXED HERE (was live on `main` at
         * `d3efcd0`).** This set read
         * `setOf(400, 404, 405, 413, 414, 415, 422)` and its justification —
         * `ClipHttpTest.theOtherPermanentRefusalsAreTheOnesARETRYCannotFix` —
         * called *"a route that does not exist"* a property of the request.
         * It is not: it is a property of the **URL in Settings**, which is
         * free text saved with no validation. The operator pasting the
         * transcription base URL (`…:8118`, four digits away) into the clip
         * field makes every `POST /v1/clips` a **404**, and every queued
         * capture was then posted, refused and deleted from the only place it
         * existed, one flush per minute, while the capture toast said *"it
         * will sync when Dicta is on the mesh"*. That is word for word the
         * reasoning [aMissingTokenIsUNREACHABLEAndNOTRefused] already used to
         * keep 401/403 out, applied to the statuses it was not applied to.
         *
         * What is in, and why each is a BODY fault:
         *
         *  * **400** — measured live as `empty_body`, i.e. the bytes. The one
         *    reachable member the design ever reasoned about, and
         *    [ClipOutboxStore.append] already refuses to queue that body, so
         *    this is belt-and-braces against a server-side body rule this
         *    client does not know.
         *  * **422** — the body parsed and was semantically rejected. Nothing
         *    in Settings changes what the body says.
         *
         * What moved OUT, and why each is a CONFIGURATION fault:
         *
         *  * **404** — the route does not exist *at the base URL that is
         *    configured*. The identical bytes succeed the moment the field is
         *    corrected.
         *  * **405** — the path exists but does not serve `POST`. Same cause:
         *    the base URL points at something that is not the clip store.
         *  * **415** — this client always sends `Content-Type: application/json`
         *    (see [post]), so a 415 cannot be produced by the bytes. It means
         *    something that is not the clip store — a proxy, a gateway, the
         *    transcription service — is answering.
         *  * **414** — URI too long. The request URI is
         *    `baseUrl + /v1/clips`; **the body is never in it**. A 414 is
         *    therefore only reachable through an absurd base URL, which is
         *    configuration by construction. [ClipEndpoints.rejectionFor] now
         *    bounds that field, so it should not be reachable at all.
         *  * **413** — payload too large. `ACTION_PROCESS_TEXT` delivers up to
         *    `PARCEL_SAFE_TEXT_LENGTH` = 100,000 characters (see
         *    [CaptureActivity]), so a legitimate large selection can hit a
         *    server-side limit the operator may raise. Destroying the text for
         *    it is the worst available outcome, and the server's limit is not
         *    a property of the bytes — it is a property of the deployment.
         *    The *capture-time* half of this is
         *    [ClipOutboxStore.MAX_BODY_CHARS], which refuses an oversized body
         *    while the operator can still see the selection it came from.
         *
         * 🔴 **401 and 403 remain deliberately absent**, as before: a missing
         * or stale token is a configuration fault the operator fixes in
         * Settings. 429 and 408 are absent for the obvious reason — they are
         * the two statuses that explicitly mean "try again".
         */
        val PERMANENT_STATUSES: Set<Int> = setOf(400, 422)

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

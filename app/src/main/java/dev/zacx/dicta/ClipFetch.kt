package dev.zacx.dicta

/**
 * What a `GET /v1/clips` did — **four outcomes, not one `null`**.
 *
 * ## 🔴 The defect this type exists to close
 *
 * [SharedClipSource.rows] returns `List<Clip>?`, and `ClipHttp` produced that
 * `null` from **four structurally different failures**: a non-200 status (401,
 * 403, 404, 500 — any of them), a [ClipJson.Malformed] body, an `IOException`,
 * and a `RuntimeException`. [ClipPickerActivity] then rendered every one of
 * them as `@string/clip_empty_unreachable` — *"Dicta could not reach the shared
 * clipboard. It is mesh-only, so this is usually being off-mesh"*.
 *
 * So a **401 read as "you are off-mesh"**. The store was reachable, answering,
 * and refusing the token, and the only surface the operator or an agent can
 * consult said the opposite — sending both off to debug the mesh. That is the
 * [Failure Investigation] shape the repository already has a rule about: an
 * empty result cannot distinguish two mechanisms, so it identifies none of
 * them.
 *
 * This is a **product** fix before it is a diagnostic one. The distinction is
 * carried all the way to the words on the screen — see
 * [ClipPickerActivity.emptyUnreadableText] — and to the machine-readable
 * readout, [ClipDiagnostics].
 *
 * ## 🔴 Why a sealed interface rather than a nullable status code
 *
 * [ClipPost]'s argument, applied to the read half: a `Int?` would merge the two
 * cases that must not merge. "The store answered 401" and "the socket never
 * opened" are both "no rows", and they have **opposite** remedies — fix the
 * token in Settings, versus get back on the mesh. A caller holding an `Int?`
 * has to invent a sentinel for the transport case, which is how the collapse
 * gets reintroduced one field down.
 *
 * ## 🔴 `reason` is machine-stable and carries NO secret
 *
 * [reason] is read by an agent and asserted by tests, so its shape is part of
 * the contract: a lowercase tag, optionally `:` and one detail. The detail is
 * a status code, or an exception's **`message` only** — never `toString()`,
 * which is [ClipHttp]'s own measured rule (*"some platform clients put the
 * request URL in `toString()`"*). No token reaches any of these strings: the
 * token travels in an `Authorization` header and is never in a URL, a status
 * code or a parse error.
 */
sealed interface ClipFetch {

    /**
     * A short, stable, secret-free tag for the readout and for tests.
     *
     * 🔴 Pinned as whole normalised strings by `ClipFetchTest` rather than
     * matched on a word: a guard on a WORD is walkable by rewording, and the
     * whole point of this type is that two outcomes can never again be spelled
     * the same way.
     */
    val reason: String

    /** The store answered, and here is what it holds. An empty list is real. */
    data class Rows(val rows: List<Clip>) : ClipFetch {
        override val reason: String get() = "rows:${rows.size}"
    }

    /**
     * The store answered with a status that is not 200.
     *
     * 🔴 The status is CARRIED, not folded into a boolean. A 401 is a token
     * fault, a 404 is a base-URL fault and a 503 is the service being down;
     * all three were `null` before this type, and all three have different
     * repairs.
     */
    data class Status(val status: Int) : ClipFetch {
        override val reason: String get() = "http:$status"

        /**
         * Whether this status means **the token was refused**, as opposed to
         * anything else the store might say.
         *
         * One place spells the membership, because the message on screen and
         * the readout must not be able to disagree about which statuses are
         * an auth fault.
         */
        val isAuthFault: Boolean get() = status == 401 || status == 403
    }

    /**
     * The store answered 200 and the body did not parse.
     *
     * 🔴 A distinct outcome from [Status] and from [Transport], because it is
     * the one that says *something that is not the clip store is answering at
     * this URL* — the single most likely configuration mistake there is, and
     * the one [ClipEndpoints.rejectionFor] states plainly that it cannot
     * catch (two valid mesh addresses are syntactically indistinguishable).
     */
    data object Malformed : ClipFetch {
        override val reason: String get() = "malformed"
    }

    /**
     * No answer at all: the socket failed, timed out, or the host did not
     * resolve. **This, and only this, is "you are probably off-mesh".**
     *
     * @param detail the exception's `message` only — see the class kdoc.
     */
    data class Transport(val detail: String) : ClipFetch {
        override val reason: String get() = "io:$detail"
    }

    /**
     * A [SharedClipSource] that does not classify its failures.
     *
     * 🔴 Reachable only through [SharedClipSource.fetch]'s default
     * implementation, i.e. a source that overrides `rows()` and nothing else —
     * which in this tree is the test fakes and nothing in `src/main`. It exists
     * so that default can be **honest about being lossy** instead of inventing
     * a transport failure it did not observe; a readout saying `unclassified`
     * is a fact about the source, and a readout saying `io:` would be a
     * fabricated measurement.
     */
    data object Unclassified : ClipFetch {
        override val reason: String get() = "unclassified"
    }
}

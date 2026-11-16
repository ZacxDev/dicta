package dev.zacx.dicta

import org.json.JSONArray
import org.json.JSONObject

/**
 * The clip store's wire format, read and written in one place.
 *
 * ## 🔴 `org.json`, not [JsonLite] — and NOT for the reason the design said
 *
 * The shared-clipboard design states that *"`JsonLite` cannot read the clip
 * list at all — `topLevelValueStart` returns `-1` for anything that is not an
 * outermost `{`, and a clip list is an array"*. The conclusion is right and
 * **the premise is wrong**: measured against the live service on 2026-11-15,
 * the list response is an outermost OBJECT —
 * `{"clips":[…],"limit":20}` — not a bare array. So `topLevelValueStart` would
 * find `clips` perfectly well.
 *
 * It still cannot be used, for the reason that actually applies: [JsonLite]
 * reads **string and boolean fields of the outermost object** and nothing else.
 * It has no array reader, no object reader and no number reader, and the value
 * under `clips` is an array of objects of numbers and strings. Growing one
 * would be writing a JSON parser, which is the thing [JsonLite]'s own kdoc
 * declines to be.
 *
 * 🔴 **The cost, and it is a real one:** `org.json` is a platform class with no
 * JVM implementation, so **every test that touches this object needs
 * `@RunWith(RobolectricTestRunner::class)`** or every call is a `Stub!` throw.
 * Without the runner a write silently no-ops and a read returns nothing, which
 * is indistinguishable from a parse bug — that cost a debugging round on the
 * MRU work, where every store test failed with
 * `expected:<{ssh-prod=4242}> but was:<{}>` and read as a prune defect.
 *
 * ## Failure posture: refuse loudly, never return empty
 *
 * [parseList] throws [Malformed] rather than returning an empty list, and the
 * reason is the asymmetry `SnipUsageStore.kt:48-52` already records as decided:
 * the clip endpoint *"fails loud with a 503 rather than serve `[]` … an empty
 * result is indistinguishable from a working feature with nothing in it"*. A
 * parse that degraded to `[]` would reintroduce exactly that, one layer down —
 * the picker would render "no clips yet" for a store full of them.
 *
 * Unknown keys are tolerated, for [SnipCatalog]'s reason: a clipstore release
 * that adds a field must not brick the phone.
 */
object ClipJson {

    /** A response that is not a usable clip list, with the reason to log. */
    class Malformed(reason: String) : Exception(reason)

    const val KEY_CLIPS: String = "clips"
    const val KEY_ID: String = "id"
    const val KEY_BODY: String = "body"
    const val KEY_DEVICE: String = "device"
    const val KEY_CREATED_AT: String = "created_at"

    /*
     * 🔴 DELETED: `KEY_BUMP_COUNT` ("bump_count") and `KEY_DEDUPED`
     * ("deduped"), with [Clip.bumpCount], `ClipPost.Stored.deduped` and the
     * `deduped(text)` reader. Each was parsed and carried and **read by
     * nothing** — deletion candidate D4 of this branch's audit round 0.
     *
     * The server still sends both and that is fine: unknown keys are tolerated
     * here by design ([SnipCatalog]'s rule — a clipstore release that adds a
     * field must not brick the phone), and `parseList` simply does not look.
     * Re-adding either means having a surface that renders it first.
     */

    const val KEY_ERROR: String = "error"
    const val KEY_DETAIL: String = "detail"

    /**
     * `GET /v1/clips`'s rows, in the order the server supplied them.
     *
     * 🔴 **Order is preserved verbatim and nothing is sorted here.** The
     * requirement is chronological newest-first and the server is what decides
     * that; a client-side sort would be a second definition of "newest" that
     * could disagree with the one the desktop sees. See
     * `ClipPickerActivity.filterRows`, which applies no reordering for the same
     * reason.
     *
     * An empty `clips` array is **valid** and yields no rows: an operator who
     * has deleted everything is not an error, and the picker's
     * "nothing stored yet" copy covers it. That is a different state from a
     * failed read, which is `null` from [SharedClipSource.rows].
     *
     * @throws Malformed when the envelope is not an object, has no `clips`
     *   array, or holds an element that is not an object. A row missing
     *   `body`/`device` defaults them to empty rather than failing — that is
     *   [SnipCatalog]'s rule — but a row with no usable `id` IS fatal, because
     *   the id is what [SharedClipSource.delete] acts on and a 0 would address
     *   a row that does not exist.
     */
    fun parseList(text: String): List<Clip> {
        val root = try {
            JSONObject(text)
        } catch (_: Exception) {
            throw Malformed("the response is not a JSON object")
        }
        val array = root.optJSONArray(KEY_CLIPS)
            ?: throw Malformed("the response has no \"$KEY_CLIPS\" array")
        val out = ArrayList<Clip>(array.length())
        for (i in 0 until array.length()) {
            val row = array.optJSONObject(i)
                ?: throw Malformed("clip ${i + 1} is not an object")
            val id = row.optLong(KEY_ID, 0L)
            if (id <= 0L) throw Malformed("clip ${i + 1} has no usable \"$KEY_ID\"")
            out += Clip(
                id = id,
                body = row.optString(KEY_BODY, ""),
                device = row.optString(KEY_DEVICE, ""),
                createdAtMs = row.optLong(KEY_CREATED_AT, 0L),
            )
        }
        return out
    }

    /**
     * The `POST /v1/clips` request body.
     *
     * 🔴 Built with `JSONObject`, never string concatenation. A clipboard body
     * is arbitrary text: it contains quotes, backslashes and newlines as a
     * matter of routine, and a hand-built body would produce a 400 for exactly
     * the clips a user most wants to move between machines.
     */
    fun postBody(body: String, device: String): String =
        JSONObject().put(KEY_BODY, body).put(KEY_DEVICE, device).toString()

    /**
     * The `id` a `POST` response reports, or `0` when it reports none.
     *
     * 🔴 Zero rather than null, and the caller treats zero as a failure. A
     * server id is a positive row id by construction, so there is no third
     * state to carry — and the one thing that must not happen is an item
     * leaving the outbox on the strength of a 2xx whose body said nothing.
     */
    fun parseIdOrZero(text: String): Long {
        val root = try {
            JSONObject(text)
        } catch (_: Exception) {
            return 0L
        }
        return root.optLong(KEY_ID, 0L)
    }

    /**
     * The service's own reason string from an error body, or null.
     *
     * 🔴 The service's `detail`, never an exception's `toString()`. `SttHttp`
     * states the rule it is reused from: *"the reason string is the message"* is
     * a stronger invariant than "the reason string is derived from something
     * that has never contained a secret", and the URL carries a port the
     * repository is public about but a token it must never log.
     */
    fun detail(text: String): String? {
        val root = try {
            JSONObject(text)
        } catch (_: Exception) {
            return null
        }
        val detail = root.optString(KEY_DETAIL, "")
        if (detail.isNotEmpty()) return detail
        val error = root.optString(KEY_ERROR, "")
        return error.takeIf { it.isNotEmpty() }
    }

    /**
     * The local outbox envelope's `items` array. See [ClipOutboxStore].
     *
     * 🔴 `id` and `body`, and nothing else. A `created_at` carrying
     * `ClipOutbox.Pending.capturedAtMs` was written here and read back by
     * nothing (D5); the reader only ever looked at these two keys, so dropping
     * the write is compatible in both directions — an old file still parses,
     * and a new file is missing only a key no one asked for.
     */
    internal fun toJsonArray(items: List<ClipOutbox.Pending>): JSONArray {
        val array = JSONArray()
        for (item in items) {
            array.put(
                JSONObject()
                    .put(KEY_ID, item.id)
                    .put(KEY_BODY, item.body)
            )
        }
        return array
    }
}

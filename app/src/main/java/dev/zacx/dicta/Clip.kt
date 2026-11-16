package dev.zacx.dicta

/**
 * One row of the shared clipboard.
 *
 * ## 🔴 There are no labels and no categories, and that is settled
 *
 * Operator decision 1 of the shared-clipboard design: this is a **cross-device
 * clipboard**, not a snippet store. Rows are chronological newest-first, carry
 * no label, and are searched over their bodies. That is why [Snippet] is not
 * reused for them despite both being "a body with some metadata" — a `Snippet`'s
 * `label` is its *identity* (`SnipCatalog` refuses a catalog whose labels are
 * not unique), and a clipboard row has no identity but the one the server
 * assigns.
 *
 * @param id the server's own row id. The key for [SharedClipSource.delete], and
 *   never generated here: the client cannot know what the store will call a row
 *   it has not accepted yet, which is why the local outbox has an id space of
 *   its own ([ClipOutbox.Pending]).
 * @param body the text, byte-exact as captured. Paste carries this (settled
 *   decision 6), so the picker never does a second lookup and there is no
 *   stale-paste race.
 * @param device which device captured it — the attribution requirement of
 *   decision 1. Rendered, never matched on, exactly as [Snippet.category] is.
 * @param createdAtMs epoch millis, **server-assigned**. 🔴 This is deliberately
 *   not the capture time: a clip captured off-mesh and flushed an hour later
 *   gets the flush's timestamp, because the ordering the operator sees must be
 *   the store's own ordering and a client clock is not one the other devices
 *   can trust. The cost — a queued clip appears newer than it is — is stated
 *   here rather than discovered.
 *
 * ## 🔴 There is deliberately no `bumpCount`, and it was DELETED rather than
 * never written
 *
 * The server's dedup is `UNIQUE(body_sha256)` plus an `ON CONFLICT DO UPDATE`
 * that refreshes `created_at` and increments a `bump_count`, so re-copying an
 * old clip resurfaces it rather than duplicating it (measured on the live
 * service: an identical body answers **200** with `deduped:true` where a new
 * one answers **201**). That field was parsed into this class and carried
 * through the picker, and **nothing rendered it and nothing branched on it** —
 * deletion candidate D4 of this branch's audit round 0, accepted.
 *
 * It is stated here because the next reader will reach for it: the *resurfacing*
 * behaviour is real and visible — a re-copied clip moves to the top of the list
 * — it is simply carried by [createdAtMs], which the server bumps. A count of
 * how many times is a thing no surface has asked for, and a parsed-but-unread
 * field reads as a fact the client checks. `ClipJson` tolerates unknown keys
 * (`SnipCatalog`'s rule), so the server may keep sending it.
 */
data class Clip(
    val id: Long,
    val body: String,
    val device: String,
    val createdAtMs: Long,
)

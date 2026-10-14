package dev.zacx.dicta

import java.util.concurrent.atomic.AtomicReference

/**
 * 🔴 Process-wide **microphone ownership**: at most one [DictationSession] may
 * hold the mic, and a second one is refused rather than silently ruined.
 *
 * ## Why an enforced holder and not another flag
 *
 * One `DictationSession` is one `AudioRecord`. Two means Android's
 * concurrent-capture policy decides which one wins, and it enforces that by
 * **feeding the loser bit-exact zeros** — `startRecording()` succeeds, `read()`
 * returns the full byte count, every byte is 0. There is no exception and no
 * error callback, so the symptom that reaches the user is "the transcript came
 * back empty", or invented prose from the ASR service, and it reads as a server
 * bug. 🔴 A green unit suite cannot see any of this: nothing below
 * `AudioRecord` exists under Robolectric.
 *
 * [DictateLaunch.EXTRA_ATTACH_SESSION] suppresses the second session only on
 * the intent **the bubble itself sends**. The QS tile
 * ([DictaTileService]), the notification action and `MainActivity`'s "Try
 * dictation now" all go through [DictateLaunch.intent], which carries no extra
 * — so while the bubble is recording with nothing on screen, any of them opens
 * a card whose `onCreate` would start a second capture. Per-call-site flags are
 * the shape of defect that regenerates at every new surface; a holder that
 * `start()` must acquire fails for surfaces that do not exist yet too.
 *
 * ## Shape
 *
 * A register/unregister singleton, following [OverlayVisibility] — which exists
 * for the same cross-component problem in the same process. Unit-testable, and
 * it cannot leak a destroyed component because the token is released by
 * `DictationSession.stop()`/`destroy()` and the holder keeps no other state.
 *
 * ## What ownership means
 *
 * **The microphone is open.** Acquired in [DictationSession.start] before any
 * platform call, released as soon as capture ends — `stop()`, a capture error,
 * or `destroy()`. It is deliberately *not* held for the upload: the bytes are
 * already on disk by then and the next recording must not be blocked by a POST
 * that is still retrying.
 */
object MicrophoneOwner {

    /** The current owner's token, or null when the microphone is free. */
    private val holder = AtomicReference<Any?>(null)

    /**
     * Takes ownership for [owner].
     *
     * @return true when [owner] now holds the microphone (including when it
     *   already did — acquire is re-entrant for the same token, because
     *   `start()` on an already-recording session is a no-op that must still
     *   report success). False when **somebody else** holds it, which the
     *   caller must turn into a reportable failure rather than a recording.
     */
    fun acquire(owner: Any): Boolean =
        holder.compareAndSet(null, owner) || holder.get() === owner

    /**
     * Releases ownership held by [owner]. A no-op when [owner] is not the
     * current holder, so a late teardown cannot steal the microphone from the
     * recording that replaced it.
     */
    fun release(owner: Any) {
        holder.compareAndSet(owner, null)
    }

    /** True while any session holds the microphone. */
    val isHeld: Boolean get() = holder.get() != null

    /** True when [owner] specifically holds it. */
    fun isHeldBy(owner: Any): Boolean = holder.get() === owner

    /**
     * True when the microphone is held by **somebody other than** [candidate]
     * (and false when it is free, or when [candidate] is the holder).
     *
     * The predicate callers that are about to open a microphone want: "is
     * starting a capture now going to collide". Expressed once here rather
     * than open-coded as `isHeld && !isHeldBy(x)` at each site, because a
     * predicate spelled at N sites is typically wrong at N−1 of them.
     */
    fun isHeldByOther(candidate: Any?): Boolean {
        val current = holder.get() ?: return false
        return current !== candidate
    }

    /** Test seam only. */
    fun clearForTest() {
        holder.set(null)
    }
}

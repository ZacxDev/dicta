package dev.zacx.dicta

import java.util.concurrent.atomic.AtomicReference

/**
 * 🔴 Process-wide **microphone ownership**: at most one capture in this process
 * may hold the mic, and a second one is refused rather than silently ruined.
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
 * the intent the bubble sends for a capture it has **already finished**
 * ([DictateLaunch.transcribingIntent]). A *plain* [DictateLaunch.intent]
 * carries no such extra, so the card it opens runs its own `startDictation()`
 * in `onCreate` — which is why a per-intent flag cannot be the whole answer.
 *
 * 🔴 **Do not read any of this as a count of surfaces.** This paragraph has
 * been an enumeration twice and gone stale twice: first "the bubble, the tile
 * and the notification", then "the notification and *Try dictation now* — two,
 * not three". Every one of those callers is now gone. The tile is the bubble's
 * on/off switch and references [DictateLaunch] nowhere; the trigger
 * notification and `MainActivity`'s "Try dictation now" are deleted, and
 * `DictateLaunch.pendingIntent` went with them. A plain [DictateLaunch.intent]
 * has exactly **one** production caller today — `BubbleService`'s
 * mic-permission hop — and `StartSurfaceLedgerTest` is the enumerated set that
 * fails if that grows.
 *
 * 🔴 **The holder is not justified by that count and must not be judged on
 * it.** Two openers in this process can be live at once whatever launched
 * either — the card's own recogniser or upload session, and the bubble's
 * session — because the bubble stays tappable while the card is up.
 * Per-call-site flags are the shape of defect that regenerates at every new
 * surface; a holder that `start()` must acquire fails for surfaces that do not
 * exist yet too. That last clause is the durable half of the argument, and it
 * is why shedding callers never made the holder redundant.
 *
 * ## Shape
 *
 * A register/unregister singleton, following [OverlayVisibility] — which exists
 * for the same cross-component problem in the same process. Unit-testable, and
 * it cannot leak a destroyed component because every opener releases on the way
 * down and the holder keeps no other state.
 *
 * ## What ownership means
 *
 * **The microphone is open**, by whichever client opened it. There are two, and
 * both acquire:
 *
 *  * [DictationSession] — one `AudioRecord`. Acquired in
 *    [DictationSession.start] before any platform call, released as soon as
 *    capture ends (`stop()`, a capture error, or `destroy()`). It is
 *    deliberately *not* held for the upload: the bytes are already on disk by
 *    then and the next recording must not be blocked by a POST that is still
 *    retrying.
 *  * `DictateActivity`'s phone-only path — a bare [SpeechHelper], where
 *    **`SpeechRecognizer` opens the microphone itself**. It is the degradation
 *    path (no `AndroidKeyStore`, or capture refused), so it is reached by the
 *    *error* route, which is exactly how an opener that does not register ends
 *    up competing with one that does. Acquired around `helper.start()` with the
 *    activity as the token, released when the recogniser stops, finishes,
 *    errors or is destroyed.
 *
 * 🔴 So "`isHeld` is false" means *no client in this process has the mic*, not
 * merely "no `DictationSession` has it". That distinction is the whole reason
 * this object is not a field on `DictationSession`.
 */
object MicrophoneOwner {

    /** The current owner's token, or null when the microphone is free. */
    private val holder = AtomicReference<Any?>(null)

    /**
     * Takes ownership for [owner]: a plain compare-and-set against "free".
     *
     * 🔴 **Not re-entrant, and deliberately not.** It used to be
     * (`|| holder.get() === owner`), documented as needed because "`start()` on
     * an already-recording session is a no-op that must still report success" —
     * which was **false**: [DictationSession.start] returns at its
     * `if (recording.get()) return true` check *before* reaching `acquire`, so
     * that case never got here. No production path reached the clause; only a
     * test did, repeating the same wrong reason. Rather than invent a better
     * reason for it, it is gone.
     *
     * Every opener therefore arrives here holding nothing, which all of them
     * already did. `DictationSession.start()`: the refused-acquire branch never
     * took it, the capture-failure branch releases what it took, and
     * `stop()`/`destroy()` release. `DictateActivity`'s phone-only path tears
     * the previous recogniser down — which releases — before taking the mic for
     * the next one. A caller that ever
     * genuinely needs "I may already hold this" must say so at its own call
     * site with [isHeldBy]; it must not come back and rely on a silent
     * re-entrancy here.
     *
     * @return true when [owner] now holds the microphone. False when it is
     *   held by anybody at all — including [owner] itself — which the caller
     *   must turn into a reportable failure rather than a recording.
     */
    fun acquire(owner: Any): Boolean = holder.compareAndSet(null, owner)

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
     * True when the microphone is held by **somebody other than** any of
     * [mine] (and false when it is free, or when one of [mine] is the holder).
     *
     * The predicate a surface about to open a microphone wants: "is starting a
     * capture here going to collide with a capture that is not one of mine".
     * Variadic because a surface can have more than one opener and therefore
     * more than one token: `DictateActivity` has two — its [DictationSession]
     * and, on the phone-only path, the activity itself — and the question it
     * has to ask is about the pair, not about either one.
     *
     * ⚠ It has exactly **one** call site (`DictateActivity.startDictation`),
     * and [isHeld]/[isHeldBy] have none outside tests and `BubbleService`'s
     * failure classification. This is therefore *not* a predicate consolidated
     * across N sites — an earlier version of this comment claimed that, and it
     * was never true. It lives here because the holder's representation is
     * private, not because it is shared.
     */
    fun isHeldByOtherThan(vararg mine: Any?): Boolean {
        val current = holder.get() ?: return false
        return mine.none { it === current }
    }

    /** Test seam only. */
    fun clearForTest() {
        holder.set(null)
    }
}

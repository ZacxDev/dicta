package dev.zacx.dicta

import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * The one owner of **"is the floating bubble enabled"**.
 *
 * ## Why an owner rather than a key four files read
 *
 * The flag used to live as a `private const val PREF_BUBBLE` inside
 * [MainActivity], and the activity's switch was its only writer. That is
 * precisely how issue #14's "no way to get the bubble back" arose: the
 * notification's *Hide bubble* action stopped the service through a different
 * file, so there was nowhere for it to record that the bubble is now off, and
 * the switch went on claiming otherwise. A predicate open-coded at N sites is
 * wrong at N−1 of them in the same direction; the fix is one place that owns it.
 *
 * Four surfaces can change whether the bubble is on, and every one of them
 * goes through this object — but **not all through the same entry point**, so
 * "everything routes through [turnOn]" is the wrong summary to carry around:
 *
 *  * [MainActivity]'s switch and [DictaTileService] — [turnOn] / [turnOff].
 *  * [BubbleService]'s *Hide bubble* path — [set], because the service is
 *    already stopping itself and must not call `stopService` on itself.
 *  * [BubbleBootReceiver] — [isOn] + [startService], plus its own
 *    `canDrawOverlays` check. It deliberately routes *around* [turnOn] because
 *    it must restore the bubble without *writing* the flag at all: nothing the
 *    user did at boot can have changed their stored intent. That open-coded
 *    check is **not** a duplicate to consolidate away; it is argued at the
 *    receiver's own site.
 *
 * All four are pinned by `BubbleEnabledWritersSeamTest`'s mutator ledger, which
 * is why it matches [set] and [startService] and not only [turnOn] / [turnOff].
 *
 * ## The pref file and key are a compatibility contract
 *
 * [PREF_FILE] and [PREF_KEY] keep the exact names the activity used, so an
 * existing install's bubble stays on across this change. Renaming either would
 * read as a clean refactor and silently reset every user to off — there is no
 * migration here and none is wanted.
 *
 * 🔴 Both are `private`, and that visibility is the guard. While they were
 * `internal`, any file under `app/src/main` could write the flag as
 * `getSharedPreferences(BubbleEnabled.PREF_FILE, …).putBoolean(BubbleEnabled.PREF_KEY, …)`
 * and evade **both** of `BubbleEnabledWritersSeamTest`'s ledgers at once: the
 * raw-key scan matches the string literal `"bubble_enabled"`, which that
 * spelling never writes, and the mutator scan matches `BubbleEnabled.<mutator>(`
 * calls, which it never makes. A text scan cannot close that; the compiler can,
 * so it does. `onlyTheOwnerCanNameThePreferenceFileAndKey` pins the visibility
 * itself, and `BubbleHideFromNotificationTest` declares its own copies of the
 * two literals rather than reaching in here — which is also why the names stay
 * pinned from outside.
 */
object BubbleEnabled {

    /** Shared with the notification toggle and the rest of the app's prefs. */
    private const val PREF_FILE = "dicta"

    /** 🔴 Do not rename: existing installs hold their state under this key. */
    private const val PREF_KEY = "bubble_enabled"

    /**
     * What [turnOn] actually achieved. Two outcomes rather than a `Boolean`
     * because the caller has something to say about one of them: a missing
     * overlay permission is the user's to fix, and nothing was written.
     *
     * 🔴 There was a third arm, `REFUSED`, for a platform-refused foreground
     * start. It was **deleted as unreachable, and it has no replacement
     * rationale** — see [startService] for why no refusal can reach this
     * return value. Do not re-derive one: if you are about to add an arm here
     * because a start "might fail", read that comment first.
     */
    enum class Start {
        /** The flag is on and the service was asked to start. */
        STARTED,

        /** `Display over other apps` is not granted; nothing was written. */
        NO_OVERLAY_PERMISSION,
    }

    fun isOn(context: Context): Boolean =
        prefs(context).getBoolean(PREF_KEY, false)

    /**
     * Records the flag and nothing else.
     *
     * The caller that wants the service started or stopped as well wants
     * [turnOn] / [turnOff]; this exists for the path where the service is
     * already stopping *itself* — [BubbleService]'s `ACTION_STOP` handler,
     * which must not call `stopService` on itself.
     */
    fun set(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(PREF_KEY, on).apply()
    }

    /**
     * Flag on, service started — or neither.
     *
     * The permission is checked **before** the flag is written so a denial
     * cannot leave the switch claiming a bubble that was never allowed to
     * exist; that disagreement between flag and reality is the whole of issue
     * #14. Once past that check the flag is written unconditionally, because
     * there is no second failure this function can learn about — [startService]
     * says why.
     */
    fun turnOn(context: Context): Start {
        if (!Settings.canDrawOverlays(context)) return Start.NO_OVERLAY_PERMISSION
        set(context, true)
        startService(context)
        return Start.STARTED
    }

    /** Flag off, service stopped. */
    fun turnOff(context: Context) {
        set(context, false)
        context.stopService(Intent(context, BubbleService::class.java))
    }

    /**
     * Asks for the foreground service, swallowing a throw rather than
     * propagating it.
     *
     * 🔴 The `runCatching` is crash insurance and nothing more. One caller is
     * [BubbleBootReceiver], and an uncaught throw there is a crash in a
     * broadcast receiver, which the user sees as "Dicta stopped" on an
     * otherwise fresh boot. Keep it.
     *
     * 🔴 **It is NOT a signal, and there is deliberately no return value.**
     * This wraps `startForegroundService` only, so the one refusal it could
     * ever observe is Android 12+'s background foreground-service-start
     * restriction — and on both paths that reach it, that restriction is
     * exempted:
     *
     *  * [turnOn] returns before calling this unless `canDrawOverlays` is true,
     *    which means the app holds `SYSTEM_ALERT_WINDOW`, which is itself on
     *    the documented exemption list.
     *  * [BubbleBootReceiver] additionally requires
     *    `ACTION_BOOT_COMPLETED`, a second exemption, *and* checks
     *    `canDrawOverlays` as well — so it is exempt twice over.
     *
     * The other refusal — a `microphone`-typed FGS start, Android 14+ — cannot
     * surface here at all, in either direction. It is thrown out of
     * `Service.startForeground`, in the *service's* own process after
     * `startForegroundService` has already returned, and
     * `BubbleService.platformPromoteForeground` catches it there and turns it
     * into a local `Boolean` that `onCreate` answers with `stopSelf()`. No
     * value crosses back.
     *
     * So a previous version of this function returned `Boolean`, a `Start`
     * enum carried a `REFUSED` arm, and [BubbleBootReceiver] posted a
     * "could not start after the restart" notification on it. **All three were
     * deleted as unreachable; none of them has a replacement justification, and
     * none is wanted.** Reaching for a fresh reason to put the signal back is
     * how it comes back: if you want one, the thing to change is where the
     * refusal is *caught* (`platformPromoteForeground`), not this function.
     */
    internal fun startService(context: Context) {
        runCatching {
            context.startForegroundService(Intent(context, BubbleService::class.java))
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
}

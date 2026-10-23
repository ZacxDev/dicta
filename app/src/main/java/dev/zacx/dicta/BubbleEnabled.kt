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
 * Every surface that can turn the bubble on or off goes through here:
 * [MainActivity]'s switch, [BubbleService]'s stop path, [DictaTileService] and
 * [BubbleBootReceiver].
 *
 * ## The pref file and key are a compatibility contract
 *
 * [PREF_FILE] and [PREF_KEY] keep the exact names the activity used, so an
 * existing install's bubble stays on across this change. Renaming either would
 * read as a clean refactor and silently reset every user to off — there is no
 * migration here and none is wanted. `BubbleEnabledWritersSeamTest` pins the
 * names, and `BubbleHideFromNotificationTest` reads them literally rather than
 * through this object for the same reason.
 */
object BubbleEnabled {

    /** Shared with the notification toggle and the rest of the app's prefs. */
    internal const val PREF_FILE = "dicta"

    /** 🔴 Do not rename: existing installs hold their state under this key. */
    internal const val PREF_KEY = "bubble_enabled"

    /**
     * What [turnOn] actually achieved. Three outcomes rather than a `Boolean`
     * because the callers must say different things about them: a missing
     * overlay permission is the user's to fix and nothing was written, while a
     * refused foreground start is the platform's decision and leaves the flag
     * cleared again so no switch claims a bubble that is not there.
     */
    enum class Start {
        /** The flag is on and the service was asked to start. */
        STARTED,

        /** `Display over other apps` is not granted; nothing was written. */
        NO_OVERLAY_PERMISSION,

        /** The platform refused the foreground start; the flag is back to off. */
        REFUSED,
    }

    /**
     * How the service is started. `null` means the platform call.
     *
     * The seam exists because a *refused* start is the one outcome no test can
     * provoke otherwise: Robolectric's `startForegroundService` records the
     * call and never throws, so [Start.REFUSED] — and the boot-time restore
     * notification that depends on it — would be unreachable from the JVM and
     * pinned by nothing. Set before the call and cleared after; `internal`, so
     * production has no way to reach it.
     */
    internal var serviceStarter: ((Context) -> Unit)? = null

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
     * The permission is checked **before** the flag is written so a refusal
     * cannot leave the switch claiming a bubble that was never allowed to
     * exist; that disagreement between flag and reality is the whole of issue
     * #14.
     */
    fun turnOn(context: Context): Start {
        if (!Settings.canDrawOverlays(context)) return Start.NO_OVERLAY_PERMISSION
        set(context, true)
        if (!startService(context)) {
            set(context, false)
            return Start.REFUSED
        }
        return Start.STARTED
    }

    /** Flag off, service stopped. */
    fun turnOff(context: Context) {
        set(context, false)
        context.stopService(Intent(context, BubbleService::class.java))
    }

    /**
     * Asks for the foreground service, reporting a platform refusal rather
     * than throwing.
     *
     * 🔴 `startForegroundService` is not a call that only fails for
     * programming errors: from Android 12 it throws
     * `ForegroundServiceStartNotAllowedException` when the app is in the
     * background without an exemption, and a `microphone`-typed start from a
     * `BOOT_COMPLETED` receiver is refused from Android 14. An uncaught throw
     * here would be a crash in a broadcast receiver, which the user sees as
     * "Dicta stopped" on an otherwise fresh boot.
     */
    internal fun startService(context: Context): Boolean = runCatching {
        val starter = serviceStarter
        if (starter != null) {
            starter(context)
        } else {
            context.startForegroundService(Intent(context, BubbleService::class.java))
        }
    }.isSuccess

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
}

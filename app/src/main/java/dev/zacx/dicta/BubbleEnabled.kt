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
 *    already stopping itself and must not call `stopService` on itself. The
 *    same file also reports a refused foreground promotion through
 *    [foregroundPromotionRefused], which is a *conditional* write: whether it
 *    changes anything depends on who asked for the start, and that decision is
 *    made here rather than at the call site.
 *  * [BubbleBootReceiver] — on `BOOT_COMPLETED`, [isOn] + [startService] plus
 *    its own `canDrawOverlays` check. It deliberately routes *around* [turnOn]
 *    because it must restore the bubble without *writing* the flag: nothing the
 *    user did at boot can have changed their stored intent. That open-coded
 *    check is **not** a duplicate to consolidate away; it is argued at the
 *    receiver's own site. On `MY_PACKAGE_REPLACED` the same receiver *does*
 *    write — [set] to `false` — because an app update has already taken the
 *    bubble away, and that one is argued there too.
 *
 * All four are pinned by `BubbleEnabledWritersSeamTest`'s mutator ledger, which
 * is why it matches [set], [startService] and [foregroundPromotionRefused] and
 * not only [turnOn] / [turnOff].
 *
 * ## 🔴 What "the surfaces cannot disagree" does and does NOT mean
 *
 * One owner for the *writes* is only half of it, and this kdoc used to stop
 * there — which made the invariant it claimed **false**, because two surfaces
 * can route every write through one object and still *display* different
 * answers. They did: `MainActivity` rendered its switch from
 * `isOn && overlayGranted`, [DictaTileService] painted the tile from the same
 * conjunction, and that tile's click branched on bare [isOn]. Three hand-copied
 * readings of one question.
 *
 * So the invariant takes **two** rules, and both are load-bearing:
 *
 *  1. every write goes through this object — the mutator ledger in
 *     `BubbleEnabledWritersSeamTest`;
 *  2. everything the user is **shown** is derived from [isUp], never from
 *     [isOn] — `everyDisplayedBubbleStateComesFromOnePredicate` in the same
 *     file.
 *
 * [isOn] is stored intent, for the code that acts on intent
 * ([BubbleBootReceiver]). It is not for anything the user reads, and the states
 * where intent and reality come apart are closed where they happen rather than
 * by a term in [isUp] — see [isUp] for the two of them and why a liveness check
 * is the wrong instrument.
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

    /**
     * The **stored intent**: the last on/off the user chose.
     *
     * 🔴 This is not "there is a bubble on screen" — see [isUp], which is what
     * every surface that *displays* the state must read. The flag survives a
     * reboot, an app update, a revoked overlay permission and a service the
     * platform killed, by design: none of those is the user changing their
     * mind. That is exactly why it cannot be shown to the user directly.
     */
    fun isOn(context: Context): Boolean =
        prefs(context).getBoolean(PREF_KEY, false)

    /**
     * 🔴 **What the user is shown** — the one predicate every surface that
     * displays or toggles the bubble must read, instead of [isOn].
     *
     * ## Why [isOn] is not what to render
     *
     * [isOn] is stored intent, and it deliberately outlives the bubble. The
     * overlay permission is the part intent cannot speak for: the user can
     * revoke `Display over other apps` from system Settings at any time, with
     * no app code running to notice, and without that permission the bubble
     * cannot exist at all. Rendering [isOn] alone in that state claims a bubble
     * that is not there, which is issue #14's defect reached by a new route.
     *
     * ## 🔴 Why it is one function and not three copies of the `&&`
     *
     * This is not tidiness; the duplicates were already disagreeing.
     * `MainActivity.refreshStatus` open-coded `isOn && overlayGranted` for the
     * switch, `DictaTileService.render` open-coded the same conjunction for the
     * tile's state — and that tile's `onClick` branched on bare [isOn]. So with
     * the permission revoked the tile painted itself from one predicate and
     * decided its click direction from another. A predicate open-coded at N
     * sites is wrong at N−1 of them in the same direction, and a toggle is the
     * worst place for it: the two halves of a toggle disagreeing is a control
     * that does the opposite of what it displays.
     * `BubbleEnabledWritersSeamTest.everyDisplayedBubbleStateComesFromOnePredicate`
     * pins the call sites.
     *
     * ## 🔴 What it cannot see, and who covers that
     *
     * It is a **synchronous read of the flag and the permission** — it does not
     * and must not ask whether the service is live. A field recording that was
     * tried and rejected: `startForegroundService` returns *before* the
     * service's `onCreate` runs, so a liveness term would make the tile paint
     * itself INACTIVE in the instant after the user turned the bubble on, which
     * is a worse lie than the one it set out to fix.
     *
     * The two states where the flag is set and no bubble exists *despite* the
     * permission are therefore closed at their source, by whoever is still
     * running when it happens — which is why there is no third term here:
     *
     *  * `promoteForeground` refused → [BubbleService.onCreate] hands it to
     *    [foregroundPromotionRefused], which clears the flag before the service
     *    stops itself — **for a start the user asked for.** 🔴 A refused *boot*
     *    restore deliberately does not clear it, because clearing it there
     *    means the bubble never returns, on that boot or any later one. That
     *    one case therefore does leave the flag set with no bubble until the
     *    next start, and [isUp] cannot see it. The trade is deliberate and is
     *    argued at [foregroundPromotionRefused]: the alternative is destroying
     *    stored intent the user can only restore by rediscovering the switch.
     *  * an **app update** stops the service, and Android does not restart one
     *    across a package replacement → [BubbleBootReceiver] clears the flag on
     *    `ACTION_MY_PACKAGE_REPLACED`. It deliberately does not restart the
     *    bubble; it only stops the flag claiming one.
     *
     * Not a mutator, so `BubbleEnabledWritersSeamTest`'s ledger excludes it for
     * the same reason it excludes [isOn]: a reader cannot put the flag out of
     * step with reality.
     */
    fun isUp(context: Context): Boolean =
        Settings.canDrawOverlays(context) && isOn(context)

    /**
     * 🔴 Whether the start request this process most recently issued was the
     * **user** asking for the bubble — the one condition under which a platform
     * refusal may undo their stored intent. Read only by
     * [foregroundPromotionRefused].
     *
     * Process-local and deliberately **not** persisted: it describes a start
     * request *this process* made, and a process that made none has nothing to
     * say about origin. `false` is therefore the fail-safe initial value, and it
     * is also what a `START_STICKY` restart after a process kill reads — nobody
     * asked for that start either, so it must not be able to delete what the
     * user chose.
     *
     * Safe as a single field rather than something handed along: the manifest
     * declares no `android:process` (`TileManifestTest.theAppIsSingleProcess`),
     * so there is one copy of it, and every writer and the reader run on the
     * main thread — [BubbleBootReceiver.onReceive], [MainActivity] and
     * [DictaTileService] through [turnOn], and [BubbleService.onCreate].
     * `@Volatile` is belt-and-braces, not a claim that a second thread exists.
     *
     * 🔴 It is **not** an intent extra, which is where an origin would normally
     * ride. `Service.onCreate` — the only place the refusal is observable — is
     * not handed the starting `Intent`; `onStartCommand` is, and moving the
     * promotion there to reach it would rest on an ordering claim about whether
     * `onStartCommand` is still dispatched after an `onCreate` that called
     * `stopSelf`. No device has been attached to this project, so that claim
     * could not be checked, and this mechanism needs no claim beyond
     * single-process.
     */
    @Volatile
    private var userAskedForTheLatestStart = false

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
        startService(context, userAsked = true)
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
     *
     * @param userAsked whether this start is the user turning the bubble on
     *   right now. It is recorded in [userAskedForTheLatestStart] and is the
     *   only thing that may later authorise clearing the flag — see
     *   [foregroundPromotionRefused]. **Deliberately has no default**: both
     *   call sites have a definite answer, and a default is precisely how the
     *   boot path would silently acquire the wrong one.
     */
    internal fun startService(context: Context, userAsked: Boolean) {
        userAskedForTheLatestStart = userAsked
        runCatching {
            context.startForegroundService(Intent(context, BubbleService::class.java))
        }
    }

    /**
     * The platform refused to promote the started service to the foreground, so
     * there is no bubble and never was one for that start. Clears the flag —
     * **only if the user asked for that start.**
     *
     * ## Why the asymmetry
     *
     * With `canDrawOverlays` true nothing masks a set flag, so a *user*-initiated
     * refusal that left it set is issue #14 verbatim: [MainActivity]'s switch
     * renders ON with nothing running, and a `Switch` fires its listener only
     * on a **change**, so one tap does nothing and the user has to toggle OFF
     * then ON. That is the case this clears.
     *
     * 🔴 A **boot** start is the opposite case, and clearing there destroys
     * something the user cannot get back by looking at the screen. [isOn] is
     * stored intent and survives a reboot by design; a platform refusal during
     * a boot restore is not the user changing their mind. Cleared, the bubble
     * does not come back on this boot *or any later one*, and the only route
     * back is rediscovering the switch in the app — the complaint issue #14 is
     * about. [BubbleBootReceiver] argues exactly this policy for its own two
     * checks, and the refusal handled here is the one that arrives *after* that
     * receiver has returned, in the service's process, which is why the policy
     * has to be applied a second time at a different site.
     *
     * Reachability today is low and not zero. `targetSdk` is 34 and every
     * foreground-service permission is declared, so the FGS-type restrictions
     * the receiver's kdoc sets out do not bite yet — but the `specialUse` one
     * is gated on `targetSdk >= 35`, and
     * [BubbleService.platformPromoteForeground] turns **any** throw out of
     * `buildNotification` or `startForeground` into a refusal, not only that
     * one. No device has been attached to this project, so none of that is
     * measured; it is read off the documented contracts.
     *
     * 🔴 This is a method on the owner rather than a predicate [BubbleService]
     * branches on, because the owner is the only place allowed to decide
     * whether the flag changes. Exporting the question for the call site to
     * answer would put a second copy of the policy there, which is the shape
     * this object exists to prevent.
     */
    internal fun foregroundPromotionRefused(context: Context) {
        if (userAskedForTheLatestStart) set(context, false)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
}

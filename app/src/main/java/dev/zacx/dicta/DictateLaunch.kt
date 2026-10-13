package dev.zacx.dicta

import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * The one place an "open the overlay from outside the app" intent is built.
 *
 * ## Why this exists rather than three `Intent(...)` calls
 *
 * The bubble, the Quick Settings tile and the persistent notification are the
 * three "trigger from anywhere" entry points, and all three were open-coding
 * the same two-line construction. All three were wrong in the same direction
 * (issue #11), which is the characteristic failure of a predicate spelled at N
 * sites: it gets fixed at one and silently kept at the others.
 *
 * ## 🔴 The flag is only half the mechanism — the other half is in the manifest
 *
 * [Intent.FLAG_ACTIVITY_NEW_TASK] does **not** mean "make a new task". It means
 * "put this activity in a task matching its `taskAffinity`, creating one only
 * if none exists". `DictateActivity` used to declare no affinity, so it
 * inherited the application default — the very task `MainActivity` sits in.
 * Tapping the bubble therefore brought Dicta's own task to the front and
 * stacked the overlay on top of `MainActivity`; because the overlay's theme is
 * translucent, the user saw the entire app UI behind the floating card instead
 * of the screen they were actually on.
 *
 * The fix is the `android:taskAffinity` on `DictateActivity` in the manifest.
 * This object is useless without it, and [OVERLAY_TASK_AFFINITY] below is the
 * same string — `DictateLaunchTest` pins the two together, because they live in
 * different files and a drift between them restores the bug silently.
 *
 * ## What is deliberately NOT routed through here
 *
 * `MainActivity`'s own "Try dictation now" button starts the overlay with **no
 * flags**, staying inside `MainActivity`'s task. That is correct there: the
 * user is looking at the app, so the card belongs over the app. `taskAffinity`
 * is consulted only when `NEW_TASK` is set, so that path is unaffected by the
 * manifest change.
 */
object DictateLaunch {

    /**
     * The overlay's own task affinity. **Must** equal the
     * `android:taskAffinity` on `DictateActivity` in `AndroidManifest.xml`.
     *
     * A distinct named affinity rather than `""`: an empty affinity means "no
     * affinity to anything", which can hand out a fresh task per launch. A
     * stable name means the overlay reuses one task of its own, which is what
     * `launchMode="singleTop"` and `excludeFromRecents="true"` already assume.
     */
    const val OVERLAY_TASK_AFFINITY: String = "dev.zacx.dicta.overlay"

    /**
     * An intent that opens the overlay in its own task, over whatever the user
     * is currently looking at.
     */
    fun intent(context: Context): Intent =
        Intent(context, DictateActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * [intent] wrapped for the callers that cannot start an activity directly:
     * the Quick Settings tile (the `Intent` overload of
     * `startActivityAndCollapse` is deprecated as of API 34) and the
     * notification action.
     *
     * `FLAG_IMMUTABLE` because nothing may rewrite the target, and
     * `FLAG_UPDATE_CURRENT` so re-registering replaces the existing one rather
     * than accumulating.
     */
    fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            intent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}

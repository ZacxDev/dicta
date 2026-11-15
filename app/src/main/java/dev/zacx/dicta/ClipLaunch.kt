package dev.zacx.dicta

import android.content.Context
import android.content.Intent

/**
 * The one place an "open the shared-clipboard picker from the bubble" intent is
 * built.
 *
 * ## 🔴 The flag is only half the mechanism — the other half is the manifest
 *
 * This is [SnipLaunch]'s and [HistoryLaunch]'s argument applied to a fourth
 * floating activity, and it is repeated rather than referenced because the bug
 * it prevents is a two-FILE invariant and reading one file cannot reveal it.
 * [Intent.FLAG_ACTIVITY_NEW_TASK] does **not** mean "make a new task"; it means
 * "use the task matching this activity's `taskAffinity`, creating one only if
 * none exists". With no affinity declared, [ClipPickerActivity] would inherit
 * the application default — `MainActivity`'s task — so tapping the clip button
 * would bring Dicta's own task forward and stack a translucent picker on top of
 * `MainActivity`, showing the whole app UI behind it instead of the app the user
 * was actually looking at. That is issue #11, reached by a new route.
 *
 * A distinct non-empty name rather than `""`: an empty affinity means "no
 * affinity to anything" and can hand out a fresh task per launch, which fights
 * `launchMode="singleTop"`.
 *
 * 🔴 A separate object rather than one "open a picker" helper taking a `Class`,
 * for the reason [HistoryLaunch] gives: the thing each of these owns is the
 * pairing with ITS OWN `android:taskAffinity`, and a generic helper would have
 * to be handed the affinity's partner class at every call site, which is the
 * drift these objects exist to remove.
 *
 * ## 🔴 This is NOT a dictation start surface
 *
 * Nothing here can reach the microphone. The picker lists rows fetched over the
 * mesh and writes one to the clipboard; it declares no `RECORD_AUDIO` use, holds
 * no session, and starts no activity at all. `StartSurfaceLedgerTest`'s
 * `PLAIN_OVERLAY_LAUNCH` scan counts `DictateLaunch.intent(` and hand-built
 * `DictateActivity` intents, neither of which appears here, so that ledger of
 * recording surfaces does not grow — exactly as it did not for the snippet
 * picker.
 */
object ClipLaunch {

    /**
     * The clip picker's own task affinity. **Must** equal the
     * `android:taskAffinity` on `ClipPickerActivity` in `AndroidManifest.xml`.
     *
     * 🔴 Distinct from all three existing affinities —
     * [DictateLaunch.OVERLAY_TASK_AFFINITY], [SnipLaunch.PICKER_TASK_AFFINITY]
     * and [HistoryLaunch.PICKER_TASK_AFFINITY]. With `singleTop` on every one
     * of them, two floating surfaces sharing a task would stack, and Back would
     * walk through a surface the user never opened.
     *
     * 🔴 And distinct from [CaptureActivity]'s, which is a FOURTH value. That
     * one is the easiest to get wrong because the capture activity is
     * `noHistory` and looks like it needs no task of its own — but an
     * undeclared affinity inherits `MainActivity`'s, and `SnipLaunch.kt:14-30`
     * plus the manifest state three times over what that costs.
     */
    const val PICKER_TASK_AFFINITY: String = "dev.zacx.dicta.clip"

    /**
     * An intent that opens the clip picker in its own task, over whatever the
     * user is currently looking at.
     */
    fun intent(context: Context): Intent =
        Intent(context, ClipPickerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

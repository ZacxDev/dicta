package dev.zacx.dicta

import android.content.Context
import android.content.Intent

/**
 * The one place an "open the history picker from the bubble" intent is built.
 *
 * ## 🔴 The flag is only half the mechanism — the other half is the manifest
 *
 * This is [SnipLaunch]'s argument applied to a third floating activity, and it
 * is repeated rather than referenced because the bug it prevents is a two-FILE
 * invariant and reading one file cannot reveal it.
 * [Intent.FLAG_ACTIVITY_NEW_TASK] does **not** mean "make a new task"; it means
 * "use the task matching this activity's `taskAffinity`, creating one only if
 * none exists". With no affinity declared, [HistoryPickerActivity] would
 * inherit the application default — `MainActivity`'s task — so tapping the
 * history button would bring Dicta's own task forward and stack a translucent
 * picker on top of `MainActivity`, showing the whole app UI behind it instead
 * of the app the user was actually looking at. That is issue #11, reached by a
 * new route.
 *
 * A distinct non-empty name rather than `""`: an empty affinity means "no
 * affinity to anything" and can hand out a fresh task per launch, which fights
 * `launchMode="singleTop"`.
 *
 * ## 🔴 This is NOT a dictation start surface
 *
 * Nothing here can reach the microphone. The picker lists stored dictations and
 * opens one through [DictateLaunch.historyIntent], which carries
 * `EXTRA_HISTORY_ID` — so `DictateActivity.onCreate` populates the card from
 * history and does **not** start a capture. That is why
 * `StartSurfaceLedgerTest`'s `PLAIN_OVERLAY_LAUNCH` scan matches
 * `DictateLaunch.intent(` and `DictateLaunch.pendingIntent(` but deliberately
 * not `historyIntent(`, and why opening this picker does not grow that ledger —
 * exactly as the chip reveal it replaces did not.
 *
 * 🔴 Deliberately a separate object from [SnipLaunch] rather than one
 * "open a picker" helper taking a `Class`. The thing each of these owns is the
 * pairing with ITS OWN `android:taskAffinity`, and the two affinities must stay
 * different: with `singleTop` on both, two floating surfaces sharing one task
 * would stack, and Back would walk through a surface the user never opened. A
 * generic helper would have to be handed the affinity's partner class at every
 * call site, which is the drift this object exists to remove.
 */
object HistoryLaunch {

    /**
     * The history picker's own task affinity. **Must** equal the
     * `android:taskAffinity` on `HistoryPickerActivity` in
     * `AndroidManifest.xml`.
     *
     * 🔴 Distinct from both [DictateLaunch.OVERLAY_TASK_AFFINITY] and
     * [SnipLaunch.PICKER_TASK_AFFINITY]. The card is the surface this picker
     * OPENS, so sharing the card's affinity would be the worst of the three
     * collisions: the picker would be in the same `singleTop` task as the card
     * it launches, and Back from that card would land on the picker it came
     * from rather than on the app the user started in — which is precisely the
     * thing the shell's `finish()` exists to prevent.
     */
    const val PICKER_TASK_AFFINITY: String = "dev.zacx.dicta.history"

    /**
     * An intent that opens the history picker in its own task, over whatever
     * the user is currently looking at.
     */
    fun intent(context: Context): Intent =
        Intent(context, HistoryPickerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

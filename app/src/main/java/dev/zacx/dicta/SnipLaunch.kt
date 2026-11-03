package dev.zacx.dicta

import android.content.Context
import android.content.Intent

/**
 * The one place a "open the snippet picker from the bubble" intent is built.
 *
 * ## 🔴 The flag is only half the mechanism — the other half is the manifest
 *
 * This is [DictateLaunch]'s argument applied to a second floating activity, and
 * it is repeated rather than referenced because the bug it prevents is a
 * two-FILE invariant and reading one file cannot reveal it.
 * [Intent.FLAG_ACTIVITY_NEW_TASK] does **not** mean "make a new task"; it means
 * "use the task matching this activity's `taskAffinity`, creating one only if
 * none exists". With no affinity declared, `SnipPickerActivity` would inherit
 * the application default — `MainActivity`'s task — so tapping the snip button
 * would bring Dicta's own task forward and stack a translucent picker on top of
 * `MainActivity`, showing the whole app UI behind it instead of the app the user
 * was actually looking at. That is issue #11, reached by a new route.
 *
 * A distinct non-empty name rather than `""`: an empty affinity means "no
 * affinity to anything" and can hand out a fresh task per launch, which fights
 * `launchMode="singleTop"`.
 *
 * `SnipSeamLedgerTest` pins [PICKER_TASK_AFFINITY] against the manifest's
 * attribute, because they live in different files and a drift between them
 * restores the bug silently — and it pins that this object is the only place
 * the picker intent is constructed.
 *
 * ## 🔴 This is NOT a dictation start surface
 *
 * Nothing here can reach the microphone: the picker reads the clipboard and
 * writes it, declares no `RECORD_AUDIO` use and has no session. It is
 * deliberately a separate object from [DictateLaunch] so that
 * `StartSurfaceLedgerTest`'s `PLAIN_OVERLAY_LAUNCH` scan — which counts
 * `DictateLaunch.intent(` and hand-built `DictateActivity` intents, because
 * either auto-starts a capture — stays a ledger of recording surfaces and does
 * not have to learn about a picker. Issue #38 says so explicitly: opening the
 * picker is not a dictation start.
 */
object SnipLaunch {

    /**
     * The picker's own task affinity. **Must** equal the
     * `android:taskAffinity` on `SnipPickerActivity` in `AndroidManifest.xml`.
     *
     * 🔴 Deliberately NOT [DictateLaunch.OVERLAY_TASK_AFFINITY]. Sharing one
     * affinity would put the picker and the transcription card in the same task
     * with `singleTop` on both, so opening the picker over an open card would
     * stack them and Back would walk through a surface the user never asked
     * for. Two unrelated floating surfaces, two tasks.
     */
    const val PICKER_TASK_AFFINITY: String = "dev.zacx.dicta.snip"

    /**
     * An intent that opens the picker in its own task, over whatever the user
     * is currently looking at.
     */
    fun intent(context: Context): Intent =
        Intent(context, SnipPickerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

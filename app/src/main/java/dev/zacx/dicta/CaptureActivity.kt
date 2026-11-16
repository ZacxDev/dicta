package dev.zacx.dicta

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * Takes highlighted text from any app and queues it for the shared clipboard.
 *
 * Two entry points, one activity, because the work is byte-identical:
 * `ACTION_PROCESS_TEXT` (the selection toolbar's ⋮ overflow) and `ACTION_SEND`
 * (the sharesheet). `<intent-filter android:label>` overrides per filter, so
 * the two can still read differently to the user.
 *
 * ## 🔴 This is the app's FIRST third-party-reachable entry point
 *
 * — other than MAIN/LAUNCHER and the QS tile. The manifest's own posture on the
 * snippet picker is *"this is a PUBLIC app, and an exported surface is an entry
 * point any installed app could invoke"*, and `SnipSeamLedgerTest` asserts that
 * picker is `exported="false"`. This activity has to be exported, so the
 * compensating claim is made structurally instead: it reads two extras, writes
 * one file, toasts and finishes, and is **incapable of reaching the microphone,
 * the dictation history, either token, or any picker**.
 * `ClipSeamLedgerTest.theCaptureActivityCannotReachTheMicrophoneOrHistory` is
 * that assertion, and it is the highest-value test in this feature.
 *
 * ## 🔴 Platform contracts that are easy to get wrong, each with its consequence
 *
 *  * `EXTRA_PROCESS_TEXT` is a **`CharSequence`** (API 23), so it is read with
 *    `getCharSequenceExtra`. `getStringExtra` returns **null** for a
 *    `Spanned` — which is what a styled selection in a rich-text field is — so
 *    the capture would silently do nothing for exactly the selections users
 *    make in a browser or a mail composer.
 *  * **It can be null.** `TextView.getSelectedText()` returns null when
 *    `!hasSelection()`, and the platform still fires the intent. A null extra
 *    must land NOTHING and say so, not queue an empty row.
 *  * **`RESULT_CANCELED`, always.** `TextView.onActivityResult` *inserts* the
 *    returned `EXTRA_PROCESS_TEXT` into the user's field when the result is
 *    `RESULT_OK`. Returning OK with text would edit the document the user was
 *    reading.
 *  * **No `setContentView`, AND a translucent theme.** Both halves, and only
 *    the first shipped: the manifest element declared no `android:theme`, so
 *    this inherited `@style/Theme.Dicta` — opaque — and the platform raised a
 *    full-screen empty window over whatever the user was reading before
 *    `finish()`. It now declares
 *    `@android:style/Theme.Translucent.NoTitleBar`. 🔴 Still **not**
 *    `@style/Theme.Dicta.Overlay`: that one makes a window *floating*, and it
 *    is what `FloatingOverlaySeamLedgerTest` derives its set from, so wearing
 *    it would owe a `FloatingWindowSizeTest` case for a window this never
 *    shows. ⚠ The opaque flash and its absence are **device-observable only** —
 *    Robolectric raises no window — so the manifest attribute is what is
 *    pinned and the handset is what confirms the fix.
 *  * **Its own `taskAffinity`, a FOURTH distinct value.** `SnipLaunch.kt:14-30`
 *    and the manifest state three times over that an undeclared affinity
 *    inherits `MainActivity`'s — issue #11.
 *
 * ## Known costs, stated rather than discovered on the handset
 *
 *  * **The toolbar item is always in the ⋮ overflow, never on the bar.**
 *    `Editor.onInitializeMenu` hardcodes `SHOW_AS_ACTION_NEVER` per item. In
 *    Chrome/WebView — one shared reimplementation in `//content/public/android`
 *    — items are `SHOW_AS_ACTION_IF_ROOM` and *can* sit on the bar.
 *  * 🔴 **On API 30+ the HOST app gates visibility and Dicta cannot fix it.**
 *    `Editor.loadSupportedActivities` calls `queryIntentActivities` in the
 *    host's process, so a host that does not declare
 *    `<queries><intent><action android:name="…PROCESS_TEXT"/>…` will never show
 *    the item. **This is why `ACTION_SEND` is a necessary second path, not a
 *    convenience.**
 *  * **Selections over 100,000 characters are silently truncated.**
 *    `Editor.fireIntent` → `TextUtils.trimToParcelableSize`,
 *    `PARCEL_SAFE_TEXT_LENGTH = 100000`, `subSequence(0, size)` — no ellipsis,
 *    no flag, no exception; **the receiver cannot tell**. Chromium caps at the
 *    same number but appends an ellipsis, so it is detectable there and not in
 *    native fields. Nothing here can detect or report it.
 *  * The label is controllable (`resolveInfo.loadLabel`); the **icon is not** —
 *    there is no `loadIcon`/`setIcon` anywhere in
 *    `ProcessTextIntentActionsHandler`. Hence a short verb for the label.
 *  * The sharesheet ignores `<activity>`/`<intent-filter>` icons from API 29,
 *    caps `android:priority` to 0 for `ACTION_SEND`, and there is **no
 *    documented way** to keep an `ACTION_SEND`-filtered activity out of it.
 *
 * ⚠ **None of the above was observed on a device.** Every claim is AOSP `main`
 * source plus documentation. **Samsung reimplements the selection toolbar** and
 * the target handset is a Galaxy S21, so whether the overflow item appears there
 * at all is a device question that only a handset answers.
 *
 * ## 🔴 Sensitivity: the platform's suppression, and the three holes in it
 *
 * **There is deliberately NO receiver-side sensitivity check in this file.**
 * `ClipDescription.EXTRA_IS_SENSITIVE` is **unreachable on both capture paths**,
 * not merely hard:
 *
 * | path | `getClipData()` | flag reachable? |
 * |---|---|---|
 * | `ACTION_PROCESS_TEXT` | **null, always** — `createProcessTextIntentForResolveInfo` never calls `setClipData` | **No, by construction** |
 * | `ACTION_SEND`, platform-built | auto-created as `new ClipData(null, new String[]{getType()}, …)` — label null, **no extras** | **No** |
 * | `ACTION_SEND`, sender set its own | the sender's | mechanically yes — but `migrateExtraStreamToClipData` is `@hide`, so source, not contract |
 *
 * A guard that can only fire when a sender voluntarily attaches its own
 * `ClipData` via an `@hide` path the platform never exercises has **no
 * reachable case**, and its presence is the harm: four lines that look like a
 * sensitivity guard are what would license a README sentence claiming this
 * feature honours the flag. `RULES.md` is explicit that a guard must be shown
 * reachable, not merely breakable. (The sharesheet carries no sensitivity
 * signal at all: zero hits for `SENSITIVE` in `Intent.java`, zero files in the
 * Android 14+ `IntentResolver`, confirmed with a positive control —
 * `EXTRA_TEXT` matched five files. Also ruled out, each checked:
 * `TYPE_TEXT_VARIATION_PASSWORD`, `setIsCredential`/`android:isCredential`,
 * `autofillHints`, `importantForAutofill`, `setFilterTouchesWhenObscured`.)
 *
 * 🔴 **What actually protects the path is upstream and is not ours.** AOSP
 * suppresses the whole selection menu on a masked password field:
 *
 * ```
 * TextView.canProcessText() -> canShare() -> canCopy()
 *     -> if (hasPasswordTransformationMethod()) return false;
 * Editor: if (mTextView.canProcessText()) { … onInitializeMenu(menu); }
 * ```
 *
 * On a real password box there is no Send-to-Dicta, no Cut, no Copy, no Share
 * and no Select-All.
 *
 * 🔴 **Three holes, all from reading that predicate literally:**
 *
 *  1. It tests the **transformation**, not `inputType`.
 *     `TYPE_TEXT_VARIATION_VISIBLE_PASSWORD`, or a password field whose reveal
 *     toggle removed the transformation, is **unprotected**.
 *  2. `getId() == View.NO_ID` also suppresses the menu — so the item's
 *     **absence is not evidence** of a password field.
 *  3. `TextView`/`Editor` only. Compose, WebView and custom text surfaces own
 *     their own menus. (Chromium does skip password fields, by its own path.)
 *
 * 🔴 **So nothing in this app may claim it "honours `EXTRA_IS_SENSITIVE`."** On
 * the PROCESS_TEXT path there is nothing to honour. The real mitigations are
 * the backup bucket's 30-day expiry window and day-one delete, and neither may
 * be dropped without a replacement.
 */
class CaptureActivity : Activity() {

    /**
     * Where a capture goes. `null` means a real [ClipOutboxStore].
     *
     * A seam because the production store roots itself under
     * `noBackupFilesDir`, which is process-wide under Robolectric — so a test
     * driving this activity would otherwise be a test of the filesystem and at
     * the mercy of what the previous test left behind. Assigned before
     * `onCreate` when a test wants that.
     */
    internal var outbox: ClipOutbox? = null

    /*
     * 🔴 DELETED with it: `internal var nowMs: () -> Long`.
     *
     * Its only job was to stamp `ClipOutbox.Pending.capturedAtMs`, which D5
     * removed as a field nothing read — so the clock seam became a seam with
     * nothing behind it, which is exactly the dead-seam shape D3 removed from
     * `ClipPickerActivity`. Removing the field and keeping its injector would
     * have left the next reader looking for the timestamp it implies.
     */

    /**
     * 🔴 No `setContentView`, no `setResult(RESULT_OK)`, and `finish()` on
     * every path including the failure one. An activity that stayed up would
     * be an invisible window over the app the user is reading, and one that
     * returned `RESULT_OK` would edit their text — see the class kdoc.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 🔴 RESULT_CANCELED explicitly rather than by omission. The default is
        // already RESULT_CANCELED, so this line changes nothing at runtime —
        // it is here so that a future edit adding a result has to delete a
        // line carrying the reason, rather than add one to an activity that
        // appears to have no opinion.
        setResult(RESULT_CANCELED)
        capture(textOf(intent))
        finish()
    }

    /**
     * The selected text from either filter, or null when there is none.
     *
     * 🔴 `getCharSequenceExtra`, never `getStringExtra` — see the class kdoc:
     * the string reader returns null for a `Spanned`, which is what a styled
     * selection is, so the capture would silently do nothing for the richest
     * selections users make.
     *
     * `EXTRA_PROCESS_TEXT` first, then `EXTRA_TEXT`, because the two filters are
     * disjoint in practice and an intent carrying both is a sender doing
     * something unusual — in which case the process-text extra is the one the
     * selection toolbar put there.
     *
     * Blank is treated as absent: a selection of pure whitespace carries
     * nothing worth moving between machines, and the service refuses an empty
     * body with a 400 (measured).
     */
    private fun textOf(source: Intent?): CharSequence? {
        val intent = source ?: return null
        val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
            ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
            ?: return null
        return text.takeIf { it.isNotBlank() }
    }

    /**
     * Queues [text] **synchronously**, and reports the truth either way.
     *
     * 🔴 **It must not POST.** The upload is always deferred: an executor in a
     * finishing activity's process can be killed before the socket completes,
     * and the capture is then silently lost after the user was told it was
     * taken. See [ClipOutbox]'s kdoc for the two ledgers that also forbid this
     * file from probing reachability first.
     *
     * 🔴 **A failed write SURFACES.** This is the opposite posture from
     * `SnipUsageStore`, which swallows every write failure because *"a usage
     * file is an OPTIMISATION, never a gate"*. The outbox is the only copy of
     * the user's capture, and by the time a toast appears the selection it came
     * from is gone — so "Saved" over a failed write is a capture the user
     * believes they have.
     *
     * The toast says only that something was taken. 🔴 It deliberately does
     * **not** echo the text: `@string/snip_copied` is worded to avoid putting a
     * copied body on screen, and a capture is more likely than a snippet to be
     * material the user would not want rendered over the app they are in.
     */
    private fun capture(text: CharSequence?) {
        if (text == null) {
            toast(R.string.clip_capture_nothing)
            return
        }
        try {
            (outbox ?: ClipOutboxStore(this)).append(text.toString())
            toast(R.string.clip_capture_queued)
        } catch (_: ClipOutbox.TooLarge) {
            // 🔴 Its own toast. "Dicta could not save that capture" over an
            // oversized selection is true and useless — the operator would
            // retry the identical selection. This is also the ONLY moment the
            // selection still exists on screen, which is the whole argument
            // for bounding at capture time rather than at flush time; see
            // [ClipOutboxStore.MAX_BODY_CHARS].
            toast(R.string.clip_capture_too_large)
        } catch (_: Exception) {
            toast(R.string.clip_capture_failed)
        }
    }

    private fun toast(res: Int) {
        runCatching { Toast.makeText(this, res, Toast.LENGTH_SHORT).show() }
    }

    companion object {
        /**
         * The capture activity's own task affinity. **Must** equal the
         * `android:taskAffinity` on `CaptureActivity` in `AndroidManifest.xml`,
         * and must differ from all three picker/card affinities.
         *
         * 🔴 It needs one even though it is `noHistory` and
         * `excludeFromRecents`. The platform starts it with its own flags from
         * a foreign process, and an undeclared affinity inherits
         * `MainActivity`'s — which would bring Dicta's whole task forward over
         * the app the user is selecting text in. That is issue #11 reached by a
         * fifth route, and it is the one place here where "it is invisible so
         * it cannot matter" is wrong.
         */
        const val CAPTURE_TASK_AFFINITY: String = "dev.zacx.dicta.capture"
    }
}

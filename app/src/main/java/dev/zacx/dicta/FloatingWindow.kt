package dev.zacx.dicta

import android.app.Activity
import android.view.Gravity
import android.view.WindowManager

/**
 * 🔴 The ONE owner of a floating overlay activity's window geometry.
 *
 * ## Why this is an object and not a method on each activity
 *
 * It was a method on one activity, and the second floating surface shipped
 * without it. `DictateActivity.applyFloatingWindowSize()` existed from the
 * overlay redesign; `SnipPickerActivity` arrived with issue #38, inherited the
 * same `@style/Theme.Dicta.Overlay`, and never called anything equivalent — so
 * on a real Galaxy S21 the picker rendered at roughly a FIFTH of the screen
 * width, truncating every row label to about six characters. A floating window
 * is `WRAP_CONTENT`, so `match_parent` on the layout root matches *the window*
 * and the window takes its width from its widest `wrap_content` child, which in
 * the picker is the one-word `SNIPPETS` heading.
 *
 * Copying the method into the picker would have made two copies of a rule that
 * must agree, and `claude/RULES.md` names the consequence: a predicate
 * duplicated across call sites regenerates the same bug at every site. One
 * owner, and `FloatingOverlaySeamLedgerTest` derives the set of activities that
 * must call it from the **manifest's `android:theme`**, so a third floating
 * surface is red until it is wired in.
 *
 * ## Why the cap is in code and not in the theme
 *
 * (Carried verbatim in substance from `DictateActivity`'s kdoc, which is where
 * this argument was written down and the only place it exists.)
 *
 * The dialog window is `WRAP_CONTENT`, so without a cap the decor is measured
 * at `AT_MOST(displayWidth)` and any content that wants to be wider than the
 * screen — a long hint, a `match_parent` row — simply clamps to the full
 * display width, which is how the "it looks like a full app screen" report
 * happens. The only theme attributes that can make the window *narrower* than
 * its content (`windowFixedWidthMinor` / `windowFixedWidthMajor`) are private
 * platform attributes and fail aapt2 linking from an app theme;
 * `windowMinWidth*` can only widen. `Window.setLayout` is the public
 * equivalent.
 *
 * ## 🔴 Call it from `onCreate`, AFTER `setContentView`
 *
 * Two properties, both load-bearing:
 *
 *  * `PhoneWindow.generateLayout()` resets a floating window to
 *    `WRAP_CONTENT x WRAP_CONTENT` while inflating, so a call before
 *    `setContentView` is silently undone;
 *  * being applied on **every create** is what makes a rotation re-read the
 *    orientation-qualified [R.fraction.overlay_width_fraction] — 92% default,
 *    62% `values-land`, 70% `values-sw600dp`, 50% `values-sw600dp-land`. A
 *    one-shot application somewhere longer-lived would keep the portrait
 *    fraction in landscape.
 */
internal object FloatingWindow {

    /**
     * Caps [activity]'s window at [R.fraction.overlay_width_fraction] of the
     * display width and centres it. Height stays `WRAP_CONTENT`: a fixed height
     * would either clip the content or leave the card tall and empty.
     *
     * A non-positive width is left alone rather than applied — a zero-width
     * window is an invisible surface, and that is a worse failure than the
     * uncapped one this exists to fix.
     */
    fun applySize(activity: Activity) {
        val metrics = activity.resources.displayMetrics
        val fraction = activity.resources.getFraction(R.fraction.overlay_width_fraction, 1, 1)
        val width = (metrics.widthPixels * fraction).toInt()
        if (width <= 0) return
        activity.window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
        activity.window.setGravity(Gravity.CENTER)
    }
}

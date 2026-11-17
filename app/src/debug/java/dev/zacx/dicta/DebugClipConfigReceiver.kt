package dev.zacx.dicta

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The thin **debug-only** wrapper over [ClipConfigIngress] and
 * [ClipDiagnostics]: set the shared clipboard's configuration, and read a
 * machine-readable status, without driving a single pixel.
 *
 * ## 🔴 WHY THIS FILE IS IN `src/debug` AND NOT IN `src/main`
 *
 * `ZacxDev/dicta` is a **public** repository and this surface writes a real
 * credential. An exported component in `src/main` would exist in every release
 * APK on the operator's handset, and `BuildConfig.DEBUG` gating would be a
 * *runtime* check on a component the release manifest still declares — i.e. an
 * entry point any installed app can still address, whose safety depends on one
 * `if`. Declaring it only in `src/debug/AndroidManifest.xml` means the release
 * build has **no such component at all**: there is nothing to gate, nothing to
 * get wrong, and nothing for an attacker to find.
 *
 * Operator decision, taken before this was written.
 * `DebugConfigSurfaceManifestTest` is the guard, and it is mutation-tested —
 * the declaration was moved into `src/main/AndroidManifest.xml` and the guard
 * was watched to fail with its own message.
 *
 * ## ⚠ Residual exposure in a DEBUG build, stated rather than discovered
 *
 * This receiver is `exported="true"`, because `adb shell am broadcast` is the
 * whole point and an unexported receiver cannot be reached that way. So on a
 * **debug** build any installed app can address it. What that buys an
 * attacker is bounded and worth stating precisely:
 *
 *  * it can **set** a clip token or base URL — a denial of service against
 *    the shared clipboard, and a way to point captures at a store it controls;
 *  * it can **turn the floating bubble on or off** (`bubble_enabled`). Stated
 *    rather than left to be discovered: it is a nuisance and a denial of
 *    service, not an escalation — turning it on requires the overlay
 *    permission the user has already granted this app and raises nothing the
 *    user has not already seen, and the bubble takes no action on its own. It
 *    grants no new read.
 *  * it **cannot read** the token. [CONFIGURE] never echoes what it was given,
 *    and [DIAGNOSE]'s readout carries `tokenDecrypts` as a boolean and no
 *    field that could hold a byte of the credential ([ClipDiagnostics] is
 *    explicit that this is a property of the type).
 *
 * A signature-level `android:permission` was considered and rejected: `adb
 * shell` runs as the shell uid and holds no app signature permission, so it
 * would close the only door this exists to open. The gate is the build type.
 *
 * 🔴 **Accepted by the operator, explicitly, as designed** — round 0 of this
 * PR's audit put the write exposure to them as a blocking premise (Q1) rather
 * than assuming it. The one change they required: this was documented only
 * here and in the `src/debug` manifest, both inside the source set a reviewer
 * auditing release safety never opens, so it is **also stated in the pull
 * request body**. A security property only the author can find is one nobody
 * reviews.
 *
 * ## Usage
 *
 * ```
 * adb shell am broadcast -f 0x00000020 \
 *   -a dev.zacx.dicta.debug.DIAGNOSE \
 *   -n dev.zacx.dicta/.DebugClipConfigReceiver
 *
 * adb shell am broadcast -f 0x00000020 \
 *   -a dev.zacx.dicta.debug.CONFIGURE \
 *   -n dev.zacx.dicta/.DebugClipConfigReceiver \
 *   --es clip_base_url 'http://10.42.0.10:8139' --es clip_token "$TOKEN"
 *
 * adb shell am broadcast -f 0x00000020 \
 *   -a dev.zacx.dicta.debug.CONFIGURE \
 *   -n dev.zacx.dicta/.DebugClipConfigReceiver \
 *   --es bubble_enabled true
 * ```
 *
 * 🔴 `--es` and **not** `--ez`: every extra on this door is read with
 * `getStringExtra`, so a genuinely-boolean extra would arrive as `null` and be
 * silently ignored — "leave it alone". `bubble_enabled` therefore takes a
 * string that [BubbleEnabled.parseSpelling] reads
 * (`true`/`false`/`1`/`0`/`on`/`off`/`yes`/`no`), and anything else is refused
 * rather than guessed at.
 *
 * The outcome comes back as the broadcast's **result data**, which
 * `am broadcast` prints — so nothing is logged. 🔴 `-f 0x00000020`
 * (`FLAG_INCLUDE_STOPPED_PACKAGES`) is not optional: without it a broadcast to
 * an app that has not been launched since boot is silently dropped, and a
 * dropped broadcast is indistinguishable from a receiver that is not declared.
 *
 * 🔴 **Nothing here is logged, in either direction.** No `Log.*` call exists in
 * this file; the only output is the result data, which goes to the caller that
 * asked for it. A token in logcat would be readable by anything holding
 * `READ_LOGS` and would survive in a bug report.
 */
class DebugClipConfigReceiver : BroadcastReceiver() {

    /**
     * 🔴 Synchronous disk IO on the main thread, deliberately, and bounded:
     * the preferences read is a few hundred bytes and the outbox read is a
     * small JSON file. The alternative is `goAsync()`, whose `PendingResult`
     * is one more thing to get wrong in a surface whose only caller is `adb`
     * on a debug build. If this ever grows a read that is not bounded, that
     * trade changes.
     */
    override fun onReceive(context: Context, intent: Intent) {
        // 🔴 The result data IS the output — there is no logging in this file.
        // `setResultData` on a non-ordered broadcast logs and returns rather
        // than throwing, but `am broadcast` sends an ordered one, so the caller
        // gets the answer printed. Wrapped because a framework that changes its
        // mind about that must not crash the app under diagnosis.
        runCatching { resultData = answer(context, intent) }
    }

    /**
     * The whole of this receiver's behaviour, as a function.
     *
     * 🔴 Separated from [onReceive] so it can be driven by a unit test:
     * `setResultData` needs a pending ordered broadcast, so a test that went
     * through `onReceive` would be testing the framework's result plumbing and
     * would be unable to read the answer at all. CI runs no emulator, so a
     * branch that is only reachable through a real broadcast is a branch the
     * gate cannot see.
     */
    internal fun answer(context: Context, intent: Intent): String =
        when (intent.action) {
            ACTION_CONFIGURE -> configure(context, intent)
            ACTION_DIAGNOSE -> diagnose(context)
            // An action this receiver does not implement is NOT silence: a
            // caller that mistypes it would otherwise watch nothing happen and
            // have no way to tell that from a dropped broadcast.
            else -> "rejected: unknown action ${intent.action}"
        }

    /**
     * Turns the extras into a [ClipConfigIngress.Request] and applies it.
     *
     * 🔴 All the deciding happens in [ClipConfigIngress], which is plain
     * Kotlin and therefore unit-testable — CI runs no emulator, so a rule
     * written *here* is a rule the gate cannot see. This method is the parse
     * and nothing else.
     */
    private fun configure(context: Context, intent: Intent): String {
        val request = ClipConfigIngress.parse { key -> intent.getStringExtra(key) }
        val s = surfaces?.invoke(context) ?: production(context)
        return ClipConfigIngress
            .apply(request, clip = s.clip, stt = s.stt, bubble = s.bubble)
            .message
    }

    /**
     * The readout.
     *
     * 🔴 `ClipOutboxStore` is constructed HERE rather than inside
     * [ClipDiagnostics], so that object stays Android-free and off
     * `ClipSeamLedgerTest.onlyTheDeclaredFilesTouchTheCaptureQueue`. This file
     * is ON that ledger — the scan was widened to this source set in the same
     * commit, because a reader of the capture queue that the ledger cannot see
     * is exactly the hole it exists to close. It only ever calls `pending()`.
     */
    private fun diagnose(context: Context): String {
        val s = surfaces?.invoke(context) ?: production(context)
        // 🔴 INSTALLED BEFORE READING, which is the whole of round 0's Q2 on
        // this side of the seam. This is usually the FIRST thing to run in a
        // fresh process — `adb shell am broadcast` to an app nobody has
        // opened — so without the hydrate, the one call that exists to explain
        // a failure would report `lastFlush: none` for a queue that has been
        // failing to drain for days. That `none` is the bug, not the answer.
        ClipDiagnostics.install(context)
        // 🔴 Spelled as ONE unbroken `ClipDiagnostics.readout(` on purpose:
        // `ClipSeamLedgerTest`'s install-site ledger matches that literal
        // substring, and splitting the receiver onto its own line drops this
        // file off a ledger that is asserted to be complete. Measured — it
        // went red exactly that way while this parameter was being added.
        val readout = ClipDiagnostics.readout(s.clip, s.outbox, s.bubble.isOn())
        return readout.render()
    }

    /**
     * What this receiver reads and writes, as one injectable bundle.
     *
     * 🔴 A seam rather than three direct `create` calls, and the reason is a
     * measured constraint rather than taste: the production [ClipSettings]
     * carries a [KeystoreCipher], `AndroidKeyStore` has **no Robolectric
     * implementation** (see [SecretCipher]'s kdoc), and CI runs no emulator. So
     * without this, every test of this surface would exercise a cipher that
     * throws, and the one gate that runs could only ever observe the failure
     * path. `ClipPickerActivity.remote` and `SettingsActivity.snippets` are the
     * same pattern for the same reason.
     */
    internal class Surfaces(
        val clip: ClipSettings,
        val stt: SttSettings,
        val outbox: ClipOutbox,
        /**
         * The bubble flag's owner. 🔴 A [BubbleEnabled.Control] rather than a
         * `Context` for the same reason as the rest of this bundle:
         * [BubbleEnabled.turnOn] calls `Settings.canDrawOverlays` and
         * `startForegroundService`, so a test that could not substitute it
         * would be asserting Robolectric's shadows instead of the door's rules.
         */
        val bubble: BubbleEnabled.Control,
    )

    /** Overridden by a test; `null` means the real settings and the real queue. */
    internal var surfaces: ((Context) -> Surfaces)? = null

    /**
     * 🔴 `ClipOutboxStore` is constructed HERE rather than inside
     * [ClipDiagnostics], so that object stays Android-free and unit-testable.
     * This file is on `ClipSeamLedgerTest.onlyTheDeclaredFilesTouchTheCaptureQueue`
     * — the scan was widened to this source set in the same commit, because a
     * reader of the capture queue that the ledger cannot see is exactly the
     * hole it exists to close. It only ever calls `pending()`, and
     * `DebugClipConfigReceiverTest.theReceiverNeverAPPENDSToOrREMOVESFromTheQueue`
     * is the behavioural half of that claim.
     */
    private fun production(context: Context) = Surfaces(
        clip = ClipSettings.create(context),
        stt = SttSettings.create(context),
        outbox = ClipOutboxStore(context),
        // 🔴 The ONLY mutator call this file makes, which is why it is on
        // BubbleEnabledWritersSeamTest's ledger as a declared writer.
        bubble = BubbleEnabled.control(context),
    )

    companion object {
        const val ACTION_CONFIGURE: String = "dev.zacx.dicta.debug.CONFIGURE"
        const val ACTION_DIAGNOSE: String = "dev.zacx.dicta.debug.DIAGNOSE"
    }
}

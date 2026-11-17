package dev.zacx.dicta

import org.robolectric.Robolectric

/**
 * Brings up the real [BubbleService] with its foreground promotion **refused**,
 * through the `foregroundPromoter` seam — Robolectric's `startForeground`
 * cannot be made to refuse.
 *
 * 🔴 **One copy, deliberately.** It was written in `BootRestoreTest` for the
 * boot/user split and is needed verbatim by `DebugClipConfigReceiverTest` for
 * the `CONFIGURE` door, which is the moment this repository's own rule says to
 * consolidate rather than copy: a fixture open-coded at N sites is the same
 * thing wrong at N−1 of them, and the way it would go wrong here is silent.
 * Forget one of the three `clearForTest` calls in a second copy and the next
 * test in that class inherits an overlay, a session or a microphone owner — a
 * green that is about leftovers.
 *
 * 🔴 It deliberately does **not** reset [BubbleEnabled]. The origin recorded by
 * whoever asked for the start — [BubbleEnabled.startService]'s `userAsked` — is
 * the input under test, and clearing it here would make every caller's
 * assertion a measurement of the fail-safe default instead.
 *
 * ⚠ What it cannot establish, for any caller: whether Android ever refuses the
 * promotion on a real start. No device has been attached to this project, and
 * Robolectric records the start and never refuses. Nor does it prove the
 * service instance the *platform* would create is the one the recorded origin
 * belongs to — it builds the service itself rather than letting the platform
 * create it from the recorded start request.
 */
internal fun refuseTheForegroundPromotion() {
    val controller = Robolectric.buildService(BubbleService::class.java)
    controller.get().foregroundPromoter = { false }
    runCatching { controller.create() }
    runCatching { controller.destroy() }
    OverlayVisibility.clearForTest()
    BubbleSessionBus.clearForTest()
    MicrophoneOwner.clearForTest()
}

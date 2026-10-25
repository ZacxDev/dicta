package dev.zacx.dicta

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.app.StatusBarManager
import android.provider.Settings
import android.widget.Button
import android.widget.CompoundButton
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * Setup screen. **Status and one switch — nothing configurable.**
 *
 * Three things only:
 *
 *  1. the permission rows (microphone, display-over-other-apps) with a *Grant*
 *     button where one is needed;
 *  2. the floating bubble's on/off switch, plus the *Add tile* prompt for the
 *     Quick Settings control that toggles the same flag from the shade;
 *  3. a link into [SettingsActivity], which owns everything configurable.
 *
 * ## 🔴 This screen can no longer start a recording
 *
 * It used to carry two start surfaces: a "Try dictation now" button that opened
 * the overlay (which auto-starts a capture), and a switch that posted a
 * persistent notification with a *Dictate* action. **Both are deleted.** The
 * floating bubble's tap is the only way to begin a recording — the operator's
 * decision, and `StartSurfaceLedgerTest` is the enumerated set that keeps it
 * that way.
 *
 * The notification's removal is not just a deletion: an `setOngoing(true)`
 * notification posted by an older build survives the upgrade, so
 * [TriggerNotificationCleanup] runs below. See its kdoc for why this is one of
 * only two call sites.
 */
class MainActivity : Activity() {

    private lateinit var micStatus: TextView
    private lateinit var overlayStatus: TextView
    private lateinit var bubbleSwitch: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // The backstop call site. The primary is PackageReplacedReceiver, which
        // does not need the user to come here at all; this covers a device that
        // does not deliver ACTION_MY_PACKAGE_REPLACED. Stateless and no-op once
        // there is nothing left to cancel.
        TriggerNotificationCleanup.run(this)

        micStatus = findViewById(R.id.mic_status)
        overlayStatus = findViewById(R.id.overlay_status)
        bubbleSwitch = findViewById(R.id.bubble_switch)

        findViewById<Button>(R.id.grant_mic_button).setOnClickListener {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
        }
        findViewById<Button>(R.id.grant_overlay_button).setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
        findViewById<Button>(R.id.add_tile_button).setOnClickListener { promptAddTile() }
        findViewById<Button>(R.id.settings_button).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        bubbleSwitch.setOnCheckedChangeListener(bubbleToggle)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val micGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        micStatus.text = getString(
            if (micGranted) R.string.status_granted else R.string.status_not_granted
        )

        val overlayGranted = Settings.canDrawOverlays(this)
        overlayStatus.text = getString(
            if (overlayGranted) R.string.status_granted else R.string.status_not_granted
        )

        bubbleSwitch.setOnCheckedChangeListener(null)
        // One predicate, shared with the Quick Settings tile's rendered state
        // and its click direction — see BubbleEnabled.isUp. This read used to
        // open-code `isOn && overlayGranted`, which is where the tile's click
        // drifted away from it.
        bubbleSwitch.isChecked = BubbleEnabled.isUp(this)
        bubbleSwitch.isEnabled = overlayGranted
        bubbleSwitch.setOnCheckedChangeListener(bubbleToggle)
    }

    /**
     * The switch's only behaviour, held in one field because
     * [refreshStatus] has to detach it before syncing `isChecked` from the
     * persisted flag and re-attach it afterwards. Two inline lambdas is how
     * the two copies drifted apart in the first place.
     *
     * 🔴 When the overlay permission is missing the switch is put back to
     * `false` — with the listener detached, or the assignment would re-enter
     * this lambda and attempt a `turnOff` for a bubble that never started. A
     * switch left ON with nothing running is the same lie as the one issue #14
     * reported.
     *
     * That is the only arm that reverts, because it is the only way
     * [BubbleEnabled.turnOn] can decline. A third arm once handled a
     * platform-refused foreground start; it was deleted as unreachable and has
     * no replacement rationale — [BubbleEnabled.startService] says why.
     */
    private val bubbleToggle = CompoundButton.OnCheckedChangeListener { _, checked ->
        if (!checked) {
            BubbleEnabled.turnOff(this)
            return@OnCheckedChangeListener
        }
        when (BubbleEnabled.turnOn(this)) {
            BubbleEnabled.Start.STARTED -> Unit
            BubbleEnabled.Start.NO_OVERLAY_PERMISSION -> {
                toast(R.string.need_overlay_first)
                revertBubbleSwitch()
            }
        }
    }

    private fun revertBubbleSwitch() {
        bubbleSwitch.setOnCheckedChangeListener(null)
        bubbleSwitch.isChecked = false
        bubbleSwitch.setOnCheckedChangeListener(bubbleToggle)
    }

    private fun toast(textRes: Int) {
        Toast.makeText(this, getString(textRes), Toast.LENGTH_SHORT).show()
    }

    private fun promptAddTile() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getSystemService(StatusBarManager::class.java).requestAddTileService(
                ComponentName(this, DictaTileService::class.java),
                getString(R.string.tile_label),
                Icon.createWithResource(this, R.drawable.ic_tile_mic),
                mainExecutor
            ) { result ->
                // 🔴 The result is the whole point. This callback used to
                // discard it and toast tile_added unconditionally, so
                // declining the system prompt was reported as a success — the
                // same shape of lie as the switch left ON with nothing
                // running. TileAddPrompt owns the nine-code mapping; see its
                // kdoc for why the decision is not inline here.
                Toast.makeText(
                    this,
                    getString(TileAddPrompt.messageForResult(result)),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        } else {
            Toast.makeText(this, getString(R.string.tile_add_manual), Toast.LENGTH_LONG)
                .show()
        }
    }

    companion object {
        private const val REQ_MIC = 2001
    }
}

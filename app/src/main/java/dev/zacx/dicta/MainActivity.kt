package dev.zacx.dicta

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
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
 * Setup screen: permission status, trigger toggles (bubble, notification),
 * Quick Settings tile prompt, and a "try it now" button.
 */
class MainActivity : Activity() {

    private lateinit var micStatus: TextView
    private lateinit var overlayStatus: TextView
    private lateinit var bubbleSwitch: Switch
    private lateinit var notifSwitch: Switch

    private val prefs by lazy {
        getSharedPreferences("dicta", Context.MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        micStatus = findViewById(R.id.mic_status)
        overlayStatus = findViewById(R.id.overlay_status)
        bubbleSwitch = findViewById(R.id.bubble_switch)
        notifSwitch = findViewById(R.id.notif_switch)

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
        findViewById<Button>(R.id.try_now_button).setOnClickListener {
            startActivity(Intent(this, DictateActivity::class.java))
        }

        bubbleSwitch.setOnCheckedChangeListener(bubbleToggle)
        notifSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(PREF_NOTIF, checked).apply()
            if (checked) showTriggerNotification() else cancelTriggerNotification()
        }
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
        bubbleSwitch.isChecked = BubbleEnabled.isOn(this) && overlayGranted
        bubbleSwitch.isEnabled = overlayGranted
        bubbleSwitch.setOnCheckedChangeListener(bubbleToggle)

        notifSwitch.setOnCheckedChangeListener(null)
        notifSwitch.isChecked = prefs.getBoolean(PREF_NOTIF, false)
        notifSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(PREF_NOTIF, checked).apply()
            if (checked) showTriggerNotification() else cancelTriggerNotification()
        }
    }

    /**
     * The switch's only behaviour, held in one field because
     * [refreshStatus] has to detach it before syncing `isChecked` from the
     * persisted flag and re-attach it afterwards. Two inline lambdas is how
     * the two copies drifted apart in the first place.
     *
     * 🔴 On a refusal the switch is put back to `false` — with the listener
     * detached, or the assignment would re-enter this lambda and attempt a
     * `turnOff` for a bubble that never started. A switch left ON after a
     * refused start is the same lie as the one issue #14 reported.
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
            BubbleEnabled.Start.REFUSED -> {
                toast(R.string.bubble_start_refused)
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
            ) { _ ->
                // The system shows its own add-tile prompt; just confirm the request.
                Toast.makeText(this, getString(R.string.tile_added), Toast.LENGTH_SHORT)
                    .show()
            }
        } else {
            Toast.makeText(this, getString(R.string.tile_add_manual), Toast.LENGTH_LONG)
                .show()
        }
    }

    private fun showTriggerNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                TRIGGER_CHANNEL,
                getString(R.string.trigger_channel),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        // Via DictateLaunch so the notification cannot drift from the bubble
        // and the tile — all three shared the issue #11 defect.
        val pi = DictateLaunch.pendingIntent(this)
        val notif = Notification.Builder(this, TRIGGER_CHANNEL)
            .setSmallIcon(R.drawable.ic_tile_mic)
            // Matches BubbleService.buildNotification(): the shade tints the
            // small icon and the app name from this, and it is the only styling
            // hook a platform notification offers.
            .setColor(getColor(R.color.dicta_accent))
            .setColorized(false)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(pi)
            .addAction(
                Notification.Action.Builder(null, getString(R.string.dictate), pi)
                    .build()
            )
            .setOngoing(true)
            .build()
        nm.notify(TRIGGER_NOTIF_ID, notif)
    }

    private fun cancelTriggerNotification() {
        getSystemService(NotificationManager::class.java).cancel(TRIGGER_NOTIF_ID)
    }

    companion object {
        private const val REQ_MIC = 2001
        private const val PREF_NOTIF = "notif_enabled"
        private const val TRIGGER_NOTIF_ID = 3001
        private const val TRIGGER_CHANNEL = "dicta_trigger"
    }
}

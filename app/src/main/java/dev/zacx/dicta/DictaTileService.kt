package dev.zacx.dicta

import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile — the floating bubble's on/off switch from anywhere.
 *
 * ## Why it is a toggle and no longer an overlay launcher
 *
 * It used to `startActivityAndCollapse` into [DictateActivity]. The overlay is
 * no longer a place a recording starts from, so "open the dictation overlay"
 * was becoming a tile that opens an empty card. The bubble is the thing worth
 * reaching from the shade, and a tile is a *stateful* control: it can show
 * whether the bubble is on, which a launcher never could.
 *
 * ## The two states a tile must not get wrong
 *
 * [onStartListening] previously hardcoded [Tile.STATE_INACTIVE], so even the
 * old tile's state was a constant rather than a reading. It now reflects
 * [BubbleEnabled], which is the same source of truth the app's switch and the
 * notification's Hide action use.
 *
 * Without `Display over other apps` the bubble cannot exist at all, so the tile
 * goes [Tile.STATE_UNAVAILABLE] and starts nothing. 🔴 A `TileService` has no
 * screen to toast on — a `Toast` posted from the collapsed shade is at best
 * invisible — so the reason is carried by the tile's own subtitle and content
 * description, which is the only text a tile owns.
 */
class DictaTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        render()
    }

    override fun onClick() {
        super.onClick()
        // 🔴 No overlay-permission check here on purpose, and it is not an
        // omission: [BubbleEnabled.turnOn] checks it itself, live, and returns
        // NO_OVERLAY_PERMISSION without writing the flag or starting anything.
        // A second copy of the predicate in this file was written first and
        // then deleted — mutating it could not make any test fail, because the
        // owner's check always won, and a guard that cannot be reached is a
        // guard nobody can trust. One rule, one place; `render()` below reads
        // the result back rather than assuming the click did anything.
        if (BubbleEnabled.isOn(this)) {
            BubbleEnabled.turnOff(this)
        } else {
            BubbleEnabled.turnOn(this)
        }
        render()
    }

    /**
     * Paints the tile from the persisted flag and the overlay permission —
     * never from an assumption about what the click did. [onClick] discards
     * [BubbleEnabled.turnOn]'s return value for that reason: `turnOn` declines
     * without writing anything when `canDrawOverlays` is false, so re-reading
     * the flag and the permission here covers that case without this file
     * holding a second copy of the predicate.
     *
     * 🔴 The wider justification this comment used to give — that `turnOn`
     * *clears* the flag again when the platform refuses the start — is gone
     * with the refusal path itself, which was deleted as unreachable
     * ([BubbleEnabled.startService] says why). Reading back is still the right
     * shape, but for the narrower reason above only. Do not reinstate the
     * larger claim.
     */
    private fun render() {
        val tile = qsTile ?: return
        val granted = Settings.canDrawOverlays(this)
        val on = granted && BubbleEnabled.isOn(this)
        tile.label = getString(R.string.tile_label)
        tile.state = when {
            !granted -> Tile.STATE_UNAVAILABLE
            on -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        val textRes = when {
            !granted -> R.string.tile_needs_overlay
            on -> R.string.tile_bubble_on
            else -> R.string.tile_bubble_off
        }
        tile.contentDescription = getString(textRes)
        // subtitle is API 29; below that the content description above is the
        // only text the tile carries, and TalkBack still reads it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(textRes)
        }
        tile.updateTile()
    }
}

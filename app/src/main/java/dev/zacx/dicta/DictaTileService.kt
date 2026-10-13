package dev.zacx.dicta

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile — the primary "trigger from anywhere" entry point.
 * A tap collapses the shade and opens the dictation overlay.
 */
class DictaTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.let { tile ->
            tile.label = getString(R.string.tile_label)
            tile.contentDescription = getString(R.string.tile_description)
            tile.state = Tile.STATE_INACTIVE
            tile.updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        // PendingIntent variant: the Intent-based overload is deprecated as of
        // API 34. The intent itself comes from DictateLaunch so this entry
        // point cannot drift from the bubble and the notification — see
        // issue #11, where all three carried the same defect.
        startActivityAndCollapse(DictateLaunch.pendingIntent(this))
    }
}

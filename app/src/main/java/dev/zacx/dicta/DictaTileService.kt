package dev.zacx.dicta

import android.app.PendingIntent
import android.content.Intent
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
        // PendingIntent variant: the Intent-based overload is deprecated as of API 34.
        val intent = Intent(this, DictateActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        startActivityAndCollapse(pi)
    }
}

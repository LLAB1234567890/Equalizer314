package com.bearinmind.equalizer314.audio

import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.annotation.RequiresApi
import com.bearinmind.equalizer314.R

/**
 * Quick Settings tile toggling global DynamicsProcessing without opening MainActivity. Mirrors the
 * power FAB:
 *   - EQ off → "EQ314 OFF" (inactive). Tap fires [EqService.ACTION_START_FROM_TILE], loading
 *     persisted bands from `eq_settings` SP and starting the global DP.
 *   - EQ on → "EQ314 ON" (active). Tap fires [EqService.ACTION_STOP], tearing down the DP, releasing
 *     per-session effects, stopping the foreground service.
 * Updates optimistically on click for instant feedback; next [onStartListening] re-syncs against the
 * persisted power-state pref in case the service start failed silently (e.g. no saved bands, fresh install).
 */
@RequiresApi(Build.VERSION_CODES.N)
class Eq314TileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        // Use the live in-process flag: the persisted pref drifts (MainActivity resets it to false on
        // every cold launch). EqService.isDpRunning is authoritative for whether the global DP is processing.
        val on = EqService.isDpRunning
        Log.d(TAG, "onStartListening — isDpRunning=$on")
        renderState(isOn = on)
    }

    override fun onClick() {
        super.onClick()
        // ACTION_START_FROM_TILE is a true toggle service-side: interprets isDpRunning live and either
        // starts the DP from persisted bands or tears down what's running. We fire and update optimistically.
        val turningOn = !EqService.isDpRunning
        Log.d(TAG, "onClick — turningOn=$turningOn (isDpRunning=${EqService.isDpRunning})")
        val intent = Intent(this, EqService::class.java)
            .setAction(EqService.ACTION_START_FROM_TILE)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            Log.d(TAG, "onClick — dispatched ${intent.action}")
        } catch (t: Throwable) {
            // FGS-from-tile is allowed on every API we target; if a future restriction fires, skip —
            // onStartListening re-syncs the tile next open.
            Log.w(TAG, "onClick — startService failed", t)
            return
        }
        renderState(isOn = turningOn)
    }

    companion object {
        private const val TAG = "Eq314Tile"
    }

    private fun renderState(isOn: Boolean) {
        val tile = qsTile ?: return
        tile.label = if (isOn) "EQ314 ON" else "EQ314 OFF"
        tile.state = if (isOn) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.icon = Icon.createWithResource(this, R.drawable.ic_nav_equalizer)
        // No subtitle — the label ("EQ314 ON" / "EQ314 OFF") says it all.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = ""
        }
        tile.updateTile()
    }
}

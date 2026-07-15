package com.bearinmind.equalizer314.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Watches the active audio output and emits a debounced `RouteChange(deviceKey, deviceLabel)` on
 * change. Owned by [EqService]. Detection via [AudioManager.registerAudioDeviceCallback] (API 23+);
 * also registers an [AudioManager.ACTION_AUDIO_BECOMING_NOISY] receiver so abrupt pulls (yanked
 * headphones, BT out of range) recompute even when the device-removed callback lags.
 *
 * Active-output picker: highest-priority connected output tracked by [DeviceIdentity]
 * (BT > USB > wired > speaker) — matches Android's STREAM_MUSIC routing default. Debounce: BT A2DP
 * flaps during connect/handover, so coalesce with a 400 ms postDelayed window.
 */
class AudioRoutingMonitor(
    private val context: Context,
) {

    data class RouteChange(val key: String, val label: String)

    /** Listener fires on the main thread after the debounce window. */
    var onRouteChange: ((RouteChange) -> Unit)? = null

    /** Fires (main thread) for every tracked output device on connect, and once at start-up for
     *  everything already connected. Populates the "seen devices" list so a device shows on plug-in,
     *  not only once routed to. */
    var onDeviceSeen: ((key: String, label: String) -> Unit)? = null

    /** Fires (debounced, main thread) on every device add/remove regardless of active-sink key change.
     *  Unlike [onRouteChange] (which short-circuits same-key), lets callers react to internal route /
     *  sample-rate changes DynamicsProcessing must track. Matches Poweramp's `e80.java`
     *  `AudioDeviceCallback` → `s90.java:377-400` per-session rebuild. */
    var onRouteRebuild: (() -> Unit)? = null

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private val debounceRunnable = Runnable { recomputeAndEmit() }
    private var lastEmittedKey: String? = null
    private var registered = false

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            // Remember every tracked output on appearance (not just when active) so a freshly
            // plugged-in device shows in the Audio Output screen immediately.
            addedDevices?.forEach { reportSeen(it) }
            schedule()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            schedule()
        }
    }

    private fun reportSeen(info: AudioDeviceInfo) {
        if (!info.isSink) return
        val key = DeviceIdentity.keyOf(info) ?: return
        val label = DeviceIdentity.labelOf(info)
        onDeviceSeen?.invoke(key, label)
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Becoming noisy → routing about to flip back to speaker. Recompute for a head-start on
            // the device-removed callback that usually follows.
            schedule()
        }
    }

    fun start() {
        if (registered) return
        audioManager.registerAudioDeviceCallback(deviceCallback, handler)
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(noisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(noisyReceiver, filter)
        }
        registered = true
        // Kick once immediately so a cold-start with a device already
        // routed emits a RouteChange.
        schedule()
    }

    fun stop() {
        if (!registered) return
        handler.removeCallbacks(debounceRunnable)
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        runCatching { context.unregisterReceiver(noisyReceiver) }
        registered = false
    }

    private fun schedule() {
        handler.removeCallbacks(debounceRunnable)
        handler.postDelayed(debounceRunnable, DEBOUNCE_MS)
    }

    private fun recomputeAndEmit() {
        // Always notify rebuild listeners — sample-rate / internal route
        // changes can happen without the active-sink key changing.
        onRouteRebuild?.invoke()

        val active = pickActiveOutput() ?: return
        val key = DeviceIdentity.keyOf(active) ?: return
        val label = DeviceIdentity.labelOf(active)
        if (key == lastEmittedKey) return
        lastEmittedKey = key
        Log.d(TAG, "Active output → $key ($label)")
        onRouteChange?.invoke(RouteChange(key, label))
    }

    /** Among all connected output sinks, pick the highest-priority one
     *  that [DeviceIdentity] tracks. Returns null if none are tracked
     *  (e.g. only HDMI/cast is connected). */
    fun pickActiveOutput(): AudioDeviceInfo? {
        val all = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        var best: AudioDeviceInfo? = null
        var bestPri = 0
        for (d in all) {
            if (!d.isSink) continue
            DeviceIdentity.keyOf(d) ?: continue   // skips HFP/SCO/HDMI/etc.
            val p = DeviceIdentity.priority(d)
            if (p > bestPri) {
                bestPri = p
                best = d
            }
        }
        return best
    }

    companion object {
        private const val TAG = "AudioRoutingMonitor"
        private const val DEBOUNCE_MS = 400L
    }
}

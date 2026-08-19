package com.bearinmind.equalizer314

import android.app.Application
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import androidx.appcompat.app.AppCompatDelegate
import com.bearinmind.equalizer314.audio.PlaybackListenerService

/** Applies the saved light/dark theme before any activity inflates, so every screen
 *  comes up in the right palette on cold start. Dark is default; the pref is read raw
 *  (not via EqPreferencesManager) to keep startup free of that class's migration work. */
class EqApp : Application() {

    override fun onCreate() {
        super.onCreate()

        val light = getSharedPreferences("eq_settings", MODE_PRIVATE)
            .getBoolean("lightTheme", false)

        AppCompatDelegate.setDefaultNightMode(
            if (light) {
                AppCompatDelegate.MODE_NIGHT_NO
            } else {
                AppCompatDelegate.MODE_NIGHT_YES
            }
        )

        // TV Mode: app-wide screen tracking (peer nav-follow) +
        // remote-controlled touch lock on every activity.
        com.bearinmind.equalizer314.remote.RemoteScrim.install(this)

        // Ask Android/ColorOS to bind the notification listener again.
        // This is required for session-based playback detection.
        NotificationListenerService.requestRebind(
            ComponentName(this, PlaybackListenerService::class.java)
        )
    }
}

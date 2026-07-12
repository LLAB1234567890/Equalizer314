package com.bearinmind.equalizer314

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

/** Applies the saved light/dark theme choice before any activity
 *  inflates, so every screen (including deep-linked ones) comes up in
 *  the right palette on a cold start. Dark is the default — the pref is
 *  read raw here instead of through EqPreferencesManager to keep app
 *  startup free of that class's migration work. */
class EqApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val light = getSharedPreferences("eq_settings", MODE_PRIVATE)
            .getBoolean("lightTheme", false)
        AppCompatDelegate.setDefaultNightMode(
            if (light) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES
        )
        // TV Mode: app-wide screen tracking (peer nav-follow) + the
        // remote-controlled touch lock on every activity.
        com.bearinmind.equalizer314.remote.RemoteScrim.install(this)
    }
}

package com.bearinmind.equalizer314

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.bearinmind.equalizer314.state.EqPreferencesManager
import com.bearinmind.equalizer314.state.EqStateManager

class ExperimentalActivity : AppCompatActivity() {

    private lateinit var eqPrefs: EqPreferencesManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_experimental)

        val root = findViewById<android.view.View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = insets.top, bottom = insets.bottom)
            WindowInsetsCompat.CONSUMED
        }

        eqPrefs = EqPreferencesManager(this)

        findViewById<android.widget.ImageButton>(R.id.backButton).setOnClickListener { finish() }

        setupDpBandCount()
        setupMaxEqBands()
        setupHideNotification()
        setupFrameSlider()
        setupInterleave()

        // Hide the legacy "Experimental DP Engine" switch row — the
        // experimental path is now the only path. Keeping the view
        // hidden (rather than removing the layout XML) preserves the
        // surrounding card structure for the remaining rows.
        findViewById<android.view.View>(R.id.expDpModeSwitch)
            ?.let { switch ->
                (switch.parent as? android.view.View)?.visibility = android.view.View.GONE
            }
    }

    // Gain Compensation (auto-gain) graduated out of Experimental — it now
    // lives as a real, enabled "Auto-Gain" card on the main settings screen
    // (on by default; see EqPreferencesManager.getAutoGainEnabled).
    // DP Band Count is now read-only: the converter always uses the full
    // Wavelet band table (ParametricToDpConverter.numBands), so we just show
    // that number rather than a slider that didn't actually change anything.
    private fun setupDpBandCount() {
        findViewById<android.widget.TextView>(R.id.expDpBandCountValue).text =
            com.bearinmind.equalizer314.dsp.ParametricToDpConverter.numBands.toString()
    }

    // Experimental "Add more EQ bands" toggle (issue #31). On → cap 64,
    // off → default 16. Updates the live cap so it takes effect on the next
    // band add / EQ-screen interaction.
    private fun setupMaxEqBands() {
        val switch = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.expMaxBandsSwitch)
        switch.isChecked = eqPrefs.getMaxEqBands() > 16
        switch.setOnCheckedChangeListener { _, isChecked ->
            val cap = if (isChecked) EqStateManager.ABSOLUTE_MAX_BANDS else 16
            eqPrefs.saveMaxEqBands(cap)
            EqStateManager.MAX_BANDS = cap
        }
    }

    // Issue #26: DP FFT window ("EQ precision"). One pref (dpFrameMs),
    // one 4-stop slider over the engine's useful power-of-two block sizes
    // (2048/4096/8192/16384 samples). Longer windows render the bass EQ
    // more faithfully (finer FFT bins) at the cost of audio delay --
    // REW-measured: a 12 Hz-wide bass filter is erased at 43 ms, renders
    // within 0.2 dB at 341 ms. Takes effect on the next EQ power cycle
    // (the window is baked in at DynamicsProcessing creation).
    private val frameRungsMs = floatArrayOf(40f, 80f, 160f, 320f)
    private fun currentFrameRung(): Int {
        val ms = eqPrefs.getDpFrameMs()
        var best = 1; var bd = Float.MAX_VALUE
        for (i in frameRungsMs.indices) {
            val d = kotlin.math.abs(frameRungsMs[i] - ms)
            if (d < bd) { bd = d; best = i }
        }
        return best
    }

    private fun syncFrameUi() {
        val idx = currentFrameRung()
        findViewById<com.google.android.material.slider.Slider>(R.id.expFrameSlider).value = idx.toFloat()
        com.bearinmind.equalizer314.audio.DynamicsProcessingManager.frameDurationMs = frameRungsMs[idx]
    }

    private fun setupFrameSlider() {
        val slider = findViewById<com.google.android.material.slider.Slider>(R.id.expFrameSlider)
        slider.addOnChangeListener { _, v, fromUser ->
            if (fromUser) {
                eqPrefs.saveDpFrameMs(frameRungsMs[v.toInt().coerceIn(0, frameRungsMs.size - 1)])
                syncFrameUi()
            }
        }
        // Recycle the live DP only when the finger lifts — one rebuild per
        // adjustment, not one per rung dragged across.
        slider.addOnSliderTouchListener(object : com.google.android.material.slider.Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) {}
            override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) {
                requestDpRecycle()
            }
        })
        syncFrameUi()
    }

    /** Ask EqService to rebuild the live DP so creation-time settings
     *  (interleave, DP Latency Window) apply immediately. No-op when the
     *  EQ is off. The service toasts + broadcasts so MainActivity's power
     *  FAB echoes the off→on cycle. */
    private fun requestDpRecycle() {
        try {
            startService(
                android.content.Intent(this, com.bearinmind.equalizer314.audio.EqService::class.java)
                    .setAction(com.bearinmind.equalizer314.audio.EqService.ACTION_RECYCLE_DP)
            )
        } catch (_: Exception) {}
    }

    // Pre+Post EQ interleave (issue #26 follow-up): second staircase on the
    // DP's Post-EQ stage, cutoffs offset half a stair — 256 effective stairs.
    // Baked in at DP creation, so flipping the switch asks EqService to
    // recycle the live DP — no manual power cycle needed.
    private fun setupInterleave() {
        val switch = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.expInterleaveSwitch)
        switch.isChecked = eqPrefs.getDpInterleave()
        switch.setOnCheckedChangeListener { _, isChecked ->
            eqPrefs.saveDpInterleave(isChecked)
            com.bearinmind.equalizer314.audio.DynamicsProcessingManager.interleaveEnabled = isChecked
            requestDpRecycle()
        }
    }

    // Issue #58: hide the foreground-service notification while the EQ is off.
    private fun setupHideNotification() {
        val switch = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.expHideNotifSwitch)
        switch.isChecked = eqPrefs.getHideNotificationWhenOff()
        switch.setOnCheckedChangeListener { _, isChecked ->
            eqPrefs.setHideNotificationWhenOff(isChecked)
            // Apply immediately to the running service.
            try {
                startService(
                    android.content.Intent(this, com.bearinmind.equalizer314.audio.EqService::class.java)
                        .setAction(com.bearinmind.equalizer314.audio.EqService.ACTION_REFRESH_NOTIFICATION)
                )
            } catch (_: Exception) {}
        }
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
    }
}

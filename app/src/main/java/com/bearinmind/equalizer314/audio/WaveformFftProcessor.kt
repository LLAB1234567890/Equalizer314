package com.bearinmind.equalizer314.audio

import kotlin.math.*

/**
 * Processes raw waveform bytes from Visualizer.getWaveForm() into a dB spectrum for display.
 * Instead of Visualizer.getFft() (pre-cooked 8-bit, no windowing control), we window + zero-pad
 * FFT the raw waveform ourselves — much better despite the same 8-bit source:
 * - Hann window eliminates spectral leakage
 * - Zero-padding 1024→4096 gives 4× finer bin spacing
 * - Float-precision FFT vs system's 8-bit internal FFT
 * - Proper normalization
 * 8-bit dynamic range (~48 dB) is the ceiling but adequate here (Pro-Q's visible range is ~60 dB).
 */
class WaveformFftProcessor(
    val fftSize: Int = 4096,   // zero-padded output size
    private val sampleRate: Int = 48000
) {
    // Hann window, lazily built on first use with the actual capture size (typically 1024)
    private var hannWindow: FloatArray? = null
    private var lastCaptureSize: Int = 0

    // Reusable FFT buffers (avoid allocation per frame)
    private val fftReal = FloatArray(fftSize)
    private val fftImag = FloatArray(fftSize)

    // Output: magnitude in dB for each bin (fftSize/2 bins)
    val binCount: Int get() = fftSize / 2
    val binWidthHz: Float get() = sampleRate.toFloat() / fftSize

    /**
     * Process raw waveform bytes → dB spectrum.
     *
     * @param waveform Raw bytes from Visualizer.getWaveForm().
     *                 Unsigned 8-bit: 0–255, center at 128.
     * @return FloatArray of dB values for fftSize/2 bins.
     *         0 dB = full-scale sine wave peak.
     *         Typical music content: -5 to -45 dB.
     */
    fun process(waveform: ByteArray): FloatArray {
        val captureSize = waveform.size

        // Build/rebuild Hann window if capture size changed
        if (captureSize != lastCaptureSize) {
            hannWindow = FloatArray(captureSize) { n ->
                (0.5f - 0.5f * cos(2.0 * PI * n / captureSize)).toFloat()
            }
            lastCaptureSize = captureSize
        }
        val window = hannWindow!!

        // ── Step 1: Convert unsigned 8-bit → float [-1.0, +1.0] + apply Hann window ──
        // Visualizer.getWaveForm() returns unsigned bytes: 0=min, 128=center, 255=max
        fftReal.fill(0f)
        fftImag.fill(0f)

        for (i in 0 until captureSize) {
            // Convert unsigned byte to float: (byte & 0xFF) gives 0–255, subtract 128 → -128..+127
            val sample = ((waveform[i].toInt() and 0xFF) - 128) / 128f
            fftReal[i] = sample * window[i]
            // Remaining indices stay 0 (zero-padding to fftSize)
        }

        // ── Step 2: FFT ──
        SimpleFFT.fft(fftReal, fftImag)

        // ── Step 3: Magnitude → dB ──
        // normFactor = 4/captureSize: ×2 (single-sided, drop negative-freq half) × ×2 (Hann avg 0.5)
        // ÷captureSize (FFT norm — NOT fftSize, only captureSize real samples). Full-scale sine → 0 dB.
        val normFactor = 4f / captureSize

        val dbOut = FloatArray(binCount)
        for (k in 0 until binCount) {
            val mag = sqrt(fftReal[k] * fftReal[k] + fftImag[k] * fftImag[k]) * normFactor
            dbOut[k] = if (mag > 1e-10f) {
                (20f * log10(mag)).coerceAtLeast(-96f)
            } else {
                -96f
            }
        }

        return dbOut
    }

    /** Frequency for a given bin index. */
    fun binToFrequency(bin: Int): Float = bin * sampleRate.toFloat() / fftSize
}

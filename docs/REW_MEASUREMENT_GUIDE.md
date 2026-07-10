# Measuring Equalizer314 EQ fidelity with REW

Pipeline for verifying that what's drawn on the parametric graph
matches what the phone actually applies to audio. Mirrors the test
GitHub user `RHimadiiev` ran for issue #26 (red = REW prediction,
green = Equalizer314 measured) — use this to repro his finding, and
to verify the shelf-density fix landed correctly.

The fix that motivated this guide:
`app/src/main/java/com/bearinmind/equalizer314/dsp/ParametricToDpConverter.kt`
— `supportsForBand()` shelf branches went from 4–6 sample points to
8–10, so DynamicsProcessing's linear interpolation has enough data
to reproduce the analytical RBJ shelf curve. The pre-fix behavior
under-sampled the bend region (especially for Q ≤ 1.5 shelves) and
produced the smoother / wrong-shape green curve in his measurement.

---

## What you need

- **REW** (Room EQ Wizard) — free, <https://roomeqwizard.com>.
  Windows installer.
- **A capture path** for the phone's audio output. Three options,
  ordered easiest → cleanest:
  - **Easy (acoustic):** any mic into the laptop. The laptop's
    built-in mic works for relative comparisons because both
    measurements share the same mic/room/speaker chain and that
    cancels out in trace arithmetic.
  - **Cleaner (electrical):** phone audio-out → audio interface
    line-in → laptop. Removes speaker + room from the result.
  - **Cleanest (USB audio class):** phone as a USB audio device into
    the laptop. Pure digital path, no acoustics. Samsung phones can
    do this with "Sound Assistant" on some builds.

---

## 1. Generate the predicted curve in REW

This is REW's "what *should* happen" — the analytical RBJ response
the phone needs to reproduce.

1. Open REW → **File → New**.
2. **Tools → Equaliser** (or click the EQ icon on the top toolbar).
3. In the Filter Tasks panel:
   - **Equaliser**: `Generic`
   - Target Settings: leave default, irrelevant for this test
4. In the **Filters** tab, enter the parametric values to test.
   Use `RHimadiiev`'s exact set so you can compare side-by-side
   with his screenshot:

   | # | Type              | Freq (Hz) | Gain (dB) | Q    |
   |---|-------------------|-----------|-----------|------|
   | 1 | LS Q (Low Shelf)  | 50        | -5.0      | 0.71 |
   | 2 | PK (Peak)         | 300       | +4.0      | 3.0  |
   | 3 | PK                | 1400      | -5.0      | 4.0  |
   | 4 | PK                | 4000      | +5.0      | 6.0  |
   | 5 | PK                | 8000      | +5.0      | 5.0  |
   | 6 | HS Q (High Shelf) | 10000     | -4.0      | 0.71 |

5. The EQ window's graph now shows the predicted analytical
   response. **Screenshot it** or **Export → Filter settings as
   text** as your reference.

## 2. Import the same values into Equalizer314 parametric EQ

On the phone, in EQ314's Parametric mode:

1. Tap Power FAB **off** for now.
2. **Disable the limiter** — Settings/Limiter → toggle off. Critical:
   default -2 dB / 10:1 will squash sweep peaks during measurement
   and mimic EQ fidelity loss.
3. **Disable MBC** — MBC screen → master switch off.
4. **Disable Skip system sounds** in Channel Input (so the sweep
   audio isn't bypassed if Android misclassifies the playback
   usage).
5. Build each band to match REW exactly:
   - Band 1: LSHELF, 50 Hz, -5.0 dB, Q 0.71
   - Band 2: PEAK, 300 Hz, +4.0 dB, Q 3.0
   - Band 3: PEAK, 1400 Hz, -5.0 dB, Q 4.0
   - Band 4: PEAK, 4000 Hz, +5.0 dB, Q 6.0
   - Band 5: PEAK, 8000 Hz, +5.0 dB, Q 5.0
   - Band 6: HSHELF, 10000 Hz, -4.0 dB, Q 0.71
6. Preamp = 0 dB (REW reference has no preamp; don't introduce
   extra headroom).
7. **Save Preset** → name it `REW_TestSet` for easy recall between
   captures.

## 3. Generate the sweep WAV in REW

1. REW → **Measure** (top toolbar) → Settings tab.
2. Configure:
   - Type: SPL
   - Range: 20 Hz – 20 kHz
   - Length: 256k samples
   - Sweep: Log
3. Click **"Save sweep as wave file"** → save somewhere accessible.
4. Transfer the WAV to the phone (USB copy, Drive, etc.).

## 4. Set up the capture path

1. Plug mic into the laptop.
2. REW → **Preferences → Soundcard** → set Input to that mic.
   Output to your system default (irrelevant — phone plays).
3. Click **"Check Levels"** → speak / clap → confirm meter moves.
4. Position the mic in front of whatever the phone outputs through
   (phone speaker laid flat, BT headphone earcup pressed to mic,
   USB DAC line-out into audio interface, etc.).

## 5. Capture Baseline (EQ off)

1. Phone: EQ314 Power **OFF**. Limiter / MBC / system-sound-bypass
   already off from §2.
2. Open the sweep WAV in a file manager or music app on the phone.
   Play through your chosen output. Volume comfortable, not maxed.
3. The instant you tap play, click **Measure** in REW. REW records
   while the sweep plays. Wait for the analysis to finish.
4. Rename the resulting measurement to **`Baseline`** (right-click
   in the All SPL panel → Rename).

## 6. Capture WithEQ (EQ on)

1. Phone: EQ314 Power **ON**. Load the `REW_TestSet` preset.
2. Replay the same sweep WAV at the **same volume**, mic in the
   **same spot**.
3. REW → **Measure** → capture.
4. Rename to **`WithEQ`**.

## 7. Compute the actual EQ transfer function

1. In REW's All SPL panel, select both measurements (Ctrl+click).
2. **Trace Arithmetic menu** (top toolbar) → choose **A / B**:
   - A = `WithEQ`
   - B = `Baseline`
3. Click **Generate**. A new trace appears — rename it
   **`Measured EQ`**.
4. This is what the phone's DP *actually* did to the audio. Speaker
   / room / mic distortion cancels out because both captures share
   the same chain.

## 8. Compare measured vs predicted

1. Open the EQ window again (**Tools → Equaliser**). If your
   filters are still loaded from §1, you're good; otherwise
   re-enter them.
2. In the main SPL graph, overlay:
   - `Measured EQ` (from §7) — the green trace
   - REW's analytical predicted curve from the EQ window — the red
     trace
3. The two curves should match. **Any deviation = how far our DP-
   rendered curve drifts from the analytical biquad math.**

That overlay is exactly the screenshot `RHimadiiev` posted.

---

## Tips for clean measurements

- **Identical mic position and volume** between captures. A 1 cm
  shift can introduce ~1 dB error across some frequencies, which
  reads as fake EQ deviation.
- **Quiet room.** No music, fans, AC, conversation during sweep.
- **Use headphones / IEMs in a cup over the mic** rather than the
  phone speaker if you want bass fidelity — phone speakers are
  bass-rolled-off below ~150 Hz and that adds noise to the
  measurement of the low-shelf bend.
- **Average 3+ captures** per condition if you want rigour
  (REW supports measurement averaging).
- **Save the `.mdat` file** after capturing. You can re-open in
  REW later, share with collaborators / send to Claude for
  interpretation.
- **Don't enable Vector Average / Phase Averaging** unless you
  know what you're doing — frequency-domain magnitude is what we
  care about here.

---

## What to expect with the post-fix build

After the shelf-density change in `ParametricToDpConverter.kt`
(`supportsForBand()` shelf branches now use 8–10 points instead of
4–6):

- **Low-shelf region (below ~150 Hz)** — the previously-saggy
  green curve should now hug the red curve. The bend at the corner
  (50 Hz in the test set) should look like a proper analytical
  shelf transition.
- **High-shelf region (above ~7 kHz)** — same improvement on the
  other end.
- **BELL peaks** (300 Hz / 1.4 kHz / 4 kHz / 8 kHz in the test set)
  — already had Q-aware density (8 points for Q > 7, 4 points for
  Q > 3, etc.), so these should match closely both pre- and post-
  fix.

If a deviation remains in any specific filter, the next places to
investigate:

1. **Limiter / MBC actually disabled?** Re-verify they're off.
   Triple-check in app settings.
2. **DP band count.** Currently `127` (Wavelet-parity). The Android
   `DynamicsProcessing` Pre-EQ allows up to 128 IIRC. Bumping to
   128 is trivial; going higher requires Android version that
   supports it.
3. **Anchor placement.** Check that each band's centre frequency
   is in the final sample list — `ParametricToDpConverter` adds
   anchors at lines 100-112.
4. **Sweep level.** If the sweep is unusually loud, even with
   limiter off the DP itself has internal clipping. Use REW at
   roughly -12 dBFS sweep level.
5. **Phase issues from cascaded biquads.** Out of scope — the user
   is comparing magnitude, not phase.

---

## Quick reference: files involved in the fidelity chain

- `app/src/main/java/com/bearinmind/equalizer314/dsp/BiquadFilter.kt`
  — RBJ formulas + analytical `getFrequencyResponse()`. Source of
  truth for what the curve *should* be.
- `app/src/main/java/com/bearinmind/equalizer314/dsp/ParametricToDpConverter.kt`
  — samples the analytical response at N log-spaced points and
  feeds them to DP. `supportsForBand()` decides where to place
  extra samples per filter type.
- `app/src/main/java/com/bearinmind/equalizer314/audio/DynamicsProcessingManager.kt`
  — builds the `DynamicsProcessing` instance with the configured
  band count (127 default, `VARIANT_FAVOR_FREQUENCY_RESOLUTION`).
- `app/src/main/java/com/bearinmind/equalizer314/state/EqPreferencesManager.kt`
  — `dpBandCount` preference if you want to expose band count as a
  user-tweakable setting later.

---

## Source

Original test methodology: GitHub issue #26 by `RHimadiiev`
(<https://github.com/bearinmindcat/Equalizer314/issues/26>).
Reference screenshot: green = his measured EQ trace, red = REW's
analytical predicted curve.

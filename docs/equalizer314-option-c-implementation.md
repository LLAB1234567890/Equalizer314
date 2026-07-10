# Equalizer314 — System-Wide Custom DSP via AudioEffect Session Attachment

## Project Context

Equalizer314 is an Android EQ app (Kotlin/Jetpack Compose) with a custom DSP engine that currently only processes audio played through its own ExoPlayer instance. The goal is to make it process audio from ALL other apps (Spotify, YouTube, Chrome, SoundCloud, etc.) without root, while preserving the full custom DSP chain.

### Current Audio Pipeline (In-App Only)
```
Audio File → ExoPlayer decoder → PCM 16-bit samples
  → ParametricEqAudioProcessor (Media3 AudioProcessor)
    → ParametricEqualizer.process()
      → Band 1 (BiquadFilter) → Band 2 → Band 3 → Band 4 → Band 5
      → Soft clip (prevents harsh distortion)
    → SpectrumAnalyzer (FFT for visual display)
  → Audio output (speakers/headphones)
```

### Existing DSP Engine Capabilities (Must Be Preserved)
- 5-band parametric EQ with per-band: frequency (20Hz–20kHz), gain (-15 to +15dB), Q factor (0.1–10.0)
- Filter types per band: Bell, Low Shelf, High Shelf, Low Pass, High Pass
- BiquadFilter.kt implements RBJ Audio EQ Cookbook (shelves/passes) and Vicanek impulse invariance (bell filters)
- Soft clipper for distortion prevention
- SpectrumAnalyzer with 2048-point FFT at ~60fps
- ParametricEqGraphView modeled after Ableton EQ Eight

### Target Pipeline (System-Wide)
```
Other App Audio (Spotify, YouTube, Chrome, etc.)
  → Android AudioFlinger mixer
    → Our custom AudioEffect attached to the app's audio session
      → Our ParametricEqualizer.process() [unchanged biquad chain]
      → Soft clip
      → SpectrumAnalyzer feed
    → Audio output
```

---

## Implementation Strategy: Three-Tier Architecture

We are implementing THREE processing modes that the user can switch between. Each has different trade-offs for compatibility vs DSP capability.

### Mode 1: Custom Native AudioEffect (PRIMARY — "Option C")
**What**: Register a custom native audio effect library (.so) that Android's AudioFlinger loads and routes audio through. Attach it to detected audio sessions.
**Compatibility**: Works with Spotify, Chrome, SoundCloud — anything that goes through normal AudioFlinger mixer path.
**Does NOT work with**: Apps using FastTrack/Direct/Offload output paths (hi-res players, some games).
**DSP capability**: FULL — we get raw PCM buffers in native code and can run our complete biquad chain.

### Mode 2: AudioPlaybackCapture (FALLBACK)
**What**: Capture system audio via MediaProjection + AudioRecord, process through DSP, output via AudioTrack.
**Compatibility**: Works with all apps EXCEPT those that set `allowAudioPlaybackCapture=false` (Spotify, Chrome, SoundCloud).
**DSP capability**: FULL — raw PCM buffer access.

### Mode 3: DynamicsProcessing (COMPATIBLE FALLBACK)
**What**: Attach Android's built-in DynamicsProcessing effect to audio sessions.
**Compatibility**: Works with ALL apps including Spotify, Chrome, SoundCloud.
**DSP capability**: LIMITED — frequency + gain only, no Q factor, no filter type selection, no custom math.

### Why All Three
- Mode 1 covers the majority of apps WITH full DSP (Spotify, Chrome, YouTube, etc.)
- Mode 2 covers apps that Mode 1 can't reach (if any edge cases arise)
- Mode 3 is the universal fallback with reduced controls
- The app auto-selects the best available mode per audio session, or the user manually picks

---

## Mode 1: Custom Native AudioEffect — Full Implementation

This is the core of Option C and the most complex part. Here's how PowerAmp EQ-style processing works at a technical level.

### Architecture Overview

```
┌─────────────────────────────────────────────────┐
│ Equalizer314 App (Kotlin)                       │
│                                                 │
│  ┌──────────────────┐  ┌─────────────────────┐  │
│  │ Session Detector  │  │ UI / Controls       │  │
│  │ (finds audio     │  │ (band freq, gain,   │  │
│  │  session IDs)    │  │  Q, filter type)    │  │
│  └────────┬─────────┘  └──────────┬──────────┘  │
│           │                       │              │
│  ┌────────▼───────────────────────▼──────────┐  │
│  │ AudioEffect Manager (Java/Kotlin)         │  │
│  │ Attaches our custom effect to sessions    │  │
│  │ Sends parameter updates via setParameter  │  │
│  └────────┬──────────────────────────────────┘  │
│           │                                      │
├───────────┼──────────────────────────────────────┤
│ Native Layer (C/C++)                             │
│           │                                      │
│  ┌────────▼──────────────────────────────────┐  │
│  │ libeq314effect.so                         │  │
│  │ Implements EffectHAL interface            │  │
│  │                                           │  │
│  │  ┌─────────────────────────────────┐      │  │
│  │  │ BiquadFilter (C++ port)         │      │  │
│  │  │ - RBJ cookbook coefficients      │      │  │
│  │  │ - Vicanek impulse invariance    │      │  │
│  │  │ - 5 bands, per-band params      │      │  │
│  │  │ - Soft clipper                  │      │  │
│  │  └─────────────────────────────────┘      │  │
│  │                                           │  │
│  │  process() receives raw PCM buffers       │  │
│  │  from AudioFlinger and returns processed  │  │
│  └───────────────────────────────────────────┘  │
│                                                  │
├──────────────────────────────────────────────────┤
│ Android AudioFlinger (System)                    │
│  Routes audio through our effect when attached   │
│  to an active audio session                      │
└──────────────────────────────────────────────────┘
```

### Step 1: Create the Native Audio Effect Library

This is a C/C++ shared library that implements the Android audio effect HAL interface defined in `audio_effect.h`. When properly registered, AudioFlinger will load it and route audio through it.

#### File: `app/src/main/cpp/eq314_effect.cpp`

```cpp
#include <cstdlib>
#include <cstring>
#include <cmath>
#include <new>
#include <android/log.h>

// Android audio effect headers
#include <hardware/audio_effect.h>

#define LOG_TAG "EQ314Effect"
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Our effect's UUID — generate unique ones for your app
// Type UUID: identifies this as an "Equalizer" type effect
static const effect_uuid_t EQ314_TYPE_UUID = {
    0x0bed4300, 0xddd6, 0x11db,
    0x8f34, {0x00, 0x02, 0xa5, 0xd5, 0xc5, 0x14}
};

// Implementation UUID: unique to our specific implementation
static const effect_uuid_t EQ314_IMPL_UUID = {
    0xe3141592, 0x6535, 0x4e41,
    0x9314, {0x15, 0x92, 0x65, 0x35, 0x89, 0x79}
};

// ============================================================
// Biquad Filter — port of your BiquadFilter.kt
// ============================================================

enum class FilterType : int {
    BELL = 0,
    LOW_SHELF = 1,
    HIGH_SHELF = 2,
    LOW_PASS = 3,
    HIGH_PASS = 4
};

struct BiquadCoeffs {
    double b0, b1, b2, a1, a2;
};

struct BiquadState {
    double x1 = 0, x2 = 0;  // input history
    double y1 = 0, y2 = 0;  // output history
};

struct BandParams {
    bool enabled = true;
    float frequency = 1000.0f;  // Hz
    float gain = 0.0f;          // dB
    float q = 1.0f;             // Q factor
    FilterType type = FilterType::BELL;
};

// RBJ Audio EQ Cookbook implementation
static BiquadCoeffs computeCoefficients(const BandParams& params, int sampleRate) {
    BiquadCoeffs c = {1.0, 0.0, 0.0, 0.0, 0.0};

    double w0 = 2.0 * M_PI * params.frequency / sampleRate;
    double cosw0 = cos(w0);
    double sinw0 = sin(w0);
    double alpha = sinw0 / (2.0 * params.q);
    double A = pow(10.0, params.gain / 40.0);  // for shelves/bell
    double a0;

    switch (params.type) {
        case FilterType::BELL: {
            // Vicanek impulse invariance method for more accurate analog behavior
            // (Simplified here — port your exact Vicanek implementation from BiquadFilter.kt)
            double alphaA = alpha * A;
            double alphaOverA = alpha / A;
            a0 = 1.0 + alphaOverA;
            c.b0 = (1.0 + alphaA) / a0;
            c.b1 = (-2.0 * cosw0) / a0;
            c.b2 = (1.0 - alphaA) / a0;
            c.a1 = (-2.0 * cosw0) / a0;
            c.a2 = (1.0 - alphaOverA) / a0;
            break;
        }
        case FilterType::LOW_SHELF: {
            double sqrtA2alpha = 2.0 * sqrt(A) * alpha;
            a0 = (A + 1.0) + (A - 1.0) * cosw0 + sqrtA2alpha;
            c.b0 = A * ((A + 1.0) - (A - 1.0) * cosw0 + sqrtA2alpha) / a0;
            c.b1 = 2.0 * A * ((A - 1.0) - (A + 1.0) * cosw0) / a0;
            c.b2 = A * ((A + 1.0) - (A - 1.0) * cosw0 - sqrtA2alpha) / a0;
            c.a1 = -2.0 * ((A - 1.0) + (A + 1.0) * cosw0) / a0;
            c.a2 = ((A + 1.0) + (A - 1.0) * cosw0 - sqrtA2alpha) / a0;
            break;
        }
        case FilterType::HIGH_SHELF: {
            double sqrtA2alpha = 2.0 * sqrt(A) * alpha;
            a0 = (A + 1.0) - (A - 1.0) * cosw0 + sqrtA2alpha;
            c.b0 = A * ((A + 1.0) + (A - 1.0) * cosw0 + sqrtA2alpha) / a0;
            c.b1 = -2.0 * A * ((A - 1.0) + (A + 1.0) * cosw0) / a0;
            c.b2 = A * ((A + 1.0) + (A - 1.0) * cosw0 - sqrtA2alpha) / a0;
            c.a1 = 2.0 * ((A - 1.0) - (A + 1.0) * cosw0) / a0;
            c.a2 = ((A + 1.0) - (A - 1.0) * cosw0 - sqrtA2alpha) / a0;
            break;
        }
        case FilterType::LOW_PASS: {
            a0 = 1.0 + alpha;
            c.b0 = ((1.0 - cosw0) / 2.0) / a0;
            c.b1 = (1.0 - cosw0) / a0;
            c.b2 = ((1.0 - cosw0) / 2.0) / a0;
            c.a1 = (-2.0 * cosw0) / a0;
            c.a2 = (1.0 - alpha) / a0;
            break;
        }
        case FilterType::HIGH_PASS: {
            a0 = 1.0 + alpha;
            c.b0 = ((1.0 + cosw0) / 2.0) / a0;
            c.b1 = -(1.0 + cosw0) / a0;
            c.b2 = ((1.0 + cosw0) / 2.0) / a0;
            c.a1 = (-2.0 * cosw0) / a0;
            c.a2 = (1.0 - alpha) / a0;
            break;
        }
    }
    return c;
}

// ============================================================
// Effect Context — holds all state for one effect instance
// ============================================================

#define NUM_BANDS 5
#define NUM_CHANNELS 2  // stereo

struct EQ314Context {
    const struct effect_interface_s* itfe;

    // Configuration
    effect_config_t config;
    int sampleRate;
    int state;  // EFFECT_STATE_ACTIVE, etc.

    // Per-band parameters
    BandParams bands[NUM_BANDS];

    // Per-band, per-channel filter state
    BiquadCoeffs coeffs[NUM_BANDS];
    BiquadState states[NUM_BANDS][NUM_CHANNELS];

    // Master enable
    bool eqEnabled;

    // Soft clipper threshold
    float softClipThreshold;
};

// ============================================================
// DSP Processing — this is where your audio gets processed
// ============================================================

static inline float softClip(float sample, float threshold) {
    if (sample > threshold) {
        return threshold + (1.0f - threshold) * tanhf((sample - threshold) / (1.0f - threshold));
    } else if (sample < -threshold) {
        return -(threshold + (1.0f - threshold) * tanhf((-sample - threshold) / (1.0f - threshold)));
    }
    return sample;
}

static void recalculateCoefficients(EQ314Context* ctx) {
    for (int i = 0; i < NUM_BANDS; i++) {
        ctx->coeffs[i] = computeCoefficients(ctx->bands[i], ctx->sampleRate);
    }
}

static int processAudio(EQ314Context* ctx, audio_buffer_t* inBuffer, audio_buffer_t* outBuffer) {
    if (!ctx->eqEnabled) {
        // Passthrough
        if (inBuffer->raw != outBuffer->raw) {
            memcpy(outBuffer->raw, inBuffer->raw,
                   inBuffer->frameCount * NUM_CHANNELS * sizeof(int16_t));
        }
        return 0;
    }

    int16_t* in = inBuffer->s16;
    int16_t* out = outBuffer->s16;
    size_t frameCount = inBuffer->frameCount;

    for (size_t frame = 0; frame < frameCount; frame++) {
        for (int ch = 0; ch < NUM_CHANNELS; ch++) {
            // Read input sample, normalize to -1.0..1.0
            double sample = (double)in[frame * NUM_CHANNELS + ch] / 32768.0;

            // Apply each enabled band's biquad filter in series
            for (int band = 0; band < NUM_BANDS; band++) {
                if (!ctx->bands[band].enabled || ctx->bands[band].gain == 0.0f) {
                    continue;
                }

                BiquadCoeffs& c = ctx->coeffs[band];
                BiquadState& s = ctx->states[band][ch];

                double y = c.b0 * sample + c.b1 * s.x1 + c.b2 * s.x2
                         - c.a1 * s.y1 - c.a2 * s.y2;

                s.x2 = s.x1;
                s.x1 = sample;
                s.y2 = s.y1;
                s.y1 = y;

                sample = y;
            }

            // Soft clip
            sample = softClip((float)sample, ctx->softClipThreshold);

            // Clamp and convert back to int16
            if (sample > 1.0) sample = 1.0;
            if (sample < -1.0) sample = -1.0;
            out[frame * NUM_CHANNELS + ch] = (int16_t)(sample * 32767.0);
        }
    }

    return 0;
}

// ============================================================
// Parameter IDs for communication between Java and native
// ============================================================

// Parameter commands from Java → Native
#define EQ314_PARAM_BAND_FREQUENCY  0x100  // + band index
#define EQ314_PARAM_BAND_GAIN       0x200  // + band index
#define EQ314_PARAM_BAND_Q          0x300  // + band index
#define EQ314_PARAM_BAND_TYPE       0x400  // + band index
#define EQ314_PARAM_BAND_ENABLED    0x500  // + band index
#define EQ314_PARAM_MASTER_ENABLE   0x600
#define EQ314_PARAM_SOFT_CLIP       0x700

// Spectrum data request (Native → Java)
#define EQ314_PARAM_GET_SPECTRUM    0x800

// ============================================================
// Effect Interface Implementation
// ============================================================

static int eq314_init(EQ314Context* ctx) {
    ctx->sampleRate = 44100;
    ctx->eqEnabled = true;
    ctx->softClipThreshold = 0.95f;

    // Default band configuration
    float defaultFreqs[NUM_BANDS] = {60.0f, 230.0f, 910.0f, 3600.0f, 14000.0f};
    for (int i = 0; i < NUM_BANDS; i++) {
        ctx->bands[i].enabled = true;
        ctx->bands[i].frequency = defaultFreqs[i];
        ctx->bands[i].gain = 0.0f;
        ctx->bands[i].q = 1.0f;
        ctx->bands[i].type = FilterType::BELL;
    }

    memset(ctx->states, 0, sizeof(ctx->states));
    recalculateCoefficients(ctx);
    return 0;
}

// --- Effect interface functions ---

static int eq314_process(effect_handle_t self,
                          audio_buffer_t* inBuffer,
                          audio_buffer_t* outBuffer) {
    EQ314Context* ctx = (EQ314Context*)self;
    if (ctx == nullptr || inBuffer == nullptr || outBuffer == nullptr) {
        return -EINVAL;
    }
    return processAudio(ctx, inBuffer, outBuffer);
}

static int eq314_command(effect_handle_t self,
                          uint32_t cmdCode,
                          uint32_t cmdSize, void* pCmdData,
                          uint32_t* replySize, void* pReplyData) {
    EQ314Context* ctx = (EQ314Context*)self;

    switch (cmdCode) {
        case EFFECT_CMD_INIT:
            eq314_init(ctx);
            if (replySize) *replySize = sizeof(int);
            if (pReplyData) *(int*)pReplyData = 0;
            return 0;

        case EFFECT_CMD_SET_CONFIG: {
            if (cmdSize < sizeof(effect_config_t)) return -EINVAL;
            effect_config_t* config = (effect_config_t*)pCmdData;
            ctx->config = *config;
            ctx->sampleRate = config->inputCfg.samplingRate;
            recalculateCoefficients(ctx);
            if (replySize) *replySize = sizeof(int);
            if (pReplyData) *(int*)pReplyData = 0;
            return 0;
        }

        case EFFECT_CMD_GET_CONFIG: {
            if (replySize) *replySize = sizeof(effect_config_t);
            if (pReplyData) memcpy(pReplyData, &ctx->config, sizeof(effect_config_t));
            return 0;
        }

        case EFFECT_CMD_ENABLE:
            ctx->state = EFFECT_STATE_ACTIVE;
            if (replySize) *replySize = sizeof(int);
            if (pReplyData) *(int*)pReplyData = 0;
            return 0;

        case EFFECT_CMD_DISABLE:
            ctx->state = EFFECT_STATE_INITIALIZED;
            if (replySize) *replySize = sizeof(int);
            if (pReplyData) *(int*)pReplyData = 0;
            return 0;

        case EFFECT_CMD_SET_PARAM: {
            if (cmdSize < sizeof(effect_param_t)) return -EINVAL;
            effect_param_t* param = (effect_param_t*)pCmdData;
            int32_t paramId = *(int32_t*)param->data;
            void* valuePtr = param->data + ((param->psize + 3) & ~3);  // aligned

            int bandIndex = paramId & 0x0F;
            int paramType = paramId & 0xFF0;

            if (bandIndex >= NUM_BANDS && paramType != EQ314_PARAM_MASTER_ENABLE
                && paramType != EQ314_PARAM_SOFT_CLIP) {
                return -EINVAL;
            }

            switch (paramType) {
                case EQ314_PARAM_BAND_FREQUENCY:
                    ctx->bands[bandIndex].frequency = *(float*)valuePtr;
                    recalculateCoefficients(ctx);
                    break;
                case EQ314_PARAM_BAND_GAIN:
                    ctx->bands[bandIndex].gain = *(float*)valuePtr;
                    recalculateCoefficients(ctx);
                    break;
                case EQ314_PARAM_BAND_Q:
                    ctx->bands[bandIndex].q = *(float*)valuePtr;
                    recalculateCoefficients(ctx);
                    break;
                case EQ314_PARAM_BAND_TYPE:
                    ctx->bands[bandIndex].type = (FilterType)(*(int32_t*)valuePtr);
                    recalculateCoefficients(ctx);
                    break;
                case EQ314_PARAM_BAND_ENABLED:
                    ctx->bands[bandIndex].enabled = *(int32_t*)valuePtr != 0;
                    break;
                case EQ314_PARAM_MASTER_ENABLE:
                    ctx->eqEnabled = *(int32_t*)valuePtr != 0;
                    break;
                case EQ314_PARAM_SOFT_CLIP:
                    ctx->softClipThreshold = *(float*)valuePtr;
                    break;
            }

            if (replySize) *replySize = sizeof(int);
            if (pReplyData) *(int*)pReplyData = 0;
            return 0;
        }

        case EFFECT_CMD_RESET:
            memset(ctx->states, 0, sizeof(ctx->states));
            if (replySize) *replySize = sizeof(int);
            if (pReplyData) *(int*)pReplyData = 0;
            return 0;

        default:
            return -EINVAL;
    }
}

static int eq314_get_descriptor(effect_handle_t self,
                                 effect_descriptor_t* pDescriptor) {
    if (pDescriptor == nullptr) return -EINVAL;

    pDescriptor->type = EQ314_TYPE_UUID;
    pDescriptor->uuid = EQ314_IMPL_UUID;
    pDescriptor->apiVersion = EFFECT_CONTROL_API_VERSION;
    pDescriptor->flags = EFFECT_FLAG_TYPE_INSERT | EFFECT_FLAG_INSERT_LAST;
    pDescriptor->cpuLoad = 10;  // relative CPU load indicator
    pDescriptor->memoryUsage = 0;
    strncpy(pDescriptor->name, "EQ314 Parametric", sizeof(pDescriptor->name));
    strncpy(pDescriptor->implementor, "Equalizer314", sizeof(pDescriptor->implementor));
    return 0;
}

// ============================================================
// Effect interface vtable
// ============================================================

static const struct effect_interface_s gEQ314Interface = {
    eq314_process,
    eq314_command,
    eq314_get_descriptor,
    nullptr  // process_reverse (not needed for insert effects)
};

// ============================================================
// Library interface — what AudioFlinger calls to create effects
// ============================================================

static const effect_descriptor_t gEQ314Descriptor = {
    EQ314_TYPE_UUID,    // type
    EQ314_IMPL_UUID,    // uuid
    EFFECT_CONTROL_API_VERSION,
    EFFECT_FLAG_TYPE_INSERT | EFFECT_FLAG_INSERT_LAST,
    10,                 // cpuLoad
    0,                  // memoryUsage
    "EQ314 Parametric",
    "Equalizer314"
};

static int eq314_lib_create(const effect_uuid_t* uuid,
                             int32_t sessionId,
                             int32_t ioId,
                             effect_handle_t* pHandle) {
    if (memcmp(uuid, &EQ314_IMPL_UUID, sizeof(effect_uuid_t)) != 0) {
        return -ENOENT;
    }

    EQ314Context* ctx = new(std::nothrow) EQ314Context();
    if (ctx == nullptr) return -ENOMEM;

    ctx->itfe = &gEQ314Interface;
    eq314_init(ctx);

    *pHandle = (effect_handle_t)ctx;
    ALOGI("EQ314 effect created for session %d", sessionId);
    return 0;
}

static int eq314_lib_release(effect_handle_t handle) {
    EQ314Context* ctx = (EQ314Context*)handle;
    delete ctx;
    ALOGI("EQ314 effect released");
    return 0;
}

static int eq314_lib_get_descriptor(const effect_uuid_t* uuid,
                                     effect_descriptor_t* pDescriptor) {
    if (memcmp(uuid, &EQ314_IMPL_UUID, sizeof(effect_uuid_t)) != 0) {
        return -ENOENT;
    }
    *pDescriptor = gEQ314Descriptor;
    return 0;
}

// ============================================================
// Library entry point — AudioFlinger looks for this symbol
// ============================================================

extern "C" {

audio_effect_library_t AUDIO_EFFECT_LIBRARY_INFO_SYM = {
    .tag = AUDIO_EFFECT_LIBRARY_TAG,
    .version = EFFECT_LIBRARY_API_VERSION,
    .name = "EQ314 Effect Library",
    .implementor = "Equalizer314",
    .create_effect = eq314_lib_create,
    .release_effect = eq314_lib_release,
    .get_descriptor = eq314_lib_get_descriptor
};

}  // extern "C"
```

#### File: `app/src/main/cpp/CMakeLists.txt`

```cmake
cmake_minimum_required(VERSION 3.18)
project(eq314effect)

add_library(eq314effect SHARED eq314_effect.cpp)

target_link_libraries(eq314effect
    log
)

# We need access to the audio effect headers
# These may need to be vendored from AOSP source
target_include_directories(eq314effect PRIVATE
    ${CMAKE_SOURCE_DIR}/include
)
```

**IMPORTANT NOTE**: The `audio_effect.h` and `hardware/audio_effect.h` headers are part of AOSP and may not be in the standard NDK. You will likely need to vendor them from the AOSP source tree:
- `hardware/libhardware/include/hardware/audio_effect.h`
- `system/media/audio_effects/include/audio_effects/effect_*.h`

Copy these headers into `app/src/main/cpp/include/hardware/`.

### Step 2: Audio Session Detection

This is how we find audio session IDs from other apps so we can attach our effect.

#### File: `app/src/main/java/com/eq314/audio/SessionDetector.kt`

```kotlin
/**
 * Detects audio sessions from other apps using multiple strategies:
 * 1. AudioManager.registerAudioPlaybackCallback — standard API
 * 2. ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION broadcasts — apps announce sessions
 * 3. Advanced Player Tracking via DUMP permission — finds hidden sessions
 * 4. NotificationListenerService — detects media notifications
 *
 * This mirrors how PowerAmp EQ discovers audio sessions.
 */

package com.eq314.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.audiofx.AudioEffect
import android.os.Build
import android.util.Log

data class DetectedSession(
    val sessionId: Int,
    val packageName: String?,
    val playerType: String?,
    val isActive: Boolean
)

class SessionDetector(private val context: Context) {

    private val TAG = "EQ314_SessionDetector"
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val activeSessions = mutableMapOf<Int, DetectedSession>()
    private var onSessionCallback: ((DetectedSession) -> Unit)? = null
    private var onSessionEndCallback: ((Int) -> Unit)? = null

    // ==========================================
    // Strategy 1: AudioPlaybackCallback (API 26+)
    // Most reliable for apps using standard AudioTrack/MediaPlayer
    // ==========================================
    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            val currentSessionIds = mutableSetOf<Int>()

            for (config in configs) {
                // config.audioAttributes gives us USAGE type
                // We want USAGE_MEDIA, USAGE_GAME, USAGE_UNKNOWN
                val sessionId = getSessionIdFromConfig(config)
                if (sessionId > 0) {
                    currentSessionIds.add(sessionId)

                    if (!activeSessions.containsKey(sessionId)) {
                        val session = DetectedSession(
                            sessionId = sessionId,
                            packageName = getPackageFromConfig(config),
                            playerType = "AudioPlaybackCallback",
                            isActive = true
                        )
                        activeSessions[sessionId] = session
                        Log.i(TAG, "New session detected: $session")
                        onSessionCallback?.invoke(session)
                    }
                }
            }

            // Check for ended sessions
            val endedSessions = activeSessions.keys.filter { it !in currentSessionIds }
            for (sessionId in endedSessions) {
                activeSessions.remove(sessionId)
                Log.i(TAG, "Session ended: $sessionId")
                onSessionEndCallback?.invoke(sessionId)
            }
        }
    }

    // NOTE: getSessionIdFromConfig uses reflection or hidden APIs
    // AudioPlaybackConfiguration doesn't directly expose session ID in public API
    // This requires the DUMP permission granted via ADB:
    //   adb shell pm grant com.eq314 android.permission.DUMP
    private fun getSessionIdFromConfig(config: AudioPlaybackConfiguration): Int {
        return try {
            // Try reflection to access the audio session ID
            // The public API added getAudioSessionId() but availability varies
            if (Build.VERSION.SDK_INT >= 29) {
                // Android 10+ has a hidden method we can try
                val method = config.javaClass.getMethod("getAudioSessionId")
                method.invoke(config) as? Int ?: 0
            } else {
                0
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not get session ID from config: ${e.message}")
            0
        }
    }

    private fun getPackageFromConfig(config: AudioPlaybackConfiguration): String? {
        return try {
            // Android 14+ has getClientPackageName() potentially
            // Earlier versions need DUMP permission + dumpsys parsing
            val method = config.javaClass.getMethod("getClientUid")
            val uid = method.invoke(config) as? Int
            uid?.let {
                context.packageManager.getNameForUid(it)
            }
        } catch (e: Exception) {
            null
        }
    }

    // ==========================================
    // Strategy 2: AudioEffect session broadcasts
    // Apps that properly integrate with AudioEffect framework
    // broadcast when they open/close sessions
    // ==========================================
    private val sessionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val sessionId = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
            val packageName = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME)

            when (intent.action) {
                AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> {
                    if (sessionId > 0) {
                        val session = DetectedSession(
                            sessionId = sessionId,
                            packageName = packageName,
                            playerType = "Broadcast",
                            isActive = true
                        )
                        activeSessions[sessionId] = session
                        Log.i(TAG, "Session broadcast opened: $session")
                        onSessionCallback?.invoke(session)
                    }
                }
                AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> {
                    if (sessionId > 0) {
                        activeSessions.remove(sessionId)
                        Log.i(TAG, "Session broadcast closed: $sessionId")
                        onSessionEndCallback?.invoke(sessionId)
                    }
                }
            }
        }
    }

    // ==========================================
    // Strategy 3: Global session 0 (deprecated but still works on some devices)
    // Works on Samsung, may not work on Pixel
    // ==========================================
    fun tryGlobalSession(): Boolean {
        // Session 0 = global audio output mix
        // Google deprecated this but many OEMs still support it
        return try {
            val session = DetectedSession(
                sessionId = 0,
                packageName = "GLOBAL",
                playerType = "Session0_Global",
                isActive = true
            )
            activeSessions[0] = session
            onSessionCallback?.invoke(session)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Global session 0 not supported on this device")
            false
        }
    }

    // ==========================================
    // Start/stop detection
    // ==========================================
    fun start(
        onSession: (DetectedSession) -> Unit,
        onSessionEnd: (Int) -> Unit
    ) {
        onSessionCallback = onSession
        onSessionEndCallback = onSessionEnd

        // Register playback callback
        audioManager.registerAudioPlaybackCallback(playbackCallback, null)

        // Register broadcast receiver
        val filter = IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        }
        context.registerReceiver(sessionReceiver, filter,
            Context.RECEIVER_EXPORTED)

        Log.i(TAG, "Session detection started")
    }

    fun stop() {
        audioManager.unregisterAudioPlaybackCallback(playbackCallback)
        try { context.unregisterReceiver(sessionReceiver) } catch (_: Exception) {}
        activeSessions.clear()
        Log.i(TAG, "Session detection stopped")
    }

    fun getActiveSessions(): List<DetectedSession> = activeSessions.values.toList()
}
```

### Step 3: Effect Attachment Manager

This Kotlin class manages attaching/detaching our custom effect to detected sessions.

#### File: `app/src/main/java/com/eq314/audio/EffectManager.kt`

```kotlin
/**
 * Manages attaching our custom native AudioEffect to detected audio sessions.
 * Falls back to DynamicsProcessing if custom effect is unavailable on the device.
 *
 * Communication flow:
 *   UI → EffectManager → setParameter() → Native libeq314effect.so → DSP processing
 */

package com.eq314.audio

import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.util.Log
import java.util.UUID
import java.nio.ByteBuffer
import java.nio.ByteOrder

class EffectManager {

    private val TAG = "EQ314_EffectManager"

    // UUIDs matching our native library
    companion object {
        val EQ314_TYPE_UUID: UUID = UUID.fromString("0bed4300-ddd6-11db-8f34-0002a5d5c514")
        val EQ314_IMPL_UUID: UUID = UUID.fromString("e3141592-6535-4e41-9314-159265358979")

        // Parameter IDs — must match native eq314_effect.cpp
        const val PARAM_BAND_FREQUENCY = 0x100
        const val PARAM_BAND_GAIN = 0x200
        const val PARAM_BAND_Q = 0x300
        const val PARAM_BAND_TYPE = 0x400
        const val PARAM_BAND_ENABLED = 0x500
        const val PARAM_MASTER_ENABLE = 0x600
        const val PARAM_SOFT_CLIP = 0x700
    }

    // Active effects keyed by session ID
    private val activeEffects = mutableMapOf<Int, AudioEffect>()
    private val fallbackEffects = mutableMapOf<Int, DynamicsProcessing>()

    // Current band state (applied to all sessions)
    private val bandParams = Array(5) { BandState() }

    data class BandState(
        var frequency: Float = 1000f,
        var gain: Float = 0f,
        var q: Float = 1.0f,
        var filterType: Int = 0,  // 0=Bell, 1=LowShelf, 2=HighShelf, 3=LowPass, 4=HighPass
        var enabled: Boolean = true
    )

    /**
     * Check if our custom native effect is available on this device.
     * If the .so was properly installed, AudioEffect.queryEffects() will list it.
     */
    fun isCustomEffectAvailable(): Boolean {
        val effects = AudioEffect.queryEffects()
        return effects?.any {
            it.uuid == EQ314_IMPL_UUID
        } ?: false
    }

    /**
     * Attach our effect to an audio session.
     * Tries custom native effect first, falls back to DynamicsProcessing.
     */
    fun attachToSession(sessionId: Int): Boolean {
        if (activeEffects.containsKey(sessionId)) {
            Log.d(TAG, "Already attached to session $sessionId")
            return true
        }

        // Try custom native effect first
        if (tryAttachCustomEffect(sessionId)) {
            return true
        }

        // Fallback to DynamicsProcessing
        Log.w(TAG, "Custom effect unavailable, falling back to DynamicsProcessing for session $sessionId")
        return tryAttachDynamicsProcessing(sessionId)
    }

    private fun tryAttachCustomEffect(sessionId: Int): Boolean {
        return try {
            // Create AudioEffect with our custom UUID
            // Priority 0 = normal, sessionId = target session
            val effect = AudioEffect(
                EQ314_TYPE_UUID,    // type UUID
                EQ314_IMPL_UUID,    // implementation UUID
                0,                   // priority
                sessionId            // audio session to attach to
            )

            effect.enabled = true
            activeEffects[sessionId] = effect

            // Apply current band state to the new effect
            applyAllBandsToEffect(effect)

            Log.i(TAG, "Custom EQ314 effect attached to session $sessionId")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create custom effect for session $sessionId: ${e.message}")
            false
        }
    }

    private fun tryAttachDynamicsProcessing(sessionId: Int): Boolean {
        return try {
            val config = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                1,      // channels
                true,   // pre-EQ enabled
                5,      // pre-EQ band count
                false,  // MBC disabled
                0,
                false,  // post-EQ disabled
                0,
                false   // limiter disabled
            ).build()

            val dp = DynamicsProcessing(0, sessionId, config)
            dp.enabled = true

            // Apply bands (limited — no Q, no filter type)
            for (i in bandParams.indices) {
                val band = DynamicsProcessing.EqBand(
                    bandParams[i].enabled,
                    bandParams[i].frequency,
                    bandParams[i].gain
                )
                dp.setPreEqBandByChannelIndex(0, i, band)
            }

            fallbackEffects[sessionId] = dp
            Log.i(TAG, "DynamicsProcessing fallback attached to session $sessionId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach DynamicsProcessing to session $sessionId: ${e.message}")
            false
        }
    }

    /**
     * Update a band parameter — pushes to all active effects.
     */
    fun updateBand(bandIndex: Int, frequency: Float, gain: Float, q: Float, filterType: Int, enabled: Boolean) {
        if (bandIndex !in 0..4) return

        bandParams[bandIndex] = BandState(frequency, gain, q, filterType, enabled)

        // Update custom native effects (full parameters)
        for ((_, effect) in activeEffects) {
            setFloatParam(effect, PARAM_BAND_FREQUENCY or bandIndex, frequency)
            setFloatParam(effect, PARAM_BAND_GAIN or bandIndex, gain)
            setFloatParam(effect, PARAM_BAND_Q or bandIndex, q)
            setIntParam(effect, PARAM_BAND_TYPE or bandIndex, filterType)
            setIntParam(effect, PARAM_BAND_ENABLED or bandIndex, if (enabled) 1 else 0)
        }

        // Update DynamicsProcessing fallbacks (limited — freq + gain only)
        for ((_, dp) in fallbackEffects) {
            try {
                val band = DynamicsProcessing.EqBand(enabled, frequency, gain)
                dp.setPreEqBandByChannelIndex(0, bandIndex, band)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update DP band: ${e.message}")
            }
        }
    }

    fun setMasterEnabled(enabled: Boolean) {
        for ((_, effect) in activeEffects) {
            setIntParam(effect, PARAM_MASTER_ENABLE, if (enabled) 1 else 0)
            effect.enabled = enabled
        }
        for ((_, dp) in fallbackEffects) {
            dp.enabled = enabled
        }
    }

    /**
     * Detach effect from a session.
     */
    fun detachFromSession(sessionId: Int) {
        activeEffects.remove(sessionId)?.let {
            it.enabled = false
            it.release()
            Log.i(TAG, "Custom effect detached from session $sessionId")
        }
        fallbackEffects.remove(sessionId)?.let {
            it.enabled = false
            it.release()
            Log.i(TAG, "DP fallback detached from session $sessionId")
        }
    }

    fun releaseAll() {
        activeEffects.values.forEach {
            it.enabled = false
            it.release()
        }
        activeEffects.clear()
        fallbackEffects.values.forEach {
            it.enabled = false
            it.release()
        }
        fallbackEffects.clear()
    }

    // ==========================================
    // Parameter helpers
    // ==========================================
    private fun setFloatParam(effect: AudioEffect, paramId: Int, value: Float) {
        try {
            val paramBytes = ByteBuffer.allocate(4).order(ByteOrder.nativeOrder()).putInt(paramId).array()
            val valueBytes = ByteBuffer.allocate(4).order(ByteOrder.nativeOrder()).putFloat(value).array()
            effect.setParameter(paramBytes, valueBytes)
        } catch (e: Exception) {
            Log.w(TAG, "setFloatParam failed: ${e.message}")
        }
    }

    private fun setIntParam(effect: AudioEffect, paramId: Int, value: Int) {
        try {
            val paramBytes = ByteBuffer.allocate(4).order(ByteOrder.nativeOrder()).putInt(paramId).array()
            val valueBytes = ByteBuffer.allocate(4).order(ByteOrder.nativeOrder()).putInt(value).array()
            effect.setParameter(paramBytes, valueBytes)
        } catch (e: Exception) {
            Log.w(TAG, "setIntParam failed: ${e.message}")
        }
    }

    private fun applyAllBandsToEffect(effect: AudioEffect) {
        for (i in bandParams.indices) {
            val b = bandParams[i]
            setFloatParam(effect, PARAM_BAND_FREQUENCY or i, b.frequency)
            setFloatParam(effect, PARAM_BAND_GAIN or i, b.gain)
            setFloatParam(effect, PARAM_BAND_Q or i, b.q)
            setIntParam(effect, PARAM_BAND_TYPE or i, b.filterType)
            setIntParam(effect, PARAM_BAND_ENABLED or i, if (b.enabled) 1 else 0)
        }
        setIntParam(effect, PARAM_MASTER_ENABLE, 1)
    }
}
```

### Step 4: Background Service

Ties session detection and effect management together in a foreground service.

#### File: `app/src/main/java/com/eq314/audio/EQ314Service.kt`

```kotlin
package com.eq314.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

class EQ314Service : Service() {

    private val TAG = "EQ314Service"
    private lateinit var sessionDetector: SessionDetector
    private lateinit var effectManager: EffectManager
    private val binder = EQ314Binder()

    inner class EQ314Binder : Binder() {
        fun getEffectManager(): EffectManager = effectManager
        fun getSessionDetector(): SessionDetector = sessionDetector
    }

    override fun onCreate() {
        super.onCreate()
        sessionDetector = SessionDetector(this)
        effectManager = EffectManager()

        startForeground(NOTIFICATION_ID, buildNotification())

        // Start detecting and auto-attaching
        sessionDetector.start(
            onSession = { session ->
                Log.i(TAG, "Auto-attaching to: ${session.packageName} (session ${session.sessionId})")
                effectManager.attachToSession(session.sessionId)
            },
            onSessionEnd = { sessionId ->
                Log.i(TAG, "Auto-detaching from session $sessionId")
                effectManager.detachFromSession(sessionId)
            }
        )

        // Also try global session 0
        sessionDetector.tryGlobalSession()

        Log.i(TAG, "EQ314 Service started")
        Log.i(TAG, "Custom native effect available: ${effectManager.isCustomEffectAvailable()}")
    }

    override fun onDestroy() {
        sessionDetector.stop()
        effectManager.releaseAll()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder = binder

    private fun buildNotification(): Notification {
        val channelId = "eq314_service"
        val channel = NotificationChannel(
            channelId, "EQ314 Audio Processing",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Equalizer314")
            .setContentText("Processing system audio")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val NOTIFICATION_ID = 314
    }
}
```

### Step 5: AndroidManifest additions

```xml
<!-- Permissions -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />

<!-- For MediaProjection fallback (Mode 2) -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />

<!-- Service -->
<service
    android:name=".audio.EQ314Service"
    android:foregroundServiceType="mediaPlayback"
    android:exported="false" />

<!-- Register our native effect library -->
<!-- NOTE: This is the CRITICAL part — see "Effect Registration" section below -->
```

---

## Critical Challenge: Effect Library Registration

**THIS IS THE HARDEST PART AND THE MAIN R&D RISK.**

Android's AudioFlinger discovers effect libraries by reading `/vendor/etc/audio_effects.xml` (or the older `/system/etc/audio_effects.conf`). Third-party apps CANNOT modify these files without root. This means:

### The Problem
Our `libeq314effect.so` is a properly implemented AudioEffect library, but AudioFlinger won't load it because it doesn't know about it. The effect won't appear in `AudioEffect.queryEffects()` and we can't instantiate it via the `AudioEffect` constructor.

### Possible Solutions (In Order of Feasibility)

#### Solution A: Don't use a custom effect — use DynamicsProcessing creatively
Instead of registering a custom native effect, use `DynamicsProcessing` as the AudioEffect attachment mechanism (for session binding), but use its multi-band compressor stage creatively to approximate our biquad behavior. This is limited but guaranteed to work.

**This is what Wavelet does.** Wavelet gets its EQ working with only freq+gain because that's all DynamicsProcessing provides.

#### Solution B: Use AudioEffect with session attachment + separate processing
1. Attach a lightweight `DynamicsProcessing` or `Equalizer` effect to the session just to "claim" it
2. Use `Visualizer` API attached to the same session to read the audio data
3. Process the Visualizer data through our DSP... but Visualizer is READ-ONLY

**This doesn't work for processing** — Visualizer can't modify audio.

#### Solution C: Root-only native effect registration
With root/Magisk, copy `libeq314effect.so` to `/vendor/lib/soundfx/` and add an entry to `audio_effects.xml`. This is exactly what ViPER4Android and rooted JamesDSP do.

```xml
<!-- Added to /vendor/etc/audio_effects.xml -->
<library name="eq314" path="libeq314effect.so"/>
<effect name="eq314_parametric" library="eq314" uuid="e3141592-6535-4e41-9314-159265358979"/>
```

#### Solution D: Shizuku/ADB-level registration attempt
Using Shizuku or ADB shell permissions, attempt to:
1. Copy the .so to an accessible location
2. Modify audio config files (may require remounting /vendor as rw — needs root)
3. Restart audioserver to reload config

**Verdict: This likely still requires root for the /vendor write.**

#### Solution E: The PowerAmp EQ approach (MOST LIKELY)
Based on all our research, PowerAmp EQ most likely does NOT register a custom native effect. Instead, it:

1. Uses `DynamicsProcessing` (or possibly direct `AudioEffect` session attachment) to bind to audio sessions
2. Runs its own DSP engine in a high-priority background service
3. Uses **DVC (Direct Volume Control)** — a technique where it:
   - Lowers the system volume for the session
   - Captures or monitors the audio level via Visualizer
   - Applies its own DSP processing in its service
   - Compensates by adjusting gain in the DynamicsProcessing effect

This is speculative but fits all the observed behaviors:
- Works with Spotify/Chrome (session attachment, no capture)
- Has full parametric control (own DSP engine)
- DVC mode toggle in settings
- The volume spike/drop issues users report with Spotify

### RECOMMENDED APPROACH FOR EQUALIZER314

Given the constraints, here's the pragmatic implementation plan:

```
┌─────────────────────────────────────────────────────────┐
│ TIER 1: DynamicsProcessing Session Attachment           │
│ - Attach DP to detected audio sessions                  │
│ - Use all available DP parameters (freq, gain)          │
│ - Works with Spotify, Chrome, SoundCloud, YouTube       │
│ - No Q or filter type — but universal compatibility     │
│                                                         │
│ PLUS: Use DynamicsProcessing's multi-band compressor    │
│ creatively to approximate shelf/pass filter behavior    │
│ via aggressive threshold + ratio settings               │
├─────────────────────────────────────────────────────────┤
│ TIER 2: AudioPlaybackCapture for Full DSP               │
│ - For apps that DON'T block capture                     │
│ - Full biquad chain, Q, filter types, soft clip         │
│ - YouTube, YouTube Music, Amazon Music, Deezer, etc.    │
│ - NOT Spotify, Chrome, SoundCloud                       │
├─────────────────────────────────────────────────────────┤
│ TIER 3: In-App Player with Full DSP                     │
│ - Existing ExoPlayer pipeline — unchanged               │
│ - For local file playback                               │
│ - Full DSP with zero limitations                        │
└─────────────────────────────────────────────────────────┘
```

The UI should indicate which mode is active for the current audio source.

---

## Build Configuration

### `app/build.gradle.kts` additions

```kotlin
android {
    defaultConfig {
        // NDK for native audio effect
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
        }
    }
}

dependencies {
    // Existing
    implementation("androidx.media3:media3-exoplayer:1.2.1")
    implementation("androidx.media3:media3-common:1.2.1")

    // No additional dependencies needed for AudioEffect/DynamicsProcessing
    // These are part of android.media.audiofx in the framework
}
```

### ADB Setup Commands (User Must Run)

For Advanced Player Tracking (session detection for apps that don't broadcast sessions):

```bash
# Grant DUMP permission for session detection
adb shell pm grant com.eq314.app android.permission.DUMP

# The user also needs to grant Notification Listener permission
# via Settings > Apps > Special access > Notification access
```

---

## Key Files Summary

```
app/
├── src/main/
│   ├── java/com/eq314/
│   │   ├── audio/
│   │   │   ├── SessionDetector.kt       — Finds audio sessions from other apps
│   │   │   ├── EffectManager.kt         — Attaches effects to sessions
│   │   │   ├── EQ314Service.kt          — Background service tying it together
│   │   │   ├── AudioCaptureService.kt   — Mode 2: AudioPlaybackCapture fallback
│   │   │   └── DynamicsProcessingManager.kt — Mode 3: DP-only fallback
│   │   ├── dsp/
│   │   │   ├── ParametricEqualizer.kt   — EXISTING: your DSP engine
│   │   │   ├── BiquadFilter.kt          — EXISTING: RBJ/Vicanek filters
│   │   │   └── SpectrumAnalyzer.kt      — EXISTING: FFT analyzer
│   │   └── ui/
│   │       ├── EqViewModel.kt           — Mode switching, band state
│   │       ├── ProcessingModeSwitch.kt  — UI for mode selection
│   │       └── ParametricEqGraphView.kt — EXISTING: Ableton-style graph
│   ├── cpp/
│   │   ├── CMakeLists.txt
│   │   ├── eq314_effect.cpp             — Native effect library (for root/future)
│   │   └── include/
│   │       └── hardware/
│   │           └── audio_effect.h       — Vendored from AOSP
│   └── AndroidManifest.xml
```

---

## Implementation Order

1. **SessionDetector.kt** — Get session detection working first. Test by logging which apps' sessions you can see. This is the foundation everything else depends on.

2. **EffectManager.kt with DynamicsProcessing only** — Attach DP to detected sessions. Verify it actually affects audio from Spotify, YouTube, etc. This proves the session attachment pipeline works.

3. **EQ314Service.kt** — Wrap detection + attachment in a foreground service. Test auto-attach/detach as users start/stop music apps.

4. **UI Integration** — Connect your existing ParametricEqGraphView to the EffectManager. When in DP mode, hide Q and filter type controls. When in capture mode, show full controls.

5. **AudioCaptureService.kt (Mode 2)** — Add AudioPlaybackCapture as an alternative mode for when users want full DSP on compatible apps.

6. **Native effect library (Mode 1)** — Build the C++ effect. This only works if you can register it (requires root or a future Android API that allows it). Keep it as an optional advanced feature.

7. **Auto-mode selection** — Detect per-app whether capture is allowed. Auto-select the best available mode. Show indicator in UI.

---

## Known Limitations

- **DynamicsProcessing mode**: No Q factor, no filter type selection, no soft clipper. Your Vicanek/RBJ math is not used. This is the trade-off for Spotify/Chrome compatibility.
- **AudioPlaybackCapture mode**: Blocked by Spotify, Chrome, SoundCloud. Adds latency. Requires persistent notification.
- **Session detection**: Some apps (especially games using low-level AAudio/OpenSL ES) may not create discoverable audio sessions. Advanced Player Tracking via DUMP permission helps but isn't universal.
- **FastTrack/Direct/Offload**: Apps using these hardware-accelerated audio paths bypass ALL software effects. No solution without root. Affected apps: hi-res music players (UAPP, Neutron in hi-res mode), some games, VoIP apps.
- **Global session 0**: Deprecated by Google. Works on Samsung, often fails on Pixel. May break with any OS update.
- **OEM interference**: Samsung Adapt Sound, Dolby Atmos, Xiaomi Mi Sound Enhancer can conflict with our effects. Users may need to disable these.
- **Battery**: Background service + effect processing adds CPU load. Optimize the DSP path and use wake locks carefully.

---

## Testing Matrix

Test each mode against:
| App | Mode 1 (DP) | Mode 2 (Capture) | Expected |
|-----|-------------|-------------------|----------|
| Spotify | ✅ | ❌ Blocked | DP only |
| YouTube Music | ✅ | ✅ | Both work |
| YouTube | ✅ (APT needed) | ✅ | Both work |
| Chrome | ✅ | ❌ Blocked | DP only |
| SoundCloud | ✅ | ❌ Blocked | DP only |
| Amazon Music | ✅ | ✅ | Both work |
| Local files (ExoPlayer) | N/A | N/A | In-app DSP |
| Games | ⚠️ May not work | ⚠️ May work | Varies |

Test on:
- Samsung Galaxy (likely supports session 0)
- Google Pixel (likely does NOT support session 0)
- Various Android versions (10, 11, 12, 13, 14, 15)

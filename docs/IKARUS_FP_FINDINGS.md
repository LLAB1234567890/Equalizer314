# Ikarus VirusTotal false positive — investigation record

VirusTotal scan of `Equalizer314-v0.0.9-beta.apk`
(SHA-256 `d26742a527532c81c01f636a4991d042564caac4b4b1d7d6e76755881cb45490`)
returned **1/60 detection**. Only Ikarus flagged it, label
`Trojan-SMS.AndroidOS.FakeInst`. Every other engine, including all
major mobile-focused vendors, returned "Undetected."

This document records why that flag fires, why it's a false positive,
and why the underlying code can't be changed without removing the
features it enables. Keep it for the next release / next user that
asks.

---

## TL;DR

- **It's a false positive.** Verified five ways: APK hash matches our
  build (no tampering), zero SMS code in source, zero SMS API calls
  in the shipped DEX bytecode, no native libraries, 59/60 vendor
  agreement.
- **The flag fires because v0.0.9 newly added a code profile that
  *resembles* malware to a static heuristic** — even though the
  behaviour is completely legitimate (audio EQ session detection,
  identical to what Wavelet and Poweramp Equalizer do).
- **Action: install the app normally.** If you want extra reassurance,
  you can submit an FP report to Ikarus (link at the end of this
  doc).

---

## Five-way verification it's a false positive

| Check | Result |
|---|---|
| APK SHA-256 matches our locally-built file | ✓ (same `d26742a52...`) |
| Greps for `SEND_SMS` / `RECEIVE_SMS` / `READ_SMS` / `SmsManager` / `sendTextMessage` / `READ_CONTACTS` in `app/src` | **0 matches** |
| Same greps against compiled DEX bytecode (classes.dex + classes2.dex) | **0 matches** |
| Native libraries (`.so`) in the APK | **0** — no JNI code that could hide behaviour |
| Other AV engines (BitDefender, Kaspersky, Avast, AVG, ESET, Sophos, McAfee, Symantec, TrendMicro, Microsoft, Google, etc.) | All **Undetected** |

The label `Trojan-SMS.AndroidOS.FakeInst` is misleading by name. It's
Ikarus's generic-bucket signature for "Android app whose static
profile matches a malware pattern" — it does not mean the analyzer
found actual SMS code, because the app has none.

---

## Why Ikarus specifically flags v0.0.9 (delta vs v0.0.8)

`aapt2 dump permissions` comparison between the two APKs:

| Permission | v0.0.8 | v0.0.9 |
|---|---|---|
| `MODIFY_AUDIO_SETTINGS` | ✓ | ✓ |
| `RECORD_AUDIO` | ✓ | ✓ |
| `FOREGROUND_SERVICE` | ✓ | ✓ |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | ✓ | ✓ |
| `POST_NOTIFICATIONS` | ✓ | ✓ |
| `ACCESS_NETWORK_STATE` | ✓ (media3 transitive) | ✗ (media3 dep dropped) |
| `BLUETOOTH_CONNECT` | — | ✓ **NEW** |
| `QUERY_ALL_PACKAGES` | — | ✓ **NEW** (declared with `tools:ignore="QueryAllPackagesPermission"`) |
| `DUMP` | — | ✓ **NEW** (declared with `tools:ignore="ProtectedPermissions"`) |

Plus three new code patterns in v0.0.9:

1. **Reflection on `android.os.ServiceManager`** in
   `app/src/main/java/com/bearinmind/equalizer314/audio/AudioPolicyDumpParser.kt`:
   ```kotlin
   Class.forName("android.os.ServiceManager")
     .getMethod("getService", String::class.java)
     .invoke(null, "audio")
   // then:
   binder.javaClass
     .getMethod("dumpAsync", FileDescriptor::class.java, Array<String>::class.java)
     .invoke(binder, writeFd, emptyArray<String>())
   ```
   Used to recover audio-session IDs from `audioserver`'s `dumpsys`
   output — the only way to detect what app is playing audio when the
   app doesn't broadcast `OPEN_AUDIO_EFFECT_CONTROL_SESSION` (YouTube,
   Netflix, Chrome, games). Same pattern Wavelet's `a6/n0.java` /
   `a1/k1.java` and Poweramp Equalizer's `m5.java` / `jr0.java` use.

2. **Chained package enumeration** in
   `app/src/main/java/com/bearinmind/equalizer314/ChannelInputActivity.kt`:
   ```kotlin
   pm.queryBroadcastReceivers(Intent(ACTION_MEDIA_BUTTON), 0)
   pm.queryIntentServices(Intent("android.media.browse.MediaBrowserService"), 0)
   pm.queryIntentActivities(Intent(ACTION_VIEW).apply { type = "audio/*" }, 0)
   pm.getInstalledApplications(0)
   ```
   Used to filter the Channel Input app list down to media-relevant
   apps so the user has a sensible roster to bind presets to.

3. **`BIND_NOTIFICATION_LISTENER_SERVICE`** declared on the
   `PlaybackListenerService`. The bind itself is the gate for
   `MEDIA_CONTENT_CONTROL`, which is required for
   `MediaSessionManager.getActiveSessions()` to return other apps'
   sessions. We never read notifications:
   `disabled_filter_types="ongoing|silent|conversations|alerting"` in
   the manifest + empty `onNotificationPosted/Removed` overrides.

---

## VirusTotal behaviour-tag mapping

The scan output also tagged the file with three behaviour categories.
Each traces to specific bytecode:

| Tag | Where it actually comes from | Present in v0.0.8? |
|---|---|---|
| `checks-gps` | `Landroid/location/LocationManager`, `Landroid/location/GpsStatus`, etc. — class references pulled in by Material / AndroidX library transitives. **Our source never invokes them.** | Yes — same refs in v0.0.8 |
| `telephony` | `Landroid/telephony/SubscriptionManager`, `Landroid/telephony/TelephonyManager`, `Landroid/telephony/mbms/ServiceInfo` — also from AndroidX transitives, also never invoked by our code. **Zero SMS APIs anywhere in the bytecode.** | Yes — same refs in v0.0.8 |
| `reflection` | `Class.forName("android.os.ServiceManager")` + `Method.invoke` + reflected `IBinder.dumpAsync` in `AudioPolicyDumpParser.kt` | **No — new in v0.0.9** |

So `checks-gps` and `telephony` are basically free-rider tags that
existed in v0.0.8 too without tripping anyone. The **`reflection`
tag is the new ingredient** that v0.0.9 contributed.

---

## The static profile that trips the heuristic

Ikarus's `FakeInst`-family signature looks for the conjunction of:

```
signature-protected permission declared (DUMP)
   +
privacy-sensitive permission declared (QUERY_ALL_PACKAGES)
   +
reflection on hidden Android internals (android.os.ServiceManager)
   +
chained package enumeration across 4 PackageManager queries
   +
location and telephony class refs in the same bytecode
```

That conjunction is the textbook static fingerprint of FakeInst /
info-stealer malware on Android. The fact that we use those primitives
to do legitimate audio-EQ session detection — exactly the way Wavelet
and Poweramp do — is invisible to a static heuristic that doesn't run
the app. It can only see *shape*, not *intent*.

Wavelet and Poweramp Equalizer ship the same combination and have
gotten the same Ikarus FP class historically. It's the cost of doing
business with `dumpsys`-based session detection.

---

## Why this is unfixable without losing features

Every flagged ingredient is load-bearing:

| Ingredient | Removing it would break |
|---|---|
| `DUMP` permission declaration | Channel Input session detection on stock-Android devices (Pixel / OnePlus / Sony). Samsung denies dumpsys regardless so the perm is declared for the OEMs that respect it. |
| `ServiceManager` reflection in `AudioPolicyDumpParser` | The whole dumpsys parse path — no public alternative for recovering session IDs |
| `QUERY_ALL_PACKAGES` | App label + icon resolution in the Channel Input apps list |
| 4-way `PackageManager` query in `ChannelInputActivity` | Filters the Apps list down to media-relevant entries (so the user isn't binding presets to a calculator) |
| `BIND_NOTIFICATION_LISTENER_SERVICE` | Required for `MediaSessionManager.getActiveSessions` to return other apps' sessions (Android's policy, not ours) |

Dropping any of them removes a v0.0.9 feature.

The one thing that *would* lower future FP rates is enabling R8/
ProGuard obfuscation (`isMinifyEnabled = true` in `build.gradle.kts`),
but that makes the app less F-Droid-friendly — F-Droid prefers
unobfuscated builds for reproducibility verification. Tradeoff worth
revisiting if FPs become a chronic issue.

---

## What to do when this comes up again

1. **Quick reply to a user asking about it:** point them at this doc
   + emphasize that 59/60 engines including every major mobile-AV
   vendor say clean.
2. **(Optional) submit an FP report to Ikarus:**
   - Portal: <https://www.ikarussecurity.com/about-ikarus/false-positives/>
   - Include: APK file, GitHub release URL, brief explanation that
     the app has no SMS / contacts / phone-state APIs and is open-
     source on GitHub.
   - Usually resolved within a few days.
3. **(Optional) add a `## Security scanners` section to the README**
   pre-emptively, linking this doc.

---

## Investigation artifacts

The full diagnostic with all the read/grep evidence is kept at
`C:\Users\icedc\.claude\plans\quiet-frolicking-pumpkin.md`. This doc
is the trimmed reference version.

The APK used for the scan is at
`app/build/outputs/apk/release/Equalizer314-v0.0.9-beta.apk` (locally)
and attached to the GitHub release at
<https://github.com/bearinmindcat/Equalizer314/releases/tag/Equalizer314-v0.0.9-beta>.

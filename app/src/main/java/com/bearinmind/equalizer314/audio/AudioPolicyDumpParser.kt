package com.bearinmind.equalizer314.audio

import android.content.Context
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import com.bearinmind.equalizer314.BuildConfig
import java.io.BufferedReader
import java.io.FileReader
import java.util.regex.Pattern

/**
 * Recovers the **session ID** of every currently-playing media stream, including apps that never
 * broadcast `OPEN_AUDIO_EFFECT_CONTROL_SESSION` (YouTube, Netflix, Chrome). Public APIs expose
 * [android.media.AudioPlaybackConfiguration] but not the audio-session ID, which is required to
 * attach a per-session [android.media.audiofx.DynamicsProcessing] effect. Trick (from Wavelet/Poweramp):
 * reflect `ServiceManager.getService("audio")`, pipe its binder's `dumpAsync(fd, args)` into a
 * `BufferedReader`, and parse the `AudioPlaybackConfiguration` table.
 *
 * Format differs per Android version / OEM:
 * - Poweramp: lines start `  AudioPlaybackConfiguration ` with `u/pid:<UID>/<PID>`, `usage=USAGE_MEDIA`, `session:<N>`.
 * - Wavelet: simpler `Session ID: <N>; UID: <UID>`.
 * Poweramp parser first (richer), fall back to Wavelet if prefix never appears. On any failure
 * (DUMP denied, dump rejected, format unrecognised) returns empty map; caller falls back to
 * public-API-only path (package name, no session ID). Reflection confined here to keep the rest hidden-API-free.
 */
object AudioPolicyDumpParser {

    private const val TAG = "AudioPolicyDumpParser"

    /** Wavelet's regex (see `SessionListenerService.f2179d`). */
    private val WAVELET_LINE: Pattern =
        Pattern.compile("Session\\sID:\\s(\\d+);?\\sUID:?\\s(\\d+)")

    /** Pulls `u/pid:<UID>/<PID>` from a Poweramp-format line. */
    private val POWERAMP_UID_PID: Pattern =
        Pattern.compile("u/pid:(\\d+)/(\\d+)")

    /** Pulls session ids from variants such as:
 *  `session ID: <N>`, `sessionId:<N>`, and `session:<N>`.
 */
    private val POWERAMP_SESSION: Pattern =
    Pattern.compile(
        "session(?:\\s*ID)?\\s*:\\s*(\\d+)",
        Pattern.CASE_INSENSITIVE
    )

    /** Dumps the audio service, returning playing apps grouped by package name. Each app may have
     *  multiple concurrent sessions (e.g. ExoPlayer pre-buffering the next track).
     *
     *  @param timeoutMs hard ceiling on the blocking pipe read (dump normally completes in a few ms;
     *         if audioserver stalls we abandon rather than block the caller's thread forever). */
    fun dump(context: Context, timeoutMs: Long = 1500L): Map<String, Set<Int>> {
        return try {
            dumpInternal(context, timeoutMs)
        } catch (t: Throwable) {
            // Failure modes (all same caller-facing result): SecurityException (DUMP denied),
            // reflection SDK-blocklist hit on Android 14+, OOM on a huge dump, IO errors.
            Log.w(TAG, "dump failed, falling back to public-API-only path", t)
            emptyMap()
        }
    }

    private fun dumpInternal(context: Context, timeoutMs: Long): Map<String, Set<Int>> {
        val binder = obtainAudioBinder()
if (binder == null) {
    Log.w(TAG, "audio binder is null")
    return emptyMap()
}
Log.d(TAG, "audio binder acquired: ${binder.javaClass.name}")
        val pipe = ParcelFileDescriptor.createPipe()
        val readFd = pipe[0]
        val writeFd = pipe[1]

        // audioserver closes its copy of the write-end when done. We must close OUR copy as soon
        // as the binder has it, otherwise the reader never sees EOF.
        try {
            invokeDumpAsync(binder, writeFd.fileDescriptor)
        } finally {
            try { writeFd.close() } catch (_: Throwable) {}
        }

        // Bound the blocking read. Caller already runs on a HandlerThread, so stay single-threaded.
        val deadline = System.currentTimeMillis() + timeoutMs
        val unmatched = mutableListOf<String>()
        val uidToSessions = mutableMapOf<Int, MutableSet<Int>>()
        var lineCount = 0

        try {
            BufferedReader(FileReader(readFd.fileDescriptor)).use { reader ->
                while (true) {
                    if (System.currentTimeMillis() > deadline) {
                        Log.w(TAG, "dump read timed out after ${timeoutMs}ms")
                        break
                    }
                    val line = reader.readLine() ?: break
                    lineCount++
                    if (tryParsePowerampLine(line, uidToSessions)) continue
                    if (tryParseWaveletLine(line, uidToSessions)) continue
                    // Sample unrecognised lines for format-drift triage (debug builds only).
                    if (BuildConfig.DEBUG && unmatched.size < 20 && line.isNotBlank()) {
                        unmatched.add(line)
                    }
                }
            }
        } finally {
            try { readFd.close() } catch (_: Throwable) {}
        }

        if (BuildConfig.DEBUG && uidToSessions.isEmpty() && unmatched.isNotEmpty()) {
            Log.d(TAG, "no rows matched; first ${unmatched.size} unmatched lines for triage:")
            unmatched.forEach { Log.d(TAG, "  | $it") }
        }
        Log.d(
    TAG,
    "audio dump finished: lines=$lineCount parsedSessions=${uidToSessions.values.sumOf { it.size }}"
)

        return resolveUidsToPackages(context, uidToSessions)
    }

    /** Tries the Poweramp prefix format. Returns true when the line contributed a UID + session pair
     *  (or was a valid prefix line deliberately skipped, e.g. `SoundPool`). */
    private fun tryParsePowerampLine(
        line: String,
        out: MutableMap<Int, MutableSet<Int>>,
    ): Boolean {
        // audioserver indents `AudioPlaybackConfiguration` two spaces; some OEM forks drop them.
        val isPrefix = line.startsWith("  AudioPlaybackConfiguration ") ||
            line.startsWith("AudioPlaybackConfiguration ") ||
            line.startsWith("  ID:") ||
            line.startsWith("ID:")
        if (!isPrefix) return false

        // SoundPool players (UI clicks, alarm tones, game SFX) aren't music-stream candidates.
        if (line.contains("type:android.media.SoundPool")) return true
        // Only music-ish usage tags get EQ. USAGE_MEDIA = music/video; USAGE_UNKNOWN = untagged third-party players.
        if (!line.contains("USAGE_MEDIA") && !line.contains("USAGE_UNKNOWN")) return true

        val uidMatch = POWERAMP_UID_PID.matcher(line)
        if (!uidMatch.find()) return true   // prefix matched but no UID — skip
        val uid = uidMatch.group(1)?.toIntOrNull() ?: return true

        val sessionMatch = POWERAMP_SESSION.matcher(line)
        if (!sessionMatch.find()) return true
        val sid = sessionMatch.group(1)?.toIntOrNull() ?: return true
        if (sid <= 0) return true            // session 0 is the global mix

        out.getOrPut(uid) { mutableSetOf() }.add(sid)
        return true
    }

    /** Wavelet's terser format. Only fires when the Poweramp parser found nothing on this line. */
    private fun tryParseWaveletLine(
        line: String,
        out: MutableMap<Int, MutableSet<Int>>,
    ): Boolean {
        val m = WAVELET_LINE.matcher(line)
        if (!m.find()) return false
        val sid = m.group(1)?.toIntOrNull() ?: return false
        val uid = m.group(2)?.toIntOrNull() ?: return false
        if (sid <= 0) return false
        out.getOrPut(uid) { mutableSetOf() }.add(sid)
        return true
    }

    /** Resolves a raw UID map to package names. Our own UID is dropped so the global session-0 DP
     *  doesn't show up. Shared-UID handling: one UID may map to several packages (e.g.
     *  `com.google.android.gms`); we pick index [0] (the documented "primary" package, as Poweramp's
     *  `i0.java:925-928` does) — exploding to N rows for one session would add N misleading
     *  "Now playing" entries. Wavelet avoids this via `MediaController.getPackageName()`, unavailable here. */
    private fun resolveUidsToPackages(
        context: Context,
        uidToSessions: Map<Int, Set<Int>>,
    ): Map<String, Set<Int>> {
        val pm = context.packageManager
        val ourUid = context.applicationInfo.uid
        val out = mutableMapOf<String, MutableSet<Int>>()
        for ((uid, sids) in uidToSessions) {
            if (uid == ourUid) continue
            val pkg = pm.getPackagesForUid(uid)?.firstOrNull() ?: continue
            out.getOrPut(pkg) { mutableSetOf() }.addAll(sids)
        }
        return out
    }

    /** Cached binder — reused across calls (via `IBinder.isBinderAlive`) until audioserver dies. */
    @Volatile private var cachedBinder: IBinder? = null

    private fun obtainAudioBinder(): IBinder? {
        cachedBinder?.takeIf { it.isBinderAlive }?.let { return it }
        val serviceManagerClass = Class.forName("android.os.ServiceManager")
        val getService = serviceManagerClass.getMethod("getService", String::class.java)
        // "audio" is the AudioFlinger-side service emitting AudioPlaybackConfiguration rows;
        // "media.audio_policy" works on some versions too, but "audio" has widest coverage.
        val obj = getService.invoke(null, "audio")
        val binder = obj as? IBinder ?: return null
        cachedBinder = binder
        return binder
    }

    /** IBinder.dumpAsync (public since API 24), signature `(FileDescriptor, String[])`. Called via
     *  reflection so a stricter future hidden-API list can't break the rest of the parser. */
    private fun invokeDumpAsync(binder: IBinder, writeFd: java.io.FileDescriptor) {
        val dumpAsync = binder.javaClass.getMethod(
            "dumpAsync",
            java.io.FileDescriptor::class.java,
            Array<String>::class.java,
        )
        dumpAsync.invoke(binder, writeFd, emptyArray<String>())
    }
}

package com.music.bitchord.download

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import com.music.bitchord.data.settings.AppSettings
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.math.min

/**
 * Half a song, honestly listened to, is a download.
 *
 * Fed from the same sampler as
 * [com.music.bitchord.data.stats.ListeningRecorder] — the one loop in the
 * player that ticks only while audio is actually coming out — and it counts
 * the way that recorder counts, because the two are answering the same
 * question and should never disagree: wall-clock time between ticks, each
 * step capped at [MAX_STEP_MS], position never consulted. A seek therefore
 * contributes nothing it didn't play through, a pause contributes nothing at
 * all, and a loop of the same track contributes no more than the track is
 * long before the cap in [commitSession] is applied.
 *
 * Totals are kept per track and written down, so half a song heard across
 * three sittings is still half a song. When a track's total crosses half its
 * length it is put through the ordinary download door — [Downloads.enqueue] —
 * with everything that comes with that: it shows in the download manager
 * while it fetches, with the queue's own progress, and lands in Downloads
 * like any tapped-for download, encrypted the same way. Nothing here keeps a
 * second list of what it has saved; [Downloads] is the only record, and a
 * track already there, queued or running is left alone.
 *
 * Two kinds of track are never candidates: one already on the device as the
 * listener's own file (it has a `localUri`; saving it again would only copy
 * it), and any track while downloads are not allowed on this connection —
 * the Wi-Fi-only setting is a promise about data, and an automatic download
 * is exactly the kind it was made for. The totals are kept either way, so a
 * track that crossed the line on mobile data is saved at the next listen on
 * a connection that allows it.
 */
object AutoSave {

    private const val TAG = "BitChord"
    private const val PREFS_NAME = "esei_autosave"
    private const val KEY_TOTALS = "listened_totals"
    private const val KEY_DURATIONS = "listened_durations"

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), Long.serializer())

    private var prefs: SharedPreferences? = null

    /** Every track's banked listening, in milliseconds, across sittings. */
    private val totals = mutableMapOf<String, Long>()

    /** The length each track was last known to be, for the half of it. */
    private val durations = mutableMapOf<String, Long>()

    private var currentId: String? = null
    private var currentSong: Song? = null
    private var currentDurationMs = 0L
    private var sessionMs = 0L
    private var lastSampleAt = 0L

    /**
     * One tick of the player's sampler, on the track that is playing.
     *
     * [durationMs] is the player's figure when it has one; the row's stated
     * runtime stands in until it does.
     */
    @Synchronized
    fun onSample(context: Context, song: Song, durationMs: Long) {
        ensureLoaded(context)
        val now = System.currentTimeMillis()
        if (song.videoId != currentId) {
            commitSession()
            currentId = song.videoId
            currentSong = song
            sessionMs = 0L
            lastSampleAt = now
            currentDurationMs = durationMs.takeIf { it > 0 } ?: song.durationMillis()
            return
        }
        val step = (now - lastSampleAt).coerceIn(0L, MAX_STEP_MS)
        lastSampleAt = now
        if (durationMs > 0) currentDurationMs = durationMs
        if (step <= 0L) return
        sessionMs += step
        maybeSave(context)
    }

    /** Playback stopped or paused: bank whatever this sitting amounted to. */
    @Synchronized
    fun onStopped() {
        commitSession()
        currentId = null
        currentSong = null
        sessionMs = 0L
    }

    /**
     * Bank the sitting under the track it belongs to.
     *
     * One sitting can contribute no more than the track is long: repeat-one
     * left running all night is one track's worth of listening toward saving
     * it, not forty.
     */
    private fun commitSession() {
        val id = currentId ?: return
        if (sessionMs <= 0L) return
        val length = currentDurationMs.takeIf { it > 0 } ?: durations[id] ?: 0L
        val contribution = if (length > 0) min(sessionMs, length) else sessionMs
        totals[id] = (totals[id] ?: 0L) + contribution
        if (length > 0) durations[id] = length
        persist()
    }

    /** Ask for the download, if this track has earned one and hasn't got one. */
    private fun maybeSave(context: Context) {
        val song = currentSong ?: return
        val id = song.videoId
        // The listener's own files are already saved in every sense that
        // matters here.
        if (song.localUri != null) return
        val length = currentDurationMs.takeIf { it > 0 } ?: durations[id] ?: return
        if (length <= 0) return
        val heard = (totals[id] ?: 0L) + min(sessionMs, length)
        if (heard < length / 2) return
        if (id in Downloads.saved.value) return
        val state = Downloads.active.value[id]
        if (state is DownloadState.Queued || state is DownloadState.Running) return
        // Refused quietly rather than through [Downloads.enqueue], which
        // would leave a failed row in the manager for a download nobody
        // tapped for. The totals stand, so this is asked again on the next
        // tick of a later listen.
        if (!AppSettings.downloadsAllowedNow && !Downloads.servableFromCache(id)) return
        Log.d(TAG, "$id listened past halfway; saving it")
        Downloads.enqueue(context.applicationContext, song)
    }

    private fun ensureLoaded(context: Context) {
        if (prefs != null) return
        val loaded = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = loaded
        runCatching {
            json.decodeFromString(serializer, loaded.getString(KEY_TOTALS, null) ?: "{}")
        }.getOrDefault(emptyMap()).let { totals.putAll(it) }
        runCatching {
            json.decodeFromString(serializer, loaded.getString(KEY_DURATIONS, null) ?: "{}")
        }.getOrDefault(emptyMap()).let { durations.putAll(it) }
    }

    private fun persist() {
        val store = prefs ?: return
        store.edit()
            .putString(KEY_TOTALS, json.encodeToString(serializer, totals))
            .putString(KEY_DURATIONS, json.encodeToString(serializer, durations))
            .apply()
    }

    /** As [com.music.bitchord.data.stats.ListeningRecorder]'s cap, for the same reason. */
    private const val MAX_STEP_MS = 8_000L
}

package com.music.bitchord.download

import android.content.Context
import android.net.Uri
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.LikeState
import com.music.bitchord.data.OfflinePlaylists
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.stats.ListeningStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The ceiling on Downloads, and what gives when it is reached.
 *
 * The listener picks the ceiling in Settings —
 * [AppSettings.downloadStorageCapGb] — and this is run after every download
 * lands (from [Downloads.run], the one door every save comes through) and
 * once at launch (from the application), because a library can be over the
 * line before any new save arrives: the cap was lowered, or a build without
 * the cap left more behind than the cap allows.
 *
 * While the encrypted saves total more than the ceiling, saves are removed
 * — file and record together, through [Downloads.delete] — starting with
 * the ones listened to least. Listening is [ListeningStats]' all-time total
 * per track, the same numbers Replay is drawn from; a save with no listening
 * record counts as none at all, which puts a bulk download nobody has played
 * since ahead of anything in rotation. Between saves heard equally little,
 * the older goes first.
 *
 * Four kinds of save are never removed here, whatever the total says:
 *
 *  - **Liked.** A save in the device's Liked playlist (its uri is in
 *    [OfflinePlaylists.liked] — the heart a downloaded track is given from
 *    its own page) or rated LIKE on the account this session
 *    ([LikeState.overrides]).
 *  - **Young.** A save that landed less than a week ago. This is also what
 *    keeps the save that has *just* landed — the one whose arrival set this
 *    running — out of its own reach, however large it is: a single track
 *    bigger than the ceiling is kept, and Downloads simply sits over the
 *    line until it ages.
 *  - **Playing.** The track in the player now — asked of [AutoSave], which
 *    is fed by the same sampler and knows the playing track's ids the way
 *    the download record does.
 *  - **In flight.** A save whose track is queued or running again — a
 *    re-download of a track already saved, replacing its file.
 *
 * If only protected saves remain and the total is still over the ceiling,
 * this stops. The overage is the listener's to resolve by hand; removing a
 * save the listener has marked, or one still warm from landing, to satisfy
 * a number they set is the one outcome worse than being over it.
 *
 * Removal is quiet by design: no notification, no dialog. The Downloads
 * page's own total is drawn from the same records this edits, so the figure
 * there is simply right afterwards — and a save that has gone is one the
 * listener had not played, liked or recently asked for.
 */
object StorageCap {

    private const val TAG = "BitChord"

    /** A gigabyte as the phone counts storage: 1024 to the third. */
    private const val BYTES_PER_GB = 1024L * 1024L * 1024L

    /** A save younger than this, in seconds, is never removed. */
    private const val GRACE_SECONDS = 7L * 24L * 60L * 60L

    /**
     * One run at a time. Downloads land in parallel, and two runs reading
     * the same records and deleting against the same total would each count
     * the other's removals as still to come.
     */
    private val gate = Mutex()

    /** One file on disk: every id the record holds for it, and its facts. */
    private class Save(
        val uri: String,
        val ids: List<String>,
        val bytes: Long,
        val listenedMs: Long,
        /** When the save landed, epoch seconds; null when nothing can date it. */
        val addedSeconds: Long?,
        val protected: Boolean,
    )

    suspend fun enforce(context: Context) = gate.withLock {
        withContext(Dispatchers.IO) { enforceLocked(context.applicationContext) }
    }

    private suspend fun enforceLocked(context: Context) {
        val capBytes = AppSettings.downloadStorageCapGb.value * BYTES_PER_GB
        var total = Downloads.savedStorageBytes()
        if (total <= capBytes) return

        val saves = snapshot(context)
        // Nothing removable, or nothing at all: the protected remainder is
        // the listener's to resolve, and saying so once in the log is the
        // whole of the ceremony.
        val candidates = saves.filter { !it.protected && it.bytes > 0 }
            .sortedWith(compareBy<Save> { it.listenedMs }.thenBy { it.addedSeconds ?: Long.MAX_VALUE })
        if (candidates.isEmpty()) {
            Log.d(TAG, "downloads are over the storage cap but every save is protected; leaving them")
            return
        }
        for (save in candidates) {
            if (total <= capBytes) break
            save.ids.forEach { Downloads.delete(context, it) }
            total -= save.bytes
            Log.d(TAG, "removed a save over the storage cap: ${save.uri} (${save.bytes} bytes)")
        }
    }

    /** Every encrypted save on disk, with the facts the order and guards are read from. */
    private suspend fun snapshot(context: Context): List<Save> {
        val recorded = Downloads.saved.value
        val added = Downloads.savedAddedSeconds()
        val listening = listeningTotals()
        val likedUris = OfflinePlaylists.liked.value
        val likedIds = LikeState.overrides.value
            .filterValues { it == LikeStatus.LIKE }.keys
        val inFlight = Downloads.active.value
            .filterValues { it is DownloadState.Queued || it is DownloadState.Running }.keys
        // The playing track under the id the queue knows it by, and the save
        // it resolves to: a music video and the catalogue track it was saved
        // as are two ids for one file, and either can be the one playing.
        val playingId = AutoSave.progress.value?.song?.videoId
        val playingUri = playingId?.let { Downloads.verifiedSavedUri(it) }
        val nowSeconds = System.currentTimeMillis() / 1_000

        return recorded.entries
            .filter { (_, uriString) -> isEncryptedSave(uriString) }
            .groupBy({ it.value }, { it.key })
            .mapNotNull { (uriString, ids) ->
                val file = Uri.parse(uriString).path?.let(::File) ?: return@mapNotNull null
                // The youngest date any of the file's ids claims: the aliases
                // were written together and agree, and where they somehow
                // don't, the generous reading is the safe one.
                val addedSeconds = ids.mapNotNull { added[it] }.maxOrNull()
                Save(
                    uri = uriString,
                    ids = ids,
                    bytes = file.length(),
                    listenedMs = ids.sumOf { listening[it] ?: 0L },
                    addedSeconds = addedSeconds,
                    protected =
                        uriString in likedUris ||
                            ids.any { it in likedIds } ||
                            ids.any { it in inFlight } ||
                            (playingId != null && playingId in ids) ||
                            (playingUri != null && playingUri == uriString) ||
                            // Undated counts as protected rather than as
                            // ancient: nothing dates it, so nothing may
                            // pronounce it a week old.
                            addedSeconds == null ||
                            nowSeconds - addedSeconds < GRACE_SECONDS,
                )
            }
    }

    /** All-time listening per track id, across every month [ListeningStats] keeps. */
    private suspend fun listeningTotals(): Map<String, Long> {
        val buckets = runCatching { ListeningStats.exportAll() }.getOrDefault(emptyList())
        val totals = HashMap<String, Long>()
        buckets.forEach { bucket ->
            bucket.tracks.forEach { track -> totals[track.id] = (totals[track.id] ?: 0L) + track.ms }
        }
        return totals
    }

    private fun isEncryptedSave(uriString: String): Boolean =
        EncryptedStore.isStorePath(runCatching { Uri.parse(uriString).path }.getOrNull())
}

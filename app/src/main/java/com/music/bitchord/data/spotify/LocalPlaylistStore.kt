package com.music.bitchord.data.spotify

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class LocalPlaylist(
    val id: String,
    val title: String,
    val songs: List<Song>,
) {
    val browseId: String get() = "local:playlist:$id"
}

/**
 * The list edits behind [LocalPlaylistStore.addSongs] and
 * [LocalPlaylistStore.removeSong], kept free of the store's
 * SharedPreferences so the rules can be unit-tested directly.
 *
 * A playlist is matched the way [LocalPlaylistStore.getPlaylist] matches:
 * by its raw id, by its browse id, or by either with a `VL` in front — the
 * shapes the picker's rows and the pages that open it actually carry.
 */
internal object LocalPlaylistEdits {

    private fun cleanId(id: String): String =
        id.removePrefix("local:playlist:").removePrefix("VL")

    private fun List<LocalPlaylist>.indexOf(id: String): Int {
        val clean = cleanId(id)
        return indexOfFirst { it.id == clean || it.id == id || it.browseId == id }
    }

    /**
     * Appends [songs] to the named playlist, skipping any whose videoId is
     * already there (or repeated within [songs] itself), in the order given.
     */
    fun addSongs(
        playlists: List<LocalPlaylist>,
        playlistId: String,
        songs: List<Song>,
    ): Pair<List<LocalPlaylist>, LocalPlaylistStore.AddResult> {
        val index = playlists.indexOf(playlistId)
        if (index < 0) return playlists to LocalPlaylistStore.AddResult(0, 0, found = false)
        val target = playlists[index]
        val known = target.songs.mapTo(HashSet()) { it.videoId }
        val distinct = songs.distinctBy { it.videoId }
        val fresh = distinct.filter { known.add(it.videoId) }
        val result = LocalPlaylistStore.AddResult(
            added = fresh.size,
            alreadyThere = distinct.size - fresh.size,
            found = true,
        )
        if (fresh.isEmpty()) return playlists to result
        val updated = playlists.toMutableList()
        updated[index] = target.copy(songs = target.songs + fresh)
        return updated to result
    }

    /** Takes every row with [videoId] out of the named playlist. */
    fun removeSong(
        playlists: List<LocalPlaylist>,
        playlistId: String,
        videoId: String,
    ): Pair<List<LocalPlaylist>, Boolean> {
        val index = playlists.indexOf(playlistId)
        if (index < 0) return playlists to false
        val target = playlists[index]
        if (target.songs.none { it.videoId == videoId }) return playlists to false
        val updated = playlists.toMutableList()
        updated[index] = target.copy(songs = target.songs.filterNot { it.videoId == videoId })
        return updated to true
    }
}

object LocalPlaylistStore {
    private const val PREF_NAME = "bitchord_local_playlists"
    private const val KEY_PLAYLISTS = "playlists_json"

    private lateinit var prefs: SharedPreferences

    private val _playlists = MutableStateFlow<List<LocalPlaylist>>(emptyList())
    val playlists = _playlists.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        load()
    }

    private fun load() {
        if (!this::prefs.isInitialized) return
        val raw = prefs.getString(KEY_PLAYLISTS, null) ?: return
        runCatching {
            val list = mutableListOf<LocalPlaylist>()
            val jsonArray = JSONArray(raw)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val id = obj.getString("id")
                val title = obj.getString("title")
                val songsArray = obj.getJSONArray("songs")
                val songs = mutableListOf<Song>()
                for (j in 0 until songsArray.length()) {
                    val sObj = songsArray.getJSONObject(j)
                    val song = Song(
                        videoId = sObj.optString("videoId", ""),
                        title = sObj.optString("title", ""),
                        artist = sObj.optString("artist", ""),
                        thumbnailUrl = sObj.optString("thumbnailUrl", null).takeIf { !it.isNullOrBlank() && it != "null" },
                        durationText = sObj.optString("durationText", null).takeIf { !it.isNullOrBlank() && it != "null" },
                        artistId = sObj.optString("artistId", null).takeIf { !it.isNullOrBlank() && it != "null" },
                        albumId = sObj.optString("albumId", null).takeIf { !it.isNullOrBlank() && it != "null" },
                        albumName = sObj.optString("albumName", null).takeIf { !it.isNullOrBlank() && it != "null" },
                        isVideo = sObj.optBoolean("isVideo", false),
                    )
                    songs.add(song)
                }
                list.add(LocalPlaylist(id, title, songs))
            }
            _playlists.value = list
        }
    }

    fun savePlaylist(title: String, songs: List<Song>): LocalPlaylist {
        val id = "sp_local_" + System.currentTimeMillis()
        val playlist = LocalPlaylist(id = id, title = title, songs = songs)
        val updated = listOf(playlist) + _playlists.value.filterNot { it.id == id }
        _playlists.value = updated
        persist(updated)
        return playlist
    }

    /** What an [addSongs] call did: [found] is false when no playlist carries the id. */
    class AddResult(val added: Int, val alreadyThere: Int, val found: Boolean)

    /**
     * Adds [songs] to the playlist [id] names (any of the shapes
     * [getPlaylist] accepts), skipping tracks already in it by videoId —
     * a duplicate row is not something the user asked for, and YouTube's own
     * playlists are held to the same rule by their caller.
     */
    fun addSongs(id: String, songs: List<Song>): AddResult {
        val (updated, result) = LocalPlaylistEdits.addSongs(_playlists.value, id, songs)
        if (result.added > 0) {
            _playlists.value = updated
            persist(updated)
        }
        return result
    }

    /** Removes [videoId] from the playlist [id] names; true when a row went. */
    fun removeSong(id: String, videoId: String): Boolean {
        val (updated, removed) = LocalPlaylistEdits.removeSong(_playlists.value, id, videoId)
        if (removed) {
            _playlists.value = updated
            persist(updated)
        }
        return removed
    }

    fun deletePlaylist(id: String) {
        val updated = _playlists.value.filterNot { it.id == id || it.browseId == id || it.id == id.removePrefix("local:playlist:") }
        _playlists.value = updated
        persist(updated)
    }

    fun renamePlaylist(id: String, newTitle: String) {
        val cleanId = id.removePrefix("local:playlist:").removePrefix("VL")
        val updated = _playlists.value.map {
            if (it.id == cleanId || it.id == id || it.browseId == id) {
                it.copy(title = newTitle)
            } else it
        }
        _playlists.value = updated
        persist(updated)
    }

    fun getPlaylist(id: String): LocalPlaylist? {
        val cleanId = id.removePrefix("local:playlist:").removePrefix("VL")
        return _playlists.value.firstOrNull { it.id == cleanId || it.id == id || it.browseId == id }
    }

    private fun persist(list: List<LocalPlaylist>) {
        if (!this::prefs.isInitialized) return
        runCatching {
            val jsonArray = JSONArray()
            for (playlist in list) {
                val pObj = JSONObject()
                pObj.put("id", playlist.id)
                pObj.put("title", playlist.title)
                val songsArray = JSONArray()
                for (song in playlist.songs) {
                    val sObj = JSONObject()
                    sObj.put("videoId", song.videoId)
                    sObj.put("title", song.title)
                    sObj.put("artist", song.artist)
                    sObj.put("thumbnailUrl", song.thumbnailUrl)
                    sObj.put("durationText", song.durationText)
                    sObj.put("artistId", song.artistId)
                    sObj.put("albumId", song.albumId)
                    sObj.put("albumName", song.albumName)
                    sObj.put("isVideo", song.isVideo)
                    songsArray.put(sObj)
                }
                pObj.put("songs", songsArray)
                jsonArray.put(pObj)
            }
            prefs.edit().putString(KEY_PLAYLISTS, jsonArray.toString()).apply()
        }
    }
}

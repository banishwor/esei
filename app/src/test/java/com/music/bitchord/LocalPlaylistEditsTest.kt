package com.music.bitchord

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.spotify.LocalPlaylist
import com.music.bitchord.data.spotify.LocalPlaylistEdits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The add/remove rules behind the device store's playlists — what creating a
 * playlist without an account, and filing tracks into it, stand on.
 */
class LocalPlaylistEditsTest {

    private fun song(videoId: String) = Song(
        videoId = videoId,
        title = "Song $videoId",
        artist = "Artist",
        thumbnailUrl = null,
        fromAutoplay = false,
    )

    private fun playlists(vararg songs: Song) = listOf(
        LocalPlaylist(id = "sp_local_1", title = "Mine", songs = songs.toList()),
        LocalPlaylist(id = "sp_local_2", title = "Other", songs = emptyList()),
    )

    @Test
    fun addAppendsNewSongsInOrder() {
        val start = playlists(song("a"))
        val (updated, result) = LocalPlaylistEdits.addSongs(
            start, "sp_local_1", listOf(song("b"), song("c")),
        )

        assertTrue(result.found)
        assertEquals(2, result.added)
        assertEquals(0, result.alreadyThere)
        assertEquals(listOf("a", "b", "c"), updated[0].songs.map { it.videoId })
        // The playlist not named is untouched, by identity as much as value.
        assertSame(start[1], updated[1])
    }

    @Test
    fun addSkipsSongsAlreadyThereByVideoId() {
        val start = playlists(song("a"), song("b"))
        val (updated, result) = LocalPlaylistEdits.addSongs(
            start, "sp_local_1", listOf(song("b"), song("c")),
        )

        assertEquals(1, result.added)
        assertEquals(1, result.alreadyThere)
        assertEquals(listOf("a", "b", "c"), updated[0].songs.map { it.videoId })
    }

    @Test
    fun addCountsARepeatedIncomingSongOnce() {
        val (updated, result) = LocalPlaylistEdits.addSongs(
            playlists(), "sp_local_1", listOf(song("a"), song("a")),
        )

        assertEquals(1, result.added)
        assertEquals(0, result.alreadyThere)
        assertEquals(1, updated[0].songs.size)
    }

    @Test
    fun addToAMissingPlaylistReportsNotFoundAndChangesNothing() {
        val start = playlists(song("a"))
        val (updated, result) = LocalPlaylistEdits.addSongs(
            start, "sp_local_nope", listOf(song("b")),
        )

        assertFalse(result.found)
        assertEquals(0, result.added)
        assertSame(start, updated)
    }

    @Test
    fun addAcceptsTheBrowseIdShapesThePickerCarries() {
        val start = playlists()
        val byBrowseId = LocalPlaylistEdits.addSongs(start, "local:playlist:sp_local_1", listOf(song("a")))
        assertTrue(byBrowseId.second.found)
        val byVlBrowseId = LocalPlaylistEdits.addSongs(start, "VLsp_local_1", listOf(song("a")))
        assertTrue(byVlBrowseId.second.found)
    }

    @Test
    fun removeTakesTheSongOutByVideoId() {
        val start = playlists(song("a"), song("b"))
        val (updated, removed) = LocalPlaylistEdits.removeSong(start, "sp_local_1", "a")

        assertTrue(removed)
        assertEquals(listOf("b"), updated[0].songs.map { it.videoId })
    }

    @Test
    fun removeOfAMissingSongIsANoOp() {
        val start = playlists(song("a"))
        val (updated, removed) = LocalPlaylistEdits.removeSong(start, "sp_local_1", "zzz")

        assertFalse(removed)
        assertSame(start, updated)
    }

    @Test
    fun removeFromAMissingPlaylistIsANoOp() {
        val start = playlists(song("a"))
        val (updated, removed) = LocalPlaylistEdits.removeSong(start, "sp_local_nope", "a")

        assertFalse(removed)
        assertSame(start, updated)
    }
}

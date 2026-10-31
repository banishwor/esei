package com.music.bitchord.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.music.bitchord.R
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.playingRowMatcher
import com.music.bitchord.download.Downloads
import com.music.bitchord.ui.components.MessageState
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.SongRow
import com.music.bitchord.ui.components.SongRowSkeleton

/**
 * Home with no connection: the feed cannot load, so Home is the downloaded
 * songs instead — the encrypted saves, listed the way Downloads lists them
 * and playable through the same path. Shown only while
 * [com.music.bitchord.data.settings.AppSettings.hasNetwork] is false; the
 * moment a network returns, MainActivity swaps the ordinary feed back in.
 */
@Composable
fun OfflineHomeScreen(
    listState: LazyListState,
    currentSong: Song?,
    isPlaying: Boolean,
    contentPadding: PaddingValues,
    onSongClick: (List<Song>, Int) -> Unit,
) {
    val context = LocalContext.current
    var songs by remember { mutableStateOf<List<Song>?>(null) }
    LaunchedEffect(Unit) {
        songs = Downloads.getDownloadedSongs(context)
    }
    val loaded = songs
    val isCurrentRow = playingRowMatcher(loaded.orEmpty(), currentSong)

    LazyColumn(state = listState, contentPadding = contentPadding) {
        item(key = "offline-header") {
            Column(
                modifier = Modifier.padding(
                    start = PAGE_GUTTER,
                    end = PAGE_GUTTER,
                    top = 8.dp,
                    bottom = 16.dp,
                ),
            ) {
                Text(
                    text = stringResource(R.string.youre_offline),
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    text = stringResource(R.string.offline_home_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        when {
            loaded == null -> items(6, key = { "offline-skeleton-$it" }) { index ->
                SongRowSkeleton(index)
            }
            loaded.isEmpty() -> item(key = "offline-empty") {
                MessageState(stringResource(R.string.auto_empty_offline_title))
            }
            else -> itemsIndexed(loaded, key = { index, song -> "offline-${song.videoId}-$index" }) { index, song ->
                SongRow(
                    song = song,
                    isCurrent = isCurrentRow(song),
                    isPlaying = isCurrentRow(song) && isPlaying,
                    downloadedTint = null,
                    onClick = { onSongClick(loaded, index) },
                )
            }
        }
    }
}

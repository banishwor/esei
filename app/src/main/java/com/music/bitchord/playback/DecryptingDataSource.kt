package com.music.bitchord.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.music.bitchord.download.EncryptedStore
import java.io.File
import java.io.IOException

/**
 * Playback's half of the encrypted download store — see
 * [com.music.bitchord.download.EncryptedStore] for the format.
 *
 * A downloaded track's uri is an ordinary `file://` uri, and everything
 * downstream of the player decides what to do with it by that scheme, which
 * is what lets [com.music.bitchord.download.Downloads] keep answering saved
 * tracks exactly as it did when they were plain files. The bytes at the end
 * of the uri are not the audio, though, so the one place that opens `file://`
 * uris for the player — [AudioCache.playbackFactory] — hands the ones whose
 * header marks them as this store's to this instead of to the file source.
 *
 * Reads are ranged: [DataSpec.position] is where a seek lands, and the
 * reader underneath decrypts only the chunks that range touches, so a seek
 * into the middle of a track costs a chunk's decryption rather than the
 * whole file's.
 */
class DecryptingDataSource : DataSource {

    private var reader: EncryptedStore.Reader? = null
    private var uri: Uri? = null
    private var position = 0L
    private var remaining = 0L

    override fun addTransferListener(transferListener: TransferListener) {
        // Nothing to report to: bytes read here were already on the device,
        // and the listeners are bandwidth meters for the network chain.
    }

    override fun open(dataSpec: DataSpec): Long {
        val path = dataSpec.uri.path ?: throw IOException("No path on ${dataSpec.uri}")
        val opened = EncryptedStore.Reader(File(path))
        reader = opened
        uri = dataSpec.uri
        position = dataSpec.position
        val available = (opened.plainLength - position).coerceAtLeast(0L)
        remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
            available
        } else {
            minOf(dataSpec.length, available)
        }
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val active = reader ?: throw IOException("Read on an unopened source")
        val read = active.readAt(position, buffer, offset, minOf(length.toLong(), remaining).toInt())
        if (read < 0) return C.RESULT_END_OF_INPUT
        position += read
        remaining -= read
        return read
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        reader?.close()
        reader = null
        uri = null
    }
}

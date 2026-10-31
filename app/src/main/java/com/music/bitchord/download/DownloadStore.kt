package com.music.bitchord.download

import android.content.Context
import android.net.Uri
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.model.Song
import java.io.File
import java.io.OutputStream
import java.util.Locale

/**
 * Where a downloaded track goes, and how it gets there.
 *
 * The destination is [EncryptedStore]: a directory inside the app's own
 * private storage, and every byte written through here is encrypted on its
 * way in. Nothing lands in the shared Music folder and nothing is registered
 * with the media store, so a download is playable only inside this app — the
 * setting that used to offer the Music folder ("export compatible
 * downloads") is gone, and with it the whole scoped-storage crossing this
 * class used to exist to make.
 *
 * The write itself keeps the discipline the shared folder imposed: bytes go
 * to a `.part` sibling beside the final name and are renamed on completion,
 * so a cancelled download is never a half-file something can find and play.
 *
 * Nothing writes tags into a download any more, either — an encrypted file
 * has no container for a tagger to rewrite. The record in
 * [Downloads] is where a download's title, artist and cover live, and it was
 * already the better source for all three.
 */
object DownloadStore {

    private const val TAG = "BitChord"

    /**
     * The subfolder of Music downloads *used* to land in, before they moved
     * to [EncryptedStore]. Nothing is written there any more; the name is
     * kept because the local library still looks in it — what an older build
     * left there is the listener's own files now, listed with the rest of
     * Local Music.
     */
    const val FOLDER = "BitChord"

    /**
     * Whether saving needs `WRITE_EXTERNAL_STORAGE` asked for at runtime.
     *
     * It did, once, below API 29, when a download could land in the shared
     * Music folder. Downloads are written to the app's own private storage
     * now (see [EncryptedStore]), which no Android version asks a permission
     * for. Kept, answering no, because callers still ask the question in
     * places where the honest answer used to be conditional.
     */
    fun needsLegacyPermission(): Boolean = false

    // ---- Naming -------------------------------------------------------------

    /**
     * What the file is called: `Artist - Title.ext`.
     *
     * Artist first because a Music folder is sorted by name and nothing
     * else — no tags to group by — so leading with the artist is the only thing
     * that puts an album back together in the listing.
     */
    fun fileNameFor(song: Song, extension: String): String {
        val artist = sanitise(song.artist)
        val title = sanitise(song.title)
        val stem = when {
            artist.isEmpty() -> title
            title.isEmpty() -> artist
            else -> "$artist - $title"
        }.ifEmpty { song.videoId }
        return "${stem.take(MAX_STEM_CHARS).trimEnd()}.$extension"
    }

    /**
     * Everything a FAT32 volume, the media store or a shell would each object
     * to for its own reasons, plus the whitespace that survives them.
     */
    private fun sanitise(raw: String): String = raw
        .replace(ILLEGAL, " ")
        .replace(WHITESPACE, " ")
        .trim()
        .trim('.')

    private val ILLEGAL = Regex("""[\\/:*?"<>|\x00-\x1F]""")
    private val WHITESPACE = Regex("""\s+""")

    /** Long enough for anything real, short of the 255-byte filename ceiling. */
    private const val MAX_STEM_CHARS = 120

    /** What a file of some codec is called and what the store is told it is. */
    class Storable(val extension: String, val mimeType: String)

    /**
     * How to file a track of [codec], or null if it can't be filed.
     *
     * Only asked of manifests now, whose segments [ManifestFile] joins into one
     * of these — the closed list of containers the assembler can produce. A
     * plain file is named for what its opening bytes are — see
     * [Downloader.Container] — because a codec label alone can't tell raw ADTS
     * from AAC-in-MP4, and this table used to send every addon `aac` and `mp3`
     * to YouTube for want of an entry.
     *
     * Kept as a table rather than derived from the codec string because two of
     * these are not the identity mapping they look like. WAV's registered type
     * is `audio/x-wav` on Android, and ALAC ships inside an MP4 container, so an
     * ALAC file is an `.m4a` as far as both the store and [Mp4Tagger] are
     * concerned — the tagger works on the box tree and never asks what the
     * samples inside are.
     */
    fun storable(codec: String?): Storable? = when (codec?.lowercase(Locale.ROOT)?.trim()) {
        "flac", "x-flac" -> Storable("flac", "audio/flac")
        "wav", "x-wav", "wave" -> Storable("wav", "audio/x-wav")
        "alac", "m4a", "mp4", "eac3-joc", "ec3-joc", "dolby-atmos" -> Storable("m4a", "audio/mp4")
        else -> null
    }

    // ---- Lookup -------------------------------------------------------------

    /**
     * The uri of a file already saved under this name, or null.
     *
     * Worth asking before every download: a user who taps download twice
     * should be told they already have the track, not handed a second copy.
     */
    fun existing(context: Context, name: String): Uri? =
        privateFile(context, name).takeIf { it.exists() }?.let(Uri::fromFile)

    /**
     * Whether [uri] still names a file that is there.
     *
     * The record of what has been downloaded is kept by this app, but the files
     * are not this app's to keep: they sit in a folder built for the user to
     * manage, and one deleted from a file manager leaves the record behind
     * claiming a download that no longer exists. Cheap to ask, and the answer
     * is what stops the menu offering to delete nothing.
     */
    fun exists(context: Context, uri: Uri): Boolean = runCatching {
        if (uri.scheme == "file") return uri.path?.let { File(it).exists() } == true
        context.contentResolver.openFileDescriptor(uri, "r")?.use { true } == true
    }.getOrDefault(false)

    fun delete(context: Context, uri: Uri): Boolean = runCatching {
        if (uri.scheme == "file") {
            uri.path?.let { path ->
                val file = File(path)
                if (file.name == "playlist.m3u8" && file.parentFile?.parentFile?.name == "offline-hls") {
                    file.parentFile?.deleteRecursively() == true
                } else file.delete()
            } == true
        } else {
            context.contentResolver.delete(uri, null, null) > 0
        }
    }.onFailure { Log.w(TAG, "could not delete $uri: ${it.message}") }.getOrDefault(false)

    // ---- Writing ------------------------------------------------------------

    /**
     * A destination that exists but is not yet a finished file.
     *
     * Every path out of here is either [commit] or [abort]; there is no third
     * option, because the thing being protected against is a partial file
     * surviving a failure and looking like a whole one.
     */
    class Pending internal constructor(
        val uri: Uri,
        val name: String,
        /** The `.part` file being written, beside its final name. */
        private val part: File,
        /** What [part] is renamed to by [commit]. */
        private val target: File,
    ) {
        /**
         * The stream the download's bytes are written to — which is
         * [EncryptedStore]'s, so what reaches the disk is ciphertext from the
         * first byte on (see the store for the format). Closing it seals the
         * last chunk and writes the true length into the header; a stream
         * abandoned unclosed leaves a file that reads as empty.
         */
        fun openStream(): OutputStream = EncryptedStore.encryptingStream(part)

        /** @return the uri the finished file can be reached at. */
        fun commit(): Uri {
            if (!part.renameTo(target)) error("Could not finish writing $name")
            // No media scan, deliberately: the file is in private storage and
            // encrypted. Nothing outside this app may index it, list it or
            // play it — that is the point of where it lives.
            return Uri.fromFile(target)
        }

        fun abort() {
            part.delete()
        }
    }

    /**
     * Reserve [name] in the encrypted store and return somewhere to write it.
     *
     * @throws IllegalStateException if the folder can't be made — a failure
     *   worth surfacing, since it means the download cannot start rather than
     *   that it might not finish.
     */
    fun begin(context: Context, name: String, mimeType: String): Pending {
        val target = privateFile(context, name)
        val folder = target.parentFile ?: error("No download folder")
        if (!folder.exists() && !folder.mkdirs()) error("Could not create ${folder.path}")
        val part = File(folder, ".$name.part")
        part.delete()
        return Pending(Uri.fromFile(target), name, part = part, target = target)
    }

    private fun privateFile(context: Context, name: String) =
        EncryptedStore.fileFor(context, name)
}

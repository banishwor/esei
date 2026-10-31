package com.music.bitchord.download

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Where downloads live from Phase 2 on, and the only form they live in.
 *
 * Every downloaded track — tapped for, or saved by [AutoSave] after half of it
 * has been listened to — is written here: a directory inside the app's own
 * private storage, as a file of encrypted chunks. Nothing is written to the
 * shared Music folder, nothing is registered with the media store, and the
 * plaintext never touches disk at any point in the download: the network copy
 * streams through [EncryptingStream] on its way down.
 *
 * ## The format
 *
 * A 17-byte header — a four-byte magic (`ESE1`), a version byte, the chunk
 * size as an int, the plaintext length as a long — then one record per chunk
 * of up to [CHUNK_SIZE] plaintext bytes: the chunk's 12-byte GCM nonce, then
 * its ciphertext with the 16-byte tag appended, which is how GCM hands it
 * back. Chunking is what makes the file a playable thing rather than a vault:
 * [Reader] can decrypt any single chunk on its own, so playback can start in
 * the middle and seek freely, at the cost of one chunk of memory.
 *
 * ## The key, and the IV rule
 *
 * One AES-256 key for the whole store, generated into the Android Keystore
 * under [KEY_ALIAS] and non-exportable: it never leaves the Keystore, is never
 * handed to anything but a [Cipher], and is never logged. Losing it — clearing
 * the app's data, uninstalling — loses the downloads with it, which is the
 * honest price of "only this app can play them".
 *
 * Nonces are never invented here. Android Keystore GCM keys refuse a
 * caller-provided IV for encryption ("Caller-provided IV not permitted"), so
 * each chunk's cipher is initialised bare and the nonce it generated is read
 * back off it and stored beside the chunk. Decryption takes the stored nonce
 * as a parameter, which the Keystore does allow.
 */
object EncryptedStore {

    /** The store's directory, inside the app's private files. */
    const val DIR_NAME = "esei-downloads"

    private const val KEY_ALIAS = "esei_download_key_v1"
    private const val VERSION = 1

    /** Plaintext bytes per chunk; the unit playback decrypts in. */
    const val CHUNK_SIZE = 256 * 1024

    private const val NONCE_SIZE = 12
    private const val TAG_SIZE = 16
    private const val TAG_BITS = TAG_SIZE * 8

    /** magic(4) + version(1) + chunkSize(4) + totalLength(8). */
    const val HEADER_SIZE = 17
    private const val OFFSET_TOTAL_LENGTH = 9

    private val MAGIC = byteArrayOf('E'.code.toByte(), 'S'.code.toByte(), 'E'.code.toByte(), '1'.code.toByte())

    /** The directory every encrypted download is written into. */
    fun dir(context: Context): File = File(context.filesDir, DIR_NAME)

    /** The encrypted file a download called [name] is kept as. */
    fun fileFor(context: Context, name: String): File = File(dir(context), name)

    /** Whether [path] names a file inside the store's directory. */
    fun isStorePath(path: String?): Boolean =
        path != null && path.split('/').contains(DIR_NAME)

    /**
     * Whether [file] is one of this store's files, by its header rather than
     * its address — the playback chain asks this of whatever `file://` uri it
     * is handed, including ones read back out of a restored queue.
     */
    fun isEncrypted(file: File): Boolean = try {
        if (!file.isFile || file.length() < HEADER_SIZE) {
            false
        } else {
            file.inputStream().use { input ->
                val head = ByteArray(MAGIC.size + 1)
                input.read(head) == head.size &&
                    MAGIC.indices.all { head[it] == MAGIC[it] } &&
                    head[MAGIC.size].toInt() == VERSION
            }
        }
    } catch (e: Exception) {
        false
    }

    /** The store's key, made on first use and kept by the Keystore itself. */
    @Synchronized
    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encryptCipher(): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }

    private fun decryptCipher(nonce: ByteArray): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, nonce))
        }

    /** A stream that encrypts everything written to it into [file], chunk by chunk. */
    fun encryptingStream(file: File): OutputStream = EncryptingStream(file)

    /**
     * Writes an encrypted file into [file] directly — the `.part` sibling a
     * download is built under, renamed by the caller once this is closed.
     *
     * The header is written first with a placeholder length, and the true
     * length is patched into it on [close], once it is known. A stream that is
     * abandoned rather than closed therefore leaves a header claiming zero
     * bytes: a partial download reads as an empty file, never as a short one.
     */
    private class EncryptingStream(private val file: File) : OutputStream() {
        private val raf = RandomAccessFile(file, "rw")
        private val plain = ByteArray(CHUNK_SIZE)
        private var filled = 0
        private var totalPlain = 0L
        private var closed = false

        init {
            raf.setLength(0)
            raf.write(MAGIC)
            raf.write(VERSION)
            raf.writeInt(CHUNK_SIZE)
            raf.writeLong(0L)
        }

        override fun write(b: Int) {
            if (filled == CHUNK_SIZE) flushChunk()
            plain[filled++] = b.toByte()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            var offset = off
            var remaining = len
            while (remaining > 0) {
                val take = minOf(CHUNK_SIZE - filled, remaining)
                System.arraycopy(b, offset, plain, filled, take)
                filled += take
                offset += take
                remaining -= take
                if (filled == CHUNK_SIZE) flushChunk()
            }
        }

        private fun flushChunk() {
            if (filled == 0) return
            val cipher = encryptCipher()
            // The nonce the cipher generated for itself — the Keystore will
            // not accept one of ours for encryption. See the class note.
            val nonce = cipher.iv
            val sealed = cipher.doFinal(plain, 0, filled)
            raf.write(nonce)
            raf.write(sealed)
            totalPlain += filled
            filled = 0
        }

        override fun flush() {
            // Deliberately nothing: a half-filled chunk is not written out
            // early, because a chunk's record is only whole once it is sealed.
        }

        override fun close() {
            if (closed) return
            closed = true
            try {
                flushChunk()
                raf.seek(OFFSET_TOTAL_LENGTH.toLong())
                raf.writeLong(totalPlain)
            } finally {
                raf.close()
            }
        }
    }

    /**
     * Random-access reading of an encrypted file: the plaintext length out of
     * the header, and any range of it decrypted chunk by chunk, holding only
     * the most recently read chunk.
     */
    class Reader(private val file: File) : Closeable {
        private val raf = RandomAccessFile(file, "r")

        /** The plaintext length, as written in the header. */
        val plainLength: Long

        private val chunkSize: Int
        private var cachedIndex = -1
        private var cachedChunk: ByteArray? = null

        init {
            val head = ByteArray(HEADER_SIZE)
            try {
                raf.readFully(head)
            } catch (e: Exception) {
                raf.close()
                throw IOException("Not an encrypted download: ${file.name}", e)
            }
            if (!MAGIC.indices.all { head[it] == MAGIC[it] } || head[MAGIC.size].toInt() != VERSION) {
                raf.close()
                throw IOException("Not an encrypted download: ${file.name}")
            }
            chunkSize = readInt(head, 5)
            plainLength = readLong(head, OFFSET_TOTAL_LENGTH)
            if (chunkSize <= 0 || plainLength < 0) {
                raf.close()
                throw IOException("Corrupt encrypted download: ${file.name}")
            }
        }

        /**
         * Up to [length] plaintext bytes starting at [position] into [buffer]
         * at [offset], or -1 when [position] is at or past the end.
         */
        @Synchronized
        fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            if (position < 0) throw IOException("Negative position $position")
            if (position >= plainLength) return -1
            var read = 0
            while (read < length) {
                val pos = position + read
                if (pos >= plainLength) break
                val index = (pos / chunkSize).toInt()
                val chunk = chunk(index)
                val within = (pos - index.toLong() * chunkSize).toInt()
                val take = minOf(chunk.size - within, length - read, (plainLength - pos).toInt())
                if (take <= 0) break
                System.arraycopy(chunk, within, buffer, offset + read, take)
                read += take
            }
            return read
        }

        private fun chunk(index: Int): ByteArray {
            cachedChunk?.let { if (index == cachedIndex) return it }
            val plainHere = minOf(chunkSize.toLong(), plainLength - index.toLong() * chunkSize).toInt()
            // Every chunk before this one is a full chunk; only the last is short.
            val storedAt = HEADER_SIZE + index.toLong() * (NONCE_SIZE + CHUNK_SIZE + TAG_SIZE)
            raf.seek(storedAt)
            val nonce = ByteArray(NONCE_SIZE)
            raf.readFully(nonce)
            val sealed = ByteArray(plainHere + TAG_SIZE)
            raf.readFully(sealed)
            val plain = try {
                decryptCipher(nonce).doFinal(sealed)
            } catch (e: Exception) {
                throw IOException("Could not decrypt chunk $index of ${file.name}", e)
            }
            cachedIndex = index
            cachedChunk = plain
            return plain
        }

        override fun close() {
            raf.close()
        }

        private fun readInt(bytes: ByteArray, at: Int): Int =
            (bytes[at].toInt() and 0xFF shl 24) or
                (bytes[at + 1].toInt() and 0xFF shl 16) or
                (bytes[at + 2].toInt() and 0xFF shl 8) or
                (bytes[at + 3].toInt() and 0xFF)

        private fun readLong(bytes: ByteArray, at: Int): Long {
            var value = 0L
            for (i in 0 until 8) value = (value shl 8) or (bytes[at + i].toLong() and 0xFF)
            return value
        }
    }
}

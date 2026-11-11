package com.exoticbutters.amoledclock

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/**
 * The on-device sound library Home Assistant can trigger. Sounds are copied
 * into app-private storage on import, so the original file can be moved or
 * deleted afterwards, and no storage permission is ever needed. A sound's
 * name is simply its file name without the extension.
 */
class SoundStore(context: Context) {

    class Sound(val name: String, val file: File) {
        /** Stable, entity-id-safe form of [name], used for Home Assistant unique IDs. */
        val slug: String get() = slugify(name)
    }

    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, "sounds")

    fun list(): List<Sound> {
        val files = dir.listFiles() ?: return emptyList()
        return files.filter { it.isFile }
            .map { Sound(it.nameWithoutExtension, it) }
            .sortedBy { it.name.lowercase() }
    }

    /** Looks a sound up by its name (case-insensitive) or its slug. */
    fun find(query: String): Sound? {
        val q = query.trim()
        if (q.isEmpty()) return null
        val qSlug = slugify(q)
        return list().firstOrNull { it.name.equals(q, ignoreCase = true) || it.slug == qSlug }
    }

    /** The picked file's name without extension, as a starting suggestion for the sound's name. */
    fun suggestedName(uri: Uri): String {
        val display = displayName(uri) ?: return ""
        return sanitizeName(display.substringBeforeLast('.'))
    }

    /**
     * Copies [uri] into the library as [name], replacing any existing sound
     * with the same slug. Does blocking I/O: call it off the main thread.
     */
    @Throws(IOException::class)
    fun import(uri: Uri, name: String): Sound {
        val clean = sanitizeName(name)
        if (slugify(clean).isEmpty()) throw IOException("Sound name needs at least one letter or number")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Can't create sound folder")

        val ext = extensionFor(uri)
        val target = File(dir, if (ext.isEmpty()) clean else "$clean.$ext")
        val tmp = File(dir, ".importing")

        val input = appContext.contentResolver.openInputStream(uri) ?: throw IOException("Can't open the selected file")
        input.use { inp ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_SOUND_BYTES) {
                        out.close()
                        tmp.delete()
                        throw IOException("Sound is larger than ${MAX_SOUND_BYTES / (1024 * 1024)} MB")
                    }
                    out.write(buf, 0, n)
                }
            }
        }

        // Same slug means the same Home Assistant entity, so replace it.
        list().filter { it.slug == slugify(clean) }.forEach { it.file.delete() }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("Can't save sound")
        }
        return Sound(clean, target)
    }

    fun delete(sound: Sound) {
        sound.file.delete()
    }

    private fun displayName(uri: Uri): String? {
        try {
            appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) c.getString(idx)?.let { return it }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't query display name for $uri", e)
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun extensionFor(uri: Uri): String {
        val fromName = displayName(uri)?.substringAfterLast('.', "")?.lowercase().orEmpty()
        if (fromName.isNotEmpty() && fromName.length <= 5 && fromName.all { it.isLetterOrDigit() }) return fromName
        val mime = appContext.contentResolver.getType(uri) ?: return ""
        return MimeTypeMap.getSingleton().getExtensionFromMimeType(mime).orEmpty()
    }

    companion object {
        private const val TAG = "SoundStore"
        private const val MAX_SOUND_BYTES = 20L * 1024 * 1024

        /** Keeps names safe to use as a file name and readable as an HA button label. */
        fun sanitizeName(raw: String): String =
            raw.replace(Regex("[^A-Za-z0-9 _-]"), " ").replace(Regex("\\s+"), " ").trim().take(40)

        fun slugify(name: String): String =
            name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
    }
}

/** Plays one sound at a time on the media stream, so the HA volume control applies to it. */
class SoundPlayer {
    private var player: MediaPlayer? = null

    fun play(file: File) {
        stop()
        val mp = MediaPlayer()
        try {
            @Suppress("DEPRECATION")
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC)
            // Hand over a file descriptor rather than a path: the media
            // server process can't read this app's private files directly.
            FileInputStream(file).use { mp.setDataSource(it.fd) }
            mp.setOnCompletionListener { done ->
                done.release()
                if (player === done) player = null
            }
            mp.prepare()
            mp.start()
            player = mp
        } catch (e: Exception) {
            Log.w("SoundPlayer", "Couldn't play ${file.name}", e)
            mp.release()
        }
    }

    fun stop() {
        player?.let {
            try {
                it.stop()
            } catch (_: IllegalStateException) {
            }
            it.release()
        }
        player = null
    }
}

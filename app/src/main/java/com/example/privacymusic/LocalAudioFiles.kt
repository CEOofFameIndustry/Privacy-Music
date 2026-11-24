package com.example.privacymusic

import com.example.privacymusic.i18n.T

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.example.privacymusic.data.Ref
import com.example.privacymusic.data.Song

/** Reads music files that are already on the phone. Nothing about them leaves the device. */
internal object LocalAudioFiles {
    fun scan(context: Context): List<Song> {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DISPLAY_NAME,
        )
        return try {
            context.contentResolver.query(collection, projection, "${MediaStore.Audio.Media.IS_MUSIC}!=0", null,
                "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC")?.use { c ->
                buildList {
                    while (c.moveToNext()) {
                        val uri = ContentUris.withAppendedId(collection, c.getLong(0))
                        val title = c.getString(1).usable() ?: c.getString(6).orEmpty().substringBeforeLast('.')
                        if (title.isBlank()) continue
                        val art = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), c.getLong(4))
                        add(Song(uri.toString(), title, listOfNotNull(c.getString(2).usable()?.let { Ref(it) }),
                            c.getString(3).usable()?.let { Ref(it) }, (c.getLong(5) / 1000).toInt().takeIf { it > 0 },
                            art.toString(), extra = T("На пристрої")))
                    }
                }
            }.orEmpty()
        } catch (_: SecurityException) { emptyList() }
    }

    fun read(context: Context, uri: Uri): Song? {
        val name = displayName(context, uri).orEmpty().substringBeforeLast('.')
        val r = MediaMetadataRetriever()
        var title: String? = null; var artist: String? = null; var album: String? = null; var duration: Int? = null
        try {
            r.setDataSource(context, uri)
            title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).usable()
            artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).usable()
            album = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).usable()
            duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.div(1000)?.toInt()
        } catch (_: Exception) {
        } finally { runCatching { r.release() } }
        val t = title ?: name.ifBlank { return null }
        return Song(uri.toString(), t, listOfNotNull(artist?.let { Ref(it) }), album?.let { Ref(it) }, duration, extra = T("На пристрої"))
    }

    private fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    } catch (_: Exception) { null }

    private fun String?.usable(): String? = this?.trim()?.takeIf { it.isNotEmpty() && !it.equals("<unknown>", true) && !it.equals("unknown", true) }
}

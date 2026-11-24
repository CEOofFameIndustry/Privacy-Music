package com.example.privacymusic.data

import com.example.privacymusic.i18n.T

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/** User-chosen playlist covers: square-cropped, downscaled JPEGs in the app's private storage. */
object Covers {
    private fun dir(context: Context) = File(context.filesDir, "covers").apply { mkdirs() }

    suspend fun set(context: Context, playlistId: Long, source: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 720) sample *= 2
            val bmp = context.contentResolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
                ?: return@runCatching false
            val side = minOf(bmp.width, bmp.height)
            val square = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
            val scaled = if (side > 720) Bitmap.createScaledBitmap(square, 720, 720, true) else square
            dir(context).listFiles { f -> f.name.startsWith("${playlistId}-") }?.forEach { it.delete() }
            // A new file name each time so image caches never show the old cover.
            val file = File(dir(context), "${playlistId}-${System.currentTimeMillis()}.jpg")
            file.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            Library.setPlaylistCover(playlistId, file.absolutePath)
            true
        }.getOrDefault(false)
    }

    fun remove(context: Context, playlistId: Long) {
        dir(context).listFiles { f -> f.name.startsWith("${playlistId}-") }?.forEach { it.delete() }
        Library.setPlaylistCover(playlistId, null)
    }

    /** For the encrypted backup: playlist id -> JPEG bytes (base64). */
    fun dump(): JSONObject = JSONObject().apply {
        Library.playlists().forEach { p -> Library.coverPath(p.id)?.let { File(it) }?.takeIf { it.exists() }?.let { put(p.id.toString(), Base64.encodeToString(it.readBytes(), Base64.NO_WRAP)) } }
    }

    fun restore(context: Context, data: JSONObject?) {
        dir(context).listFiles()?.forEach { it.delete() }
        data?.keys()?.forEach { id ->
            val file = File(dir(context), "${id}-${System.currentTimeMillis()}.jpg")
            runCatching { file.writeBytes(Base64.decode(data.getString(id), Base64.NO_WRAP)); Library.setPlaylistCover(id.toLong(), file.absolutePath) }
        }
    }

    fun bytesOf(file: File?): String? = file?.takeIf { it.exists() }?.let { Base64.encodeToString(it.readBytes(), Base64.NO_WRAP) }
    fun toBytes(base64: String) = Base64.decode(base64, Base64.NO_WRAP)
    @Suppress("unused") private fun ByteArrayOutputStream.b64() = Base64.encodeToString(toByteArray(), Base64.NO_WRAP)
}

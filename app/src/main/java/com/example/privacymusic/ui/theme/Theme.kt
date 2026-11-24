package com.example.privacymusic.ui.theme

import com.example.privacymusic.i18n.T

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.privacymusic.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

val Dim = Color(0xffaaaaaa)
/** Destructive actions stay red whatever accent the user picks. */
val Danger = Color(0xffff5a5a)

/**
 * The user-customisable palette. Every colour is derived from two user choices (background + accent), read from
 * snapshot-backed settings, so changing them in the Appearance screen updates the whole app instantly.
 */
object Pal {
    val bg: Color get() = Color(Settings.themeBg.value)
    val accent: Color get() = Color(Settings.themeAccent.value)
    val onAccent: Color get() = if (accent.luminance() > 0.45f) Color.Black else Color.White
    private fun mix(f: Float) = lerp(bg, Color.White, f)
    val bar: Color get() = mix(.06f)
    val card: Color get() = mix(.10f)
    val chip: Color get() = mix(.15f)
    val line: Color get() = mix(.20f)
    val corner get() = Settings.themeCorner.value.dp
    /** Clean, minimal interface (large titles, floating bars, airy player) instead of the YouTube Music look. */
    val minimal: Boolean get() = Settings.uiStyle.value == "minimal"

    /** Decoded, pre-blurred background picture (null = plain colour). */
    var image by mutableStateOf<ImageBitmap?>(null)
        private set

    private fun file(context: Context) = File(context.filesDir, "theme/background.jpg")
    private fun videoFile(context: Context) = File(context.filesDir, "theme/background.mp4")

    /** True when this phone can't decode the chosen video; the picture (or plain colour) is shown instead. */
    var videoFailed by mutableStateOf(false)

    /** Looping, muted background video (takes priority over the picture). */
    var video by mutableStateOf<File?>(null)
        private set

    const val MAX_VIDEO_BYTES = 300L * 1024 * 1024

    /** Copies a picked video into private storage. Returns an error message or null on success. */
    suspend fun setVideo(context: Context, source: android.net.Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val size = context.contentResolver.query(source, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)
                ?.use { if (it.moveToFirst()) it.getLong(0) else -1L } ?: -1L
            if (size > MAX_VIDEO_BYTES) return@withContext T("Відео завелике (понад 300 МБ). Виберіть коротше або стисніть його.")
            val target = videoFile(context).apply { parentFile?.mkdirs() }
            val tmp = File(target.path + ".part")
            context.contentResolver.openInputStream(source)?.use { input -> tmp.outputStream().use { input.copyTo(it) } } ?: return@withContext T("Відео недоступне")
            video = null; videoFailed = false
            tmp.renameTo(target)
            Settings.themeVideo.set(true)
            video = target
            null
        }.getOrElse { T("Не вдалося скопіювати відео") }
    }

    fun clearVideo(context: Context) { video = null; videoFile(context).delete(); Settings.themeVideo.set(false) }

    /** Copies a picked picture into private storage, downscaled so it is cheap to draw. */
    suspend fun setImage(context: Context, source: android.net.Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2200) sample *= 2
            val bmp = context.contentResolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return@runCatching false
            file(context).apply { parentFile?.mkdirs() }.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            Settings.themeImage.set(true)
            reload(context)
            true
        }.getOrDefault(false)
    }

    fun clearImage(context: Context) { file(context).delete(); Settings.themeImage.set(false); image = null }
    fun imageFile(context: Context) = file(context).takeIf { it.exists() }

    /** (Re)builds [image] from disk; "blur" is done by scaling down, which is free to draw afterwards. */
    suspend fun reload(context: Context) = withContext(Dispatchers.IO) {
        video = videoFile(context).takeIf { Settings.themeVideo.value && it.exists() }
        val f = file(context)
        if (!Settings.themeImage.value || !f.exists()) { image = null; return@withContext }
        runCatching {
            var bmp = BitmapFactory.decodeFile(f.path) ?: return@runCatching
            val blur = Settings.themeBlur.value
            if (blur > 0) {
                val k = 1f + blur * 1.6f
                val w = (bmp.width / k).toInt().coerceAtLeast(16); val h = (bmp.height / k).toInt().coerceAtLeast(16)
                bmp = Bitmap.createScaledBitmap(Bitmap.createScaledBitmap(bmp, (w * 2).coerceAtMost(bmp.width), (h * 2).coerceAtMost(bmp.height), true), w, h, true)
            }
            image = bmp.asImageBitmap()
        }
    }
}

/**
 * Screens are transparent: the wallpaper (colour + picture or video) is drawn ONCE behind the whole UI by
 * [AppBackground], so it stays still while pages slide. Screens never overlap because navigation is either a
 * side-by-side page turn or a sequential fade-through.
 */
@Composable
fun ScreenBackground(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize()) { content() }
}

@Composable
fun AppBackground() {
    Box(Modifier.fillMaxSize().background(Pal.bg))
    val video = Pal.video
    if (video != null && !Pal.videoFailed) { androidx.compose.runtime.key(video) { VideoBackground(video) }; return }
    Pal.image?.let { img ->
        Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, filterQuality = FilterQuality.Medium)
        Box(Modifier.fillMaxSize().background(Pal.bg.copy(alpha = Settings.themeDim.value / 100f)))
    }
}

@Composable
fun PrivacyMusicTheme(content: @Composable () -> Unit) {
    val bg = Pal.bg; val accent = Pal.accent
    val scheme = remember(bg, accent) {
        fun mix(f: Float) = lerp(bg, Color.White, f)
        darkColorScheme(
            primary = accent, onPrimary = if (accent.luminance() > 0.45f) Color.Black else Color.White,
            secondary = accent, onSecondary = if (accent.luminance() > 0.45f) Color.Black else Color.White,
            background = bg, onBackground = Color.White,
            surface = mix(.06f), onSurface = Color.White,
            surfaceVariant = mix(.15f), onSurfaceVariant = Dim,
            surfaceContainerLowest = bg, surfaceContainerLow = mix(.06f), surfaceContainer = mix(.10f),
            surfaceContainerHigh = mix(.15f), surfaceContainerHighest = mix(.18f),
            secondaryContainer = mix(.18f), onSecondaryContainer = Color.White,
            outline = mix(.24f), outlineVariant = mix(.17f), error = Danger,
        )
    }
    val base = LocalDensity.current
    val density = remember(base, Settings.themeFont.value) { Density(base.density, base.fontScale * Settings.themeFont.value / 100f) }
    CompositionLocalProvider(LocalDensity provides density) {
        MaterialTheme(colorScheme = scheme, typography = Typography, content = content)
    }
}

/** Muted, looping video behind the whole UI. Paused while the app isn't visible to save battery. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun VideoBackground(file: File) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val player = remember(file) {
        androidx.media3.exoplayer.ExoPlayer.Builder(context, androidx.media3.exoplayer.DefaultRenderersFactory(context).setEnableDecoderFallback(true)).build().apply {
            trackSelectionParameters = trackSelectionParameters.buildUpon().setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_AUDIO, true).build()
            volume = 0f
            repeatMode = androidx.media3.common.Player.REPEAT_MODE_ONE
            videoScalingMode = androidx.media3.common.C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
            setMediaItem(androidx.media3.common.MediaItem.fromUri(android.net.Uri.fromFile(file)))
            prepare(); playWhenReady = true
        }
    }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(player, lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_START) player.play()
            if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP) player.pause()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); player.release() }
    }
    Box(Modifier.fillMaxSize()) {
        // TextureView draws inside the normal view hierarchy (a SurfaceView would sit behind the opaque window).
        androidx.compose.ui.viewinterop.AndroidView({ ctx ->
            android.view.TextureView(ctx).also { tv ->
                var vw = 0; var vh = 0
                fun crop() {
                    if (vw == 0 || vh == 0 || tv.width == 0 || tv.height == 0) return
                    // Centre-crop: scale the video to cover the whole view, keep its aspect ratio.
                    val scale = maxOf(tv.width.toFloat() / vw, tv.height.toFloat() / vh)
                    tv.setTransform(android.graphics.Matrix().apply {
                        setScale(vw * scale / tv.width, vh * scale / tv.height, tv.width / 2f, tv.height / 2f)
                    })
                }
                player.addListener(object : androidx.media3.common.Player.Listener {
                    override fun onVideoSizeChanged(size: androidx.media3.common.VideoSize) { vw = size.width; vh = size.height; crop() }
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        com.example.privacymusic.data.Diag.log("Фонове відео: ${error.errorCodeName}")
                        Pal.videoFailed = true
                    }
                })
                tv.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> crop() }
                player.setVideoTextureView(tv)
            }
        }, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Pal.bg.copy(alpha = Settings.themeDim.value / 100f)))
    }
}

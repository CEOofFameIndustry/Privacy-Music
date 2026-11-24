package com.example.privacymusic

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import kotlinx.coroutines.launch
import com.example.privacymusic.account.AccountStore
import com.example.privacymusic.data.Library
import com.example.privacymusic.data.Settings
import com.example.privacymusic.net.Firewall
import com.example.privacymusic.playback.Downloads
import com.example.privacymusic.playback.Playback

class PrivacyMusicApp : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        Settings.init(this)
        com.example.privacymusic.i18n.I18n.init(this)
        com.example.privacymusic.data.Diag.init(this)
        AccountStore.init(this)
        Library.init(this)
        Downloads.init(this)
        Playback.init(this)
        com.example.privacymusic.net.Link.init(this)
        kotlinx.coroutines.MainScope().launch(kotlinx.coroutines.Dispatchers.Default) { com.example.privacymusic.ui.theme.Pal.reload(this@PrivacyMusicApp) }
    }

    /** Artwork goes through the same firewall (image hosts only, no cookies, generic UA) and is cached locally. */
    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { Firewall.client })) }
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.2).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("artwork")).maxSizeBytes(200L * 1024 * 1024).build() }
        .crossfade(true)
        .build()
}

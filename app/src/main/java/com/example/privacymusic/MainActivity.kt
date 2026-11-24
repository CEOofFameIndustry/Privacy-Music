package com.example.privacymusic

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.privacymusic.data.Endpoint
import com.example.privacymusic.net.YouTubeMusic
import com.example.privacymusic.playback.Playback
import com.example.privacymusic.ui.MusicApp
import com.example.privacymusic.ui.theme.PrivacyMusicTheme

class MainActivity : ComponentActivity() {
    private var link by mutableStateOf<Endpoint?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.BLACK))
        handle(intent)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            getSharedPreferences("ui", MODE_PRIVATE).getBoolean("asked_notifications", false).not()) {
            getSharedPreferences("ui", MODE_PRIVATE).edit().putBoolean("asked_notifications", true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContent { PrivacyMusicTheme { MusicApp(link) { link = null } } }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handle(intent) }

    /** music.youtube.com / youtu.be links and shared text open here instead of in Google's app. */
    private fun handle(intent: Intent?) {
        val text = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        } ?: return
        link = YouTubeMusic.parseLink(text)
    }

    override fun onStart() { super.onStart(); Playback.connect(this) }
    override fun onStop() { Playback.disconnect(); super.onStop() }
}

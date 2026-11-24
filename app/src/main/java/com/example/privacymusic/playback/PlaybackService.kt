package com.example.privacymusic.playback

import com.example.privacymusic.i18n.T

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.*
import com.example.privacymusic.MainActivity
import com.example.privacymusic.data.Library
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.*

/** Hosts the MediaSession: background playback, notification, lock screen, Bluetooth / headset buttons. */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val likeCommand = SessionCommand("LIKE", Bundle.EMPTY)

    override fun onCreate() {
        super.onCreate()
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, Playback.player)
            .setSessionActivity(open)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                    val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().add(likeCommand).build()
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailableSessionCommands(commands).build()
                }
                override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                    if (customCommand.customAction == "LIKE") {
                        val song = Playback.current.value ?: return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                        scope.launch {
                            val liked = withContext(Dispatchers.IO) { val now = !Library.isLiked(song.id); Library.setLiked(song, now); now }
                            updateButtons(liked)
                        }
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            })
            .build()
        scope.launch {
            Playback.current.collect { song ->
                val liked = song != null && withContext(Dispatchers.IO) { Library.isLiked(song.id) }
                updateButtons(liked)
            }
        }
    }

    private fun updateButtons(liked: Boolean) {
        val button = CommandButton.Builder(if (liked) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
            .setDisplayName(if (liked) T("Не подобається більше") else T("Подобається"))
            .setSessionCommand(likeCommand).build()
        session?.setMediaButtonPreferences(ImmutableList.of(button))
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    /** The app was swiped away from recent apps: stop the music (unless the user chose to keep it playing). */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0 || !com.example.privacymusic.data.Settings.playAfterClose.value) {
            player?.pause()
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        session?.release() // the player itself lives in [Playback] for the whole process
        session = null
        super.onDestroy()
    }
}

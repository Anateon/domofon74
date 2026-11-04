package ru.is74.door

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.lifecycle.Lifecycle
import ru.is74.door.ui.DoorTheme
import ru.is74.door.ui.Ink

/** Local SAF documents only; never attaches the account's HTTP credentials. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SavedVideoActivity:ComponentActivity() {
    private lateinit var player:ExoPlayer
    private var resumePlayback=true
    private var error by mutableStateOf<String?>(null)
    private var loading by mutableStateOf(true)

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val uri=intent.data
        if(uri==null || uri.scheme!="content") {finish();return}
        enableEdgeToEdge(statusBarStyle=SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),navigationBarStyle=SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        resumePlayback=savedInstanceState?.getBoolean("playing",true) ?: true
        player=ExoPlayer.Builder(this).build().apply {
            addListener(object:Player.Listener {
                override fun onPlaybackStateChanged(state:Int) {loading=state==Player.STATE_BUFFERING}
                override fun onPlayerError(exception:PlaybackException) {
                    loading=false
                    error="Не удалось воспроизвести файл. Проверьте, доступен ли он в выбранной папке."
                }
            })
            setMediaItem(MediaItem.Builder().setUri(uri).setMimeType(MimeTypes.VIDEO_MP4).build(),savedInstanceState?.getLong("position",0) ?: 0)
            prepare()
        }
        setContent {
            DoorTheme {
                Surface(Modifier.fillMaxSize(),color=Ink) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                        Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
                            TextButton(onClick={finish()}) {Text("‹ Назад")}
                            Text("Сохранённое видео",style=MaterialTheme.typography.titleMedium,modifier=Modifier.padding(start=8.dp))
                        }
                        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black),contentAlignment=Alignment.Center) {
                            AndroidView(modifier=Modifier.fillMaxSize(),factory={ctx->PlayerView(ctx).apply {this.player=this@SavedVideoActivity.player;useController=true;keepScreenOn=true}},
                                onRelease={it.player=null;it.keepScreenOn=false})
                            if(loading) CircularProgressIndicator()
                            error?.let {Text(it,modifier=Modifier.padding(24.dp))}
                        }
                    }
                }
            }
        }
    }
    override fun onStart() {super.onStart();if(::player.isInitialized) player.playWhenReady=resumePlayback}
    override fun onStop() {
        if(::player.isInitialized) {resumePlayback=player.playWhenReady;player.pause()}
        super.onStop()
    }
    override fun onSaveInstanceState(outState:Bundle) {
        if(::player.isInitialized) {
            outState.putLong("position",player.currentPosition)
            outState.putBoolean("playing",if(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) player.playWhenReady else resumePlayback)
        }
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {if(::player.isInitialized) player.release();super.onDestroy()}
}

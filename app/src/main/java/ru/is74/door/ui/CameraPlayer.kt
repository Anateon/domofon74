package ru.is74.door.ui

import android.net.Uri
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * Owns one player for this visible camera. [onPosition] is the media-relative
 * position, not a wall-clock timestamp; the archive caller supplies that origin.
 * [playing] expresses user intent and is deliberately unchanged by buffering
 * or by the application going into the background.
 * [onReleasedPosition] runs before releasing an archive player. Its callback
 * is captured when the player is created; callers should capture their request
 * generation and accept this position only if both generation and URL match.
 */
@OptIn(UnstableApi::class)
@Composable
fun CameraPlayer(
    url: String?,
    isLive: Boolean,
    playing: Boolean,
    muted: Boolean,
    speed: Float,
    onPosition: (Long) -> Unit,
    onPlayingChange: (Boolean) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
    demo: Boolean = false,
    initialPositionMs: Long = 0,
    headers: Map<String, String> = emptyMap(),
    requestedEpochSeconds: Long? = null,
    onAbsolutePosition: (Long) -> Unit = {},
    onRefreshRequested: (() -> Unit)? = null,
    onReleasedPosition: (sourceUrl: String, epochSeconds: Long) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // Immutable headers belong to one player; changing a session recreates it.
    val player = remember(context, headers) {
        val mediaFactory = DefaultDataSource.Factory(context, DataSource.Factory { SecureMediaDataSource(headers) })
        ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(mediaFactory)).build()
    }
    val latestPosition by rememberUpdatedState(onPosition)
    val latestAbsolutePosition by rememberUpdatedState(onAbsolutePosition)
    val latestPlayingChange by rememberUpdatedState(onPlayingChange)
    val latestError by rememberUpdatedState(onError)
    val latestLive by rememberUpdatedState(isLive)
    val latestPlaying by rememberUpdatedState(playing)
    val latestRequestedTime by rememberUpdatedState(requestedEpochSeconds)
    var foreground by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    var buffering by remember { mutableStateOf(false) }
    var actuallyPlaying by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var retryGeneration by remember { mutableStateOf(0) }
    var adjustedInitialPosition by remember(player) { mutableStateOf(false) }
    val liveRecovery = remember(player) { LiveRecoveryState() }

    DisposableEffect(player) {
        val releasedPosition = onReleasedPosition
        val listener = object : Player.Listener {
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                // A segment can start before requested start-date. PROGRAM-DATE-TIME
                // is the source of truth, exposed by Media3 through this window.
                val requested = latestRequestedTime
                if (!latestLive && !adjustedInitialPosition && requested != null && !timeline.isEmpty) {
                    val window = timeline.getWindow(player.currentMediaItemIndex, Timeline.Window())
                    if (window.windowStartTimeMs != C.TIME_UNSET && !window.isPlaceholder) {
                        adjustedInitialPosition = true
                        val desired = (requested * 1_000L - window.windowStartTimeMs).coerceAtLeast(0)
                        val upper = window.durationMs.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0)
                        player.seekTo(if (upper == null) desired else desired.coerceAtMost(upper))
                    }
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_ENDED && !latestLive) latestPlayingChange(false)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                actuallyPlaying = isPlaying
            }

            override fun onPlayerError(error: PlaybackException) {
                if (latestLive && error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    // A short live window can expire while the system picker or
                    // another application is in front. Recover only once per
                    // foreground session; repeated failures remain visible.
                    if (!foreground) {
                        liveRecovery.needsRefresh = true
                        buffering = false
                        return
                    }
                    if (!liveRecovery.automaticAttemptUsed) {
                        liveRecovery.automaticAttemptUsed = true
                        liveRecovery.needsRefresh = false
                        errorMessage = null
                        buffering = true
                        player.seekToDefaultPosition()
                        player.prepare()
                        player.playWhenReady = latestPlaying
                        return
                    }
                }
                buffering = false
                val message = playbackErrorText(error)
                errorMessage = message
                latestError(message)
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            try {
                val timeline = player.currentTimeline
                val source = player.currentMediaItem?.localConfiguration?.uri?.toString()
                if (source != null && !timeline.isEmpty) {
                    val window = timeline.getWindow(player.currentMediaItemIndex, Timeline.Window())
                    if (!window.isLive && !window.isPlaceholder && window.windowStartTimeMs != C.TIME_UNSET) {
                        releasedPosition(source, (window.windowStartTimeMs + player.currentPosition) / 1_000L)
                    }
                }
            } finally {
                player.release()
            }
        }
    }

    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    foreground = true
                    if (liveRecovery.wasStopped && latestLive && player.mediaItemCount > 0) {
                        liveRecovery.automaticAttemptUsed = false
                        // Resume live at the current edge. Archive deliberately
                        // keeps its precise paused position and user play intent.
                        player.seekToDefaultPosition()
                        if (liveRecovery.needsRefresh || player.playerError?.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                            errorMessage = null
                            buffering = true
                            player.prepare()
                        }
                        liveRecovery.needsRefresh = false
                        player.playWhenReady = latestPlaying
                    }
                    liveRecovery.wasStopped = false
                }
                Lifecycle.Event.ON_STOP -> {
                    // Pause synchronously; do not wait for the next composition.
                    player.pause()
                    foreground = false
                    liveRecovery.wasStopped = true
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(player, url, initialPositionMs, requestedEpochSeconds, retryGeneration) {
        errorMessage = null
        adjustedInitialPosition = false
        liveRecovery.automaticAttemptUsed = false
        liveRecovery.needsRefresh = false
        if (url.isNullOrBlank()) {
            buffering = false
            player.stop()
            player.clearMediaItems()
        } else {
            buffering = true
            if (!isTrustedMediaUrl(url)) {
                buffering = false
                errorMessage = "Сервер вернул неподдерживаемый адрес видео. Обновите камеру."
                latestError(errorMessage.orEmpty())
                player.stop()
                player.clearMediaItems()
                return@LaunchedEffect
            }
            val mediaItem = MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_M3U8).build()
            if (isLive) player.setMediaItem(mediaItem)
            else player.setMediaItem(mediaItem, initialPositionMs.coerceAtLeast(0))
            player.prepare()
            player.playWhenReady = foreground && playing
        }
    }

    LaunchedEffect(player, playing, foreground, url, retryGeneration, isLive) {
        if (isLive && playing && foreground && !url.isNullOrBlank()) {
            // Pressing Play after a long manual pause has the same live-window
            // semantics as returning from the background.
            player.seekToDefaultPosition()
            if (liveRecovery.needsRefresh || player.playerError?.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                liveRecovery.automaticAttemptUsed = false
                liveRecovery.needsRefresh = false
                errorMessage = null
                buffering = true
                player.prepare()
            }
        }
        player.playWhenReady = playing && foreground && !url.isNullOrBlank()
    }
    LaunchedEffect(player, muted, speed, isLive) {
        player.volume = if (muted) 0f else 1f
        player.setPlaybackSpeed(if (isLive) 1f else speed.coerceIn(0.25f, 4f))
    }
    LaunchedEffect(player, foreground, url) {
        if (!foreground || url.isNullOrBlank()) return@LaunchedEffect
        while (true) {
            if (player.playbackState == Player.STATE_READY) {
                latestPosition(player.currentPosition.coerceAtLeast(0))
                val timeline = player.currentTimeline
                if (!timeline.isEmpty) {
                    val window = timeline.getWindow(player.currentMediaItemIndex, Timeline.Window())
                    if (window.windowStartTimeMs != C.TIME_UNSET) {
                        latestAbsolutePosition((window.windowStartTimeMs + player.currentPosition) / 1_000L)
                    }
                }
            }
            delay(500)
        }
    }

    Box(modifier.background(Color(0xFF080D14)), contentAlignment = Alignment.Center) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    setShutterBackgroundColor(android.graphics.Color.rgb(8, 13, 20))
                    this.player = player
                }
            },
            update = { view ->
                view.player = player
                view.keepScreenOn = foreground && actuallyPlaying
            },
            onRelease = { view ->
                view.keepScreenOn = false
                view.player = null
            },
        )

        when {
            url.isNullOrBlank() -> PlayerPlaceholder(
                title = if (demo) "Камера вашего дома" else "Видео пока недоступно",
                detail = if (demo) "Демонстрация интерфейса · без трансляции"
                else "Выберите камеру или обновите подключение",
            )
            errorMessage != null -> Column(
                modifier = Modifier.fillMaxSize().background(Color(0xE6080D14)).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    errorMessage.orEmpty(),
                    color = Color(0xFFE6EDF5),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                TextButton(onClick = {
                    latestPlayingChange(true)
                    if (onRefreshRequested != null) onRefreshRequested() else retryGeneration++
                }) { Text("Повторить", color = Color(0xFF66E0D0)) }
            }
            buffering -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.background(Color(0xC0080D14)).padding(20.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    color = Color(0xFF66E0D0),
                    strokeWidth = 2.dp,
                )
                Text("Подключаем видео…", color = Color(0xFFE6EDF5), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun PlayerPlaceholder(title: String, detail: String) {
    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFF0D1721)).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
    ) {
        Canvas(Modifier.size(58.dp)) {
            val cyan = Color(0xFF66E0D0)
            drawRoundRect(
                color = cyan.copy(alpha = 0.12f),
                cornerRadius = CornerRadius(size.width * .25f),
            )
            drawRoundRect(
                color = cyan,
                topLeft = Offset(size.width * .29f, size.height * .2f),
                size = Size(size.width * .42f, size.height * .61f),
                cornerRadius = CornerRadius(size.width * .035f),
                style = Stroke(width = 2.dp.toPx()),
            )
            drawCircle(cyan, radius = 2.dp.toPx(), center = Offset(size.width * .6f, size.height * .53f))
        }
        Text(title, style = MaterialTheme.typography.titleMedium, color = Color(0xFFE6EDF5))
        Text(detail, style = MaterialTheme.typography.bodySmall, color = Color(0xFF8D9CAD), textAlign = TextAlign.Center)
    }
}

private fun playbackErrorText(error: PlaybackException): String {
    var cause: Throwable? = error
    repeat(8) {
        val current = cause ?: return@repeat
        if (current is MediaHttpException) {
            return when (current.status) {
                401, 403 -> "Ссылка на видео недействительна. Обновите камеру или войдите снова."
                404, 410 -> "На выбранное время записи нет. Попробуйте соседний отрезок."
                else -> "Сервер камеры временно недоступен. Попробуйте ещё раз."
            }
        }
        if (current is SocketTimeoutException) return "Камера не отвечает. Проверьте интернет и попробуйте снова."
        if (current is HttpDataSource.InvalidResponseCodeException) {
            return when (current.responseCode) {
                401, 403 -> "Ссылка на видео недействительна. Обновите камеру или войдите снова."
                404, 410 -> "На выбранное время записи нет. Попробуйте соседний отрезок."
                else -> "Сервер камеры временно недоступен. Попробуйте ещё раз."
            }
        }
        cause = current.cause
    }
    return when (error.errorCode) {
        PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> "Прямой эфир ушёл вперёд. Обновите подключение к камере."
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Не удалось подключиться к камере. Проверьте интернет."
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> "Телефон не смог воспроизвести это видео. Попробуйте другой поток."
        else -> "Не удалось загрузить видео. Попробуйте ещё раз."
    }
}

private class LiveRecoveryState {
    var automaticAttemptUsed = false
    var needsRefresh = false
    var wasStopped = false
}

internal fun isTrustedMediaUrl(value: String): Boolean = runCatching {
    val url = URL(value)
    url.protocol == "https" && url.host.lowercase() in setOf("cdn.cams.is74.ru", "cams.is74.ru") &&
        url.userInfo == null && (url.port == -1 || url.port == 443)
}.getOrDefault(false)

private class MediaHttpException(val status: Int) : IOException("Camera server HTTP $status")

/**
 * HttpURLConnection's automatic redirects can forward Authorization to another
 * host. Handle them explicitly and accept only the same HTTPS authority. This
 * also validates every child playlist, segment and encryption-key request.
 * Deliberately excludes URLs and response bodies from all exception messages.
 */
@OptIn(UnstableApi::class)
private class SecureMediaDataSource(private val headers: Map<String, String>) : BaseDataSource(true) {
    private var connection: HttpURLConnection? = null
    private var stream: InputStream? = null
    private var opened = false
    private var remaining = C.LENGTH_UNSET.toLong()

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        if (!isTrustedMediaUrl(dataSpec.uri.toString())) throw IOException("Unsupported camera host")
        var requested = URL(dataSpec.uri.toString())
        try {
            var redirects = 0
            while (true) {
                val conn = (requested.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 15_000
                    readTimeout = 20_000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "skyplayer")
                    setRequestProperty("Accept-Encoding", "identity")
                    headers.forEach { (key, value) -> setRequestProperty(key, value) }
                    dataSpec.httpRequestHeaders.forEach { (key, value) -> setRequestProperty(key, value) }
                    if (dataSpec.position != 0L || dataSpec.length != C.LENGTH_UNSET.toLong()) {
                        val end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) ""
                        else (dataSpec.position + dataSpec.length - 1).toString()
                        setRequestProperty("Range", "bytes=${dataSpec.position}-$end")
                    }
                }
                connection = conn
                val code = conn.responseCode
                if (code in listOf(301, 302, 303, 307, 308)) {
                    val location = conn.getHeaderField("Location") ?: throw IOException("Invalid camera redirect")
                    val next = URL(requested, location)
                    if (++redirects > 5 || !isTrustedMediaUrl(next.toString()) ||
                        next.host != requested.host || next.port != requested.port
                    ) throw IOException("Unsupported camera redirect")
                    conn.disconnect()
                    connection = null
                    requested = next
                    continue
                }
                if (code !in 200..299) throw MediaHttpException(code)
                stream = conn.inputStream
                val skip = if (code == 200) dataSpec.position else 0L
                remaining = when {
                    dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
                    conn.contentLengthLong >= 0L -> (conn.contentLengthLong - skip).coerceAtLeast(0)
                    else -> C.LENGTH_UNSET.toLong()
                }
                opened = true
                transferStarted(dataSpec)
                // Some HTTP servers ignore Range. Discard the prefix rather than
                // returning incorrect bytes to the transport-stream extractor.
                var toSkip = skip
                val discard = ByteArray(4_096)
                while (toSkip > 0) {
                    val read = stream!!.read(discard, 0, minOf(toSkip, discard.size.toLong()).toInt())
                    if (read < 0) throw EOFException("Camera range unavailable")
                    toSkip -= read
                    bytesTransferred(read)
                }
                return remaining
            }
        } catch (error: IOException) {
            runCatching { close() }
            // Retain typed HTTP status and timeout, without exposing a signed URL.
            throw sanitizedMediaFailure(error)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val requested = if (remaining == C.LENGTH_UNSET.toLong()) length else minOf(remaining, length.toLong()).toInt()
        val count = try {
            stream?.read(buffer, offset, requested) ?: return C.RESULT_END_OF_INPUT
        } catch (error: IOException) {
            throw sanitizedMediaFailure(error)
        }
        if (count < 0) {
            if (remaining > 0) throw EOFException("Incomplete camera segment")
            return C.RESULT_END_OF_INPUT
        }
        if (remaining != C.LENGTH_UNSET.toLong()) remaining -= count
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = connection?.url?.let { Uri.parse(it.toString()) }

    override fun getResponseHeaders(): Map<String, List<String>> = connection?.headerFields
        ?.filterKeys { it != null }?.mapKeys { it.key!! }.orEmpty()

    override fun close() {
        try {
            stream?.close()
        } catch (error: IOException) {
            throw sanitizedMediaFailure(error)
        } finally {
            stream = null
            connection?.disconnect()
            connection = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }
}

/** No raw cause: a platform network exception can include the signed media URL. */
private fun sanitizedMediaFailure(error: IOException): IOException = when (error) {
    is MediaHttpException -> error
    is SocketTimeoutException -> SocketTimeoutException("Camera connection timed out")
    is EOFException -> EOFException("Incomplete camera segment")
    else -> IOException("Camera connection failed")
}

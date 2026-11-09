package com.notyourflix.app

import android.content.pm.ActivityInfo
import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

private fun fmt(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@Composable
fun PlayerScreen(vm: Vm, id: String, restart: Boolean) {
    val m = vm.media.firstOrNull { it.id == id }
    if (m == null) { LaunchedEffect(Unit) { vm.screen = Screen.Main }; return }

    val ctx = LocalContext.current
    val view = LocalView.current
    val exo = remember { ExoPlayer.Builder(ctx).build() }
    val ended = remember { booleanArrayOf(false) }
    var playing by remember { mutableStateOf(true) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var show by remember { mutableStateOf(true) }
    var bump by remember { mutableIntStateOf(0) }
    var epi by remember { mutableIntStateOf(0) }
    val fr = remember { FocusRequester() }

    fun wake() { show = true; bump++ }
    fun save() {
        if (!ended[0] && exo.duration > 0) vm.saveProgress(m.id, exo.currentMediaItemIndex, exo.currentPosition, exo.duration)
    }
    fun seekBy(ms: Long) {
        val d = exo.duration
        val t = exo.currentPosition + ms
        exo.seekTo(if (d > 0) t.coerceIn(0L, d) else t.coerceAtLeast(0L))
        wake()
    }
    fun toggle() { if (exo.isPlaying) exo.pause() else exo.play(); wake() }

    DisposableEffect(Unit) {
        val act = ctx.findActivity()
        val ctl = act?.let { WindowCompat.getInsetsController(it.window, view) }
        ctl?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctl?.hide(WindowInsetsCompat.Type.systemBars())
        view.keepScreenOn = true
        act?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        val l = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { epi = exo.currentMediaItemIndex }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    ended[0] = true
                    vm.saveProgress(m.id, 0, 0, 0)
                    vm.screen = Screen.Detail(m.id)
                }
            }
        }
        exo.addListener(l)
        exo.setMediaItems(m.videos.map { MediaItem.fromUri(Uri.parse(it)) })
        val pr = vm.progress[m.id]
        if (!restart && pr != null && pr.positionMs > 3000) exo.seekTo(pr.index.coerceIn(0, m.videos.lastIndex), pr.positionMs)
        exo.prepare()
        exo.playWhenReady = true

        onDispose {
            save()
            exo.removeListener(l)
            exo.release()
            ctl?.show(WindowInsetsCompat.Type.systemBars())
            view.keepScreenOn = false
            act?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    LaunchedEffect(Unit) {
        var tick = 0
        while (true) {
            pos = exo.currentPosition
            dur = exo.duration.coerceAtLeast(0L)
            if (++tick % 10 == 0) save()
            delay(500)
        }
    }
    LaunchedEffect(bump, playing, show) { if (show && playing) { delay(4000); show = false } }
    LaunchedEffect(Unit) { delay(150); runCatching { fr.requestFocus() } }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(fr)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.MediaPlayPause, Key.Spacebar -> { toggle(); true }
                    Key.MediaPlay -> { exo.play(); wake(); true }
                    Key.MediaPause -> { exo.pause(); wake(); true }
                    Key.DirectionRight, Key.MediaFastForward -> { seekBy(10_000); true }
                    Key.DirectionLeft, Key.MediaRewind -> { seekBy(-10_000); true }
                    Key.DirectionUp, Key.DirectionDown -> { wake(); true }
                    Key.MediaNext, Key.ButtonR1 -> { if (exo.hasNextMediaItem()) exo.seekToNextMediaItem(); wake(); true }
                    Key.MediaPrevious, Key.ButtonL1 -> { exo.seekToPreviousMediaItem(); wake(); true }
                    else -> false
                }
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { show = !show; if (show) bump++ }
    ) {
        AndroidView(factory = { c -> PlayerView(c).apply { useController = false; player = exo } }, modifier = Modifier.fillMaxSize())

        AnimatedVisibility(show, enter = fadeIn(tween(250)), exit = fadeOut(tween(250))) {
            Column(
                Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xAA000000), Color.Transparent, Color(0xCC000000)))),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Btn("←") { vm.screen = Screen.Detail(m.id) }
                    Spacer(Modifier.width(14.dp))
                    val epTxt = if (m.videos.size > 1) "  ·  ${tr("episode")} ${epi + 1}" else ""
                    Text(m.title + epTxt, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
                Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
                    val range = maxOf(dur, 1L).toFloat()
                    Slider(
                        value = pos.toFloat().coerceIn(0f, range),
                        onValueChange = { exo.seekTo(it.toLong()); pos = it.toLong(); wake() },
                        valueRange = 0f..range,
                        colors = SliderDefaults.colors(thumbColor = Red, activeTrackColor = Red)
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(fmt(pos), fontSize = 13.sp, color = Muted)
                        Text(fmt(dur), fontSize = 13.sp, color = Muted)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
                        Btn("-10") { seekBy(-10_000) }
                        Btn(if (playing) tr("pause") else tr("play"), primary = true) { toggle() }
                        Btn("+10") { seekBy(10_000) }
                        if (m.videos.size > 1) Btn(tr("next_episode")) { if (exo.hasNextMediaItem()) exo.seekToNextMediaItem(); wake() }
                    }
                }
            }
        }
    }
}

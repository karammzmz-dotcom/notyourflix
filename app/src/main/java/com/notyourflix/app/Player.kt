package com.notyourflix.app

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.net.Uri
import android.util.Rational
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import java.util.Locale

fun enterPip(a: Activity) {
    runCatching {
        a.enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
    }
}

fun Episode.toMediaItem(): MediaItem {
    val b = MediaItem.Builder().setUri(Uri.parse(uri))
    if (subs.isNotEmpty()) {
        b.setSubtitleConfigurations(subs.map { s ->
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(s.uri))
                .setMimeType(s.mime).setLanguage(s.lang).setLabel(s.label).setSelectionFlags(0).build()
        })
    }
    return b.build()
}

private class TrackOpt(val label: String, val group: Tracks.Group, val index: Int, val selected: Boolean)

private fun trackOpts(t: Tracks, type: Int, prefix: String): List<TrackOpt> =
    t.groups.filter { it.type == type }.flatMap { g ->
        (0 until g.length).filter { g.isTrackSupported(it) }.map { i ->
            val f = g.getTrackFormat(i)
            val label = if (type == C.TRACK_TYPE_VIDEO) {
                if (f.height > 0) "${f.height}p" else "$prefix ${i + 1}"
            } else {
                f.label ?: f.language?.let { Locale(it).displayLanguage }?.takeIf { it.isNotBlank() } ?: "$prefix ${i + 1}"
            }
            TrackOpt(label, g, i, g.isTrackSelected(i))
        }
    }

@OptIn(ExperimentalLayoutApi::class)
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
    var tracks by remember { mutableStateOf(Tracks.EMPTY) }
    var speed by remember { mutableFloatStateOf(1f) }
    var optionsOpen by remember { mutableStateOf(false) }
    val fr = remember { FocusRequester() }
    val pip = vm.inPip

    val ep = m.episodes.getOrNull(epi)
    val canSkip = ep != null && ep.introStart >= 0 && ep.introEnd > ep.introStart && pos >= ep.introStart && pos < ep.introEnd

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
    fun skipIntro() { if (ep != null && canSkip) exo.seekTo(ep.introEnd); wake() }
    fun applySub(lang: String) {
        val b = exo.trackSelectionParameters.buildUpon()
        if (lang == "off") b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        else b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setPreferredTextLanguage(lang)
        exo.trackSelectionParameters = b.build()
    }

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
            override fun onTracksChanged(t: Tracks) { tracks = t }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    ended[0] = true
                    vm.saveProgress(m.id, 0, 0, 0)
                    vm.screen = Screen.Detail(m.id)
                }
            }
        }
        exo.addListener(l)
        exo.setMediaItems(m.episodes.map { it.toMediaItem() })
        applySub(vm.subLang)
        val pr = vm.progress[m.id]
        if (!restart && pr != null && pr.positionMs > 3000) exo.seekTo(pr.index.coerceIn(0, m.episodes.lastIndex), pr.positionMs)
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
    LaunchedEffect(bump, playing, show, optionsOpen) { if (show && playing && !optionsOpen) { delay(4000); show = false } }
    LaunchedEffect(optionsOpen) { delay(150); if (!optionsOpen) runCatching { fr.requestFocus() } }

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
                    Key.DirectionUp, Key.ButtonY -> { if (canSkip) skipIntro() else wake(); true }
                    Key.DirectionDown -> { wake(); true }
                    Key.ButtonX -> { optionsOpen = true; true }
                    Key.MediaNext, Key.ButtonR1 -> { if (exo.hasNextMediaItem()) exo.seekToNextMediaItem(); wake(); true }
                    Key.MediaPrevious, Key.ButtonL1 -> { exo.seekToPreviousMediaItem(); wake(); true }
                    else -> false
                }
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { show = !show; if (show) bump++ }
    ) {
        AndroidView(factory = { c -> PlayerView(c).apply { useController = false; player = exo } }, modifier = Modifier.fillMaxSize())

        AnimatedVisibility(show && !pip, enter = fadeIn(tween(250)), exit = fadeOut(tween(250))) {
            Column(
                Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xAA000000), Color.Transparent, Color(0xCC000000)))),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Btn("←") { vm.screen = Screen.Detail(m.id) }
                    Spacer(Modifier.width(14.dp))
                    val epName = ep?.title?.takeIf { it.isNotBlank() } ?: "${tr("episode")} ${epi + 1}"
                    val epTxt = if (m.episodes.size > 1) "  ·  $epName" else ""
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
                        Text(fmtTime(pos), fontSize = 13.sp, color = Muted)
                        Text(fmtTime(dur), fontSize = 13.sp, color = Muted)
                    }
                    Spacer(Modifier.height(8.dp))
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Btn("-10") { seekBy(-10_000) }
                        Btn(if (playing) tr("pause") else tr("play"), primary = true) { toggle() }
                        Btn("+10") { seekBy(10_000) }
                        if (m.episodes.size > 1) Btn(tr("next_episode")) { if (exo.hasNextMediaItem()) exo.seekToNextMediaItem(); wake() }
                        Btn(tr("options")) { optionsOpen = true; wake() }
                        Btn(tr("pip")) { ctx.findActivity()?.let { enterPip(it) } }
                    }
                }
            }
        }

        if (canSkip && !pip) {
            Btn(
                tr("skip_intro"),
                Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = if (show) 190.dp else 48.dp),
                primary = true
            ) { skipIntro() }
        }
    }

    if (optionsOpen) {
        val audio = trackOpts(tracks, C.TRACK_TYPE_AUDIO, tr("audio"))
        val video = trackOpts(tracks, C.TRACK_TYPE_VIDEO, tr("quality"))
        val videoAuto = exo.trackSelectionParameters.overrides.keys.none { it.type == C.TRACK_TYPE_VIDEO }
        NyDialog({ optionsOpen = false }) {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr("options"), fontSize = 20.sp, fontWeight = FontWeight.Bold)

                Text(tr("speed"), color = Muted)
                ChipFlow {
                    listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEachIndexed { i, v ->
                        val label = (if (v % 1f == 0f) v.toInt().toString() else v.toString()) + "x"
                        Pill(label, speed == v, if (i == 0) Modifier.autoFocus() else Modifier) { speed = v; exo.setPlaybackSpeed(v) }
                    }
                }

                Text(tr("subtitles"), color = Muted)
                val subsNow = ep?.subs ?: emptyList()
                if (subsNow.isEmpty()) Text(tr("no_subs"), color = Muted, fontSize = 13.sp)
                ChipFlow {
                    Pill(tr("off"), vm.subLang == "off") { vm.chooseSubLang("off"); applySub("off") }
                    subsNow.forEach { s ->
                        Pill(s.label, vm.subLang == s.lang) { vm.chooseSubLang(s.lang); applySub(s.lang) }
                    }
                }

                if (audio.size > 1) {
                    Text(tr("audio"), color = Muted)
                    ChipFlow {
                        audio.forEach { o ->
                            Pill(o.label, o.selected) {
                                exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                                    .setOverrideForType(TrackSelectionOverride(o.group.mediaTrackGroup, o.index)).build()
                            }
                        }
                    }
                }
                if (video.size > 1) {
                    Text(tr("quality"), color = Muted)
                    ChipFlow {
                        Pill(tr("auto"), videoAuto) {
                            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO).build()
                        }
                        video.forEach { o ->
                            Pill(o.label, !videoAuto && o.selected) {
                                exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                                    .setOverrideForType(TrackSelectionOverride(o.group.mediaTrackGroup, o.index)).build()
                            }
                        }
                    }
                }
                Btn(tr("done"), Modifier.fillMaxWidth(), primary = true) { optionsOpen = false }
            }
        }
    }
}

package com.notyourflix.app

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

@Composable
fun App(vm: Vm) {
    val p = vm.prefs
    val click = remember { { vm.sfx.click(vm.prefs) } }
    CompositionLocalProvider(
        LocalLang provides p.lang,
        LocalLayoutDirection provides (if (p.lang == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr),
        LocalClick provides click
    ) {
        NyTheme(p.font, p.fontPath) {
            Surface(
                Modifier.fillMaxSize().onPreviewKeyEvent { e ->
                    // Controller shoulder buttons switch tabs
                    if (e.type == KeyEventType.KeyDown && vm.screen is Screen.Main) {
                        when (e.key) {
                            Key.ButtonL1 -> { vm.tab = (vm.tab + TAB_COUNT - 1) % TAB_COUNT; true }
                            Key.ButtonR1 -> { vm.tab = (vm.tab + 1) % TAB_COUNT; true }
                            else -> false
                        }
                    } else false
                },
                color = Bg
            ) {
                val s = vm.screen
                BackHandler(enabled = s is Screen.Detail || s is Screen.Episodes || s is Screen.Edit || s is Screen.Upload || s is Screen.YtLogin || s is Screen.Player || s is Screen.ProfileIntro || (s is Screen.Main && vm.tab != TAB_HOME)) {
                    when (s) {
                        is Screen.Detail, is Screen.Upload -> vm.screen = Screen.Main
                        is Screen.Episodes -> vm.screen = Screen.Detail(s.id)
                        is Screen.YtLogin -> { vm.tab = TAB_SETTINGS; vm.screen = Screen.Main }
                        is Screen.Edit -> vm.screen = Screen.Detail(s.id)
                        is Screen.Player -> vm.screen = Screen.Detail(s.id)
                        is Screen.ProfileIntro -> vm.screen = Screen.Profiles
                        is Screen.Main -> vm.tab = TAB_HOME
                        else -> {}
                    }
                }
                AnimatedContent(
                    targetState = s,
                    transitionSpec = {
                        (fadeIn(tween(380)) + slideInHorizontally(tween(380)) { it / 14 } + scaleIn(tween(380), 0.97f)) togetherWith
                            fadeOut(tween(220))
                    },
                    label = "screen"
                ) { sc ->
                    when (sc) {
                        is Screen.Intro -> IntroScreen(p.intro, p.introUri, true, vm, { vm.screen = Screen.Profiles }) { AppLogo() }
                        is Screen.Profiles -> ProfilesScreen(vm)
                        is Screen.ProfileIntro -> {
                            val prof = vm.profiles.firstOrNull { it.id == sc.id }
                            IntroScreen(p.profileIntro, p.profileIntroUri, false, vm, { vm.screen = Screen.Main }) {
                                if (prof != null) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Avatar(prof, 150.dp)
                                    Spacer(Modifier.height(16.dp))
                                    Text(prof.name, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        is Screen.Main -> MainScreen(vm)
                        is Screen.Detail -> DetailScreen(vm, sc.id)
                        is Screen.Episodes -> EpisodesScreen(vm, sc.id)
                        is Screen.Edit -> EditScreen(vm, sc.id)
                        is Screen.Player -> PlayerScreen(vm, sc.id, sc.restart)
                        is Screen.Upload -> UploadScreen(vm)
                        is Screen.YtLogin -> YtLoginScreen(vm)
                    }
                }
            }
        }
    }
}

@Composable
fun AppLogo() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("NY", fontSize = 120.sp, fontWeight = FontWeight.Black, color = Red, letterSpacing = 4.sp)
        Text("NOTYOURFLIX", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Muted, letterSpacing = 8.sp)
    }
}

@Composable
fun IntroScreen(style: String, uri: String?, playSound: Boolean, vm: Vm, onDone: () -> Unit, content: @Composable () -> Unit) {
    val flag = remember { booleanArrayOf(false) }
    val finish = { if (!flag[0]) { flag[0] = true; onDone() } }
    LaunchedEffect(Unit) { if (playSound && style != "custom") vm.sfx.intro(vm.prefs) }
    when {
        style == "none" -> LaunchedEffect(Unit) { finish() }
        style == "custom" && uri != null -> VideoIntro(uri, finish)
        else -> AnimatedIntro(style, finish, content)
    }
}

@Composable
fun AnimatedIntro(style: String, finish: () -> Unit, content: @Composable () -> Unit) {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) { p.animateTo(1f, tween(1500, easing = FastOutSlowInEasing)); delay(700); finish() }
    val inf = rememberInfiniteTransition(label = "glow")
    val pulse by inf.animateFloat(0.5f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse")
    Box(
        Modifier.fillMaxSize().background(Color.Black).autoFocus()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { finish() },
        contentAlignment = Alignment.Center
    ) {
        if (style == "glow") {
            Box(Modifier.size(460.dp).graphicsLayer { alpha = pulse * p.value }
                .background(Brush.radialGradient(listOf(Red.copy(alpha = 0.6f), Color.Transparent))))
        }
        Box(Modifier.graphicsLayer {
            val v = p.value
            val s = if (style == "glow") 0.92f + 0.08f * v else 0.55f + 0.5f * v
            scaleX = s; scaleY = s; alpha = (v * 2.5f).coerceIn(0f, 1f)
        }) { content() }
    }
}

@Composable
fun VideoIntro(uri: String, finish: () -> Unit) {
    val ctx = LocalContext.current
    val exo = remember { ExoPlayer.Builder(ctx).build() }
    DisposableEffect(Unit) {
        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) finish() }
            override fun onPlayerError(error: PlaybackException) { finish() }
        }
        exo.addListener(l)
        exo.setMediaItem(MediaItem.fromUri(Uri.parse(uri)))
        exo.prepare(); exo.playWhenReady = true
        onDispose { exo.removeListener(l); exo.release() }
    }
    Box(
        Modifier.fillMaxSize().background(Color.Black).autoFocus()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { finish() }
    ) {
        AndroidView(factory = { c -> PlayerView(c).apply { useController = false; player = exo } }, modifier = Modifier.fillMaxSize())
    }
}

@Composable
fun ProfilesScreen(vm: Vm) {
    var pinFor by remember { mutableStateOf<Profile?>(null) }
    var pinErr by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    val wrong = tr("wrong_pin")

    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Not Your Flix", fontSize = 30.sp, fontWeight = FontWeight.Black, color = Red)
        Spacer(Modifier.height(28.dp))
        Text(tr("who"), fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(28.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(130.dp),
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            items(vm.profiles, key = { it.id }) { pr ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box {
                        Avatar(pr, 110.dp, (if (vm.profiles.first() == pr) Modifier.autoFocus() else Modifier)
                            .focusScale(1.1f, RoundedCornerShape(16.dp)) {
                                if (pr.pin != null) { pinErr = null; pinFor = pr } else vm.enterProfile(pr)
                            })
                        if (pr.pin != null) {
                            LockBadge(Modifier.align(Alignment.BottomEnd).padding(4.dp))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(pr.name, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    if (pr.kids) Text(tr("kids"), fontSize = 12.sp, color = Red, fontWeight = FontWeight.Bold)
                }
            }
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(110.dp).focusScale(1.1f, RoundedCornerShape(16.dp)) { adding = true }.background(CardC), contentAlignment = Alignment.Center) {
                        Text("+", fontSize = 48.sp, color = Muted)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(tr("add_profile"), fontSize = 15.sp, color = Muted, maxLines = 1)
                }
            }
        }
    }

    pinFor?.let { pr ->
        PinDialog(tr("enter_pin"), pinErr, onComplete = { pin ->
            if (vm.checkPin(pr, pin)) { pinFor = null; vm.enterProfile(pr) } else pinErr = wrong
        }, onDismiss = { pinFor = null })
    }
    if (adding) {
        ProfileDialog(tr("new_profile"), "", vm.profiles.size % avatarColors.size, true,
            onSave = { n, c, k -> vm.addProfile(n, c, k); adding = false }, onDismiss = { adding = false })
    }
}

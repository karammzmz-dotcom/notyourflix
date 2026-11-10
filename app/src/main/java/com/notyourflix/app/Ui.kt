package com.notyourflix.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import java.io.File

val Red = Color(0xFFE5143C)
val Bg = Color(0xFF0B0B0F)
val CardC = Color(0xFF1C1C24)
val Muted = Color(0xFFB3B3BD)
val avatarColors = listOf(
    Color(0xFFE5143C), Color(0xFF2E86DE), Color(0xFF10AC84),
    Color(0xFFF39C12), Color(0xFF8E44AD), Color(0xFF16A085)
)

val LocalClick = staticCompositionLocalOf<() -> Unit> { {} }

fun Context.findActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

fun fontFor(k: String, path: String? = null): FontFamily = when (k) {
    "custom" -> path?.let { p -> File(p).takeIf { it.exists() }?.let { f -> runCatching { FontFamily(Font(f)) }.getOrNull() } } ?: FontFamily.Default
    "sans" -> FontFamily.SansSerif
    "serif" -> FontFamily.Serif
    "mono" -> FontFamily.Monospace
    "cursive" -> FontFamily.Cursive
    else -> FontFamily.Default
}

private fun Typography.withFont(f: FontFamily) = Typography(
    displayLarge = displayLarge.copy(fontFamily = f), displayMedium = displayMedium.copy(fontFamily = f),
    displaySmall = displaySmall.copy(fontFamily = f), headlineLarge = headlineLarge.copy(fontFamily = f),
    headlineMedium = headlineMedium.copy(fontFamily = f), headlineSmall = headlineSmall.copy(fontFamily = f),
    titleLarge = titleLarge.copy(fontFamily = f), titleMedium = titleMedium.copy(fontFamily = f),
    titleSmall = titleSmall.copy(fontFamily = f), bodyLarge = bodyLarge.copy(fontFamily = f),
    bodyMedium = bodyMedium.copy(fontFamily = f), bodySmall = bodySmall.copy(fontFamily = f),
    labelLarge = labelLarge.copy(fontFamily = f), labelMedium = labelMedium.copy(fontFamily = f),
    labelSmall = labelSmall.copy(fontFamily = f)
)

@Composable
fun NyTheme(font: String, fontPath: String?, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Red, onPrimary = Color.White, background = Bg, surface = CardC,
            onSurface = Color.White, onBackground = Color.White, surfaceVariant = CardC
        ),
        typography = Typography().withFont(remember(font, fontPath) { fontFor(font, fontPath) }),
        content = content
    )
}

/** Focus-aware clickable: scales up and shows a white ring when focused by remote/controller. */
@Composable
fun Modifier.focusScale(
    scale: Float = 1.07f,
    shape: Shape = RoundedCornerShape(12.dp),
    onClick: () -> Unit
): Modifier {
    var focused by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (focused) scale else 1f, tween(180), label = "focusScale")
    val click = LocalClick.current
    return this
        .zIndex(if (focused) 1f else 0f)
        .graphicsLayer { scaleX = s; scaleY = s }
        .onFocusChanged { focused = it.isFocused }
        .clip(shape)
        .then(if (focused) Modifier.border(3.dp, Color.White, shape) else Modifier)
        .clickable { click(); onClick() }
}

@Composable
fun Modifier.autoFocus(): Modifier {
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(120); runCatching { fr.requestFocus() } }
    return this.focusRequester(fr)
}

@Composable
fun Avatar(p: Profile, dp: Dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(dp * 0.14f)
    val img = p.imagePath
    if (img != null && File(img).exists()) {
        AsyncImage(model = File(img), contentDescription = null, contentScale = ContentScale.Crop,
            modifier = modifier.size(dp).clip(shape))
    } else {
        Canvas(modifier.size(dp).clip(shape).background(avatarColors[p.colorIndex % avatarColors.size])) {
            val w = this.size.width
            val h = this.size.height
            val ink = Color(0xCC000000)
            drawLine(ink, Offset(w * 0.32f, h * 0.28f), Offset(w * 0.32f, h * 0.44f), strokeWidth = w * 0.06f, cap = StrokeCap.Round)
            drawLine(ink, Offset(w * 0.68f, h * 0.28f), Offset(w * 0.68f, h * 0.44f), strokeWidth = w * 0.06f, cap = StrokeCap.Round)
            drawArc(ink, 15f, 150f, false, Offset(w * 0.28f, h * 0.48f), Size(w * 0.44f, h * 0.28f),
                style = Stroke(w * 0.05f, cap = StrokeCap.Round))
        }
    }
}

@Composable
fun MediaCard(m: MediaEntry, modifier: Modifier, landscape: Boolean, progress: Float?, onClick: () -> Unit) {
    Box(
        modifier.aspectRatio(if (landscape) 16f / 9f else 2f / 3f)
            .focusScale(onClick = onClick).background(CardC)
    ) {
        val cp = m.coverPath
        if (cp != null && File(cp).exists()) {
            AsyncImage(model = File(cp), contentDescription = m.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(m.title.take(1).uppercase(), fontSize = 40.sp, fontWeight = FontWeight.Black, color = Muted, modifier = Modifier.align(Alignment.Center))
        }
        Text(m.rating, Modifier.align(Alignment.TopStart).padding(6.dp).background(Color(0xAA000000), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
            fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD000000))))
            .padding(start = 8.dp, end = 8.dp, top = 22.dp, bottom = if (progress != null) 10.dp else 8.dp)) {
            Text(m.title, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (progress != null) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp).background(Color(0x55FFFFFF))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(Red))
            }
        }
    }
}

@Composable
fun Pill(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.focusScale(1.06f, CircleShape, onClick = onClick)
            .background(if (selected) Color.White else CardC)
            .padding(horizontal = 18.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (selected) Color.Black else Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1)
    }
}

@Composable
fun Btn(text: String, modifier: Modifier = Modifier, primary: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier.focusScale(1.05f, RoundedCornerShape(10.dp), onClick = onClick)
            .background(if (primary) Red else CardC)
            .padding(horizontal = 22.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, fontWeight = FontWeight.Bold, fontSize = 16.sp, textAlign = TextAlign.Center) }
}

@Composable
fun NyDialog(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), color = CardC) {
            Column(Modifier.padding(24.dp).widthIn(min = 260.dp, max = 420.dp), content = content)
        }
    }
}

@Composable
fun OptionRow(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.fillMaxWidth().focusScale(1.03f, RoundedCornerShape(10.dp), onClick = onClick)
            .background(if (selected) Color(0x33E5143C) else Color(0x22FFFFFF)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 16.sp, modifier = Modifier.weight(1f))
        if (selected) Text("●", color = Red, fontWeight = FontWeight.Black)
    }
}

@Composable
fun ChoiceDialog(title: String, options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    NyDialog(onDismiss) {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(14.dp))
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEachIndexed { i, (k, l) ->
                OptionRow(l, k == selected, if (i == 0) Modifier.autoFocus() else Modifier) { onPick(k) }
            }
        }
    }
}

@Composable
fun ConfirmDialog(text: String, onYes: () -> Unit, onDismiss: () -> Unit) {
    NyDialog(onDismiss) {
        Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn(tr("cancel"), Modifier.autoFocus()) { onDismiss() }
            Btn(tr("delete"), primary = true) { onYes() }
        }
    }
}

@Composable
fun ColorRow(selected: Int, onPick: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 6.dp)) {
        avatarColors.forEachIndexed { i, c ->
            Box(Modifier.size(38.dp).focusScale(1.15f, CircleShape) { onPick(i) }.background(c)) {
                if (i == selected) Text("●", Modifier.align(Alignment.Center), fontWeight = FontWeight.Black)
            }
        }
    }
}

@Composable
fun ProfileDialog(title: String, initialName: String, initialColor: Int, withColor: Boolean, onSave: (String, Int, Boolean) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initialName) }
    var kids by remember { mutableStateOf(false) }
    var color by remember { mutableIntStateOf(initialColor) }
    NyDialog(onDismiss) {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(name, { name = it.take(20) }, label = { Text(tr("name")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (withColor) {
            Spacer(Modifier.height(10.dp))
            Text(tr("color"), color = Muted)
            ColorRow(color) { color = it }
            Spacer(Modifier.height(8.dp))
            Pill(tr("kids_profile"), kids) { kids = !kids }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn(tr("cancel")) { onDismiss() }
            Btn(tr("save"), primary = true) { if (name.isNotBlank()) onSave(name.trim(), color, kids) }
        }
    }
}

@Composable
fun PadKey(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.size(64.dp).focusScale(1.12f, CircleShape, onClick = onClick).background(Color(0x22FFFFFF)), contentAlignment = Alignment.Center) {
        Text(label, fontSize = if (label.length > 1) 16.sp else 24.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun LockBadge(modifier: Modifier = Modifier) {
    Canvas(modifier.size(26.dp).background(Color(0xCC000000), CircleShape)) {
        val w = size.width
        val h = size.height
        drawArc(Color.White, 180f, 180f, false, Offset(w * 0.34f, h * 0.2f), Size(w * 0.32f, h * 0.4f), style = Stroke(w * 0.07f))
        drawRoundRect(Color.White, Offset(w * 0.28f, h * 0.44f), Size(w * 0.44f, h * 0.3f), CornerRadius(w * 0.05f))
    }
}

@Composable
fun PinDialog(title: String, error: String?, onComplete: (String) -> Unit, onDismiss: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    NyDialog(onDismiss) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(4) { Box(Modifier.size(16.dp).clip(CircleShape).background(if (it < pin.length) Red else Color.DarkGray)) }
            }
            Text(error ?: " ", color = Red, fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))
            listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("Del", "0", "X")).forEach { row ->
                Row(Modifier.padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { k ->
                        PadKey(k, if (k == "5") Modifier.autoFocus() else Modifier) {
                            when (k) {
                                "Del" -> pin = pin.dropLast(1)
                                "X" -> onDismiss()
                                else -> if (pin.length < 4) {
                                    pin += k
                                    if (pin.length == 4) { val p = pin; pin = ""; onComplete(p) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}


@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipFlow(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

fun genreText(lang: String, g: String): String {
    val k = "g_" + g.lowercase()
    val t = Strings.get(lang, k)
    return if (t == k) g else t
}

@Composable
fun genreLabel(g: String): String = genreText(LocalLang.current, g)

@Composable
fun TextDialog(title: String, initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var t by remember { mutableStateOf(initial) }
    NyDialog(onDismiss) {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(t, { t = it.take(30) }, label = { Text(tr("group_name")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn(tr("cancel")) { onDismiss() }
            Btn(tr("save"), primary = true) { if (t.isNotBlank()) onSave(t.trim()) }
        }
    }
}

@Composable
fun GroupEditDialog(name: String, onSave: (String) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var t by remember { mutableStateOf(name) }
    NyDialog(onDismiss) {
        Text(tr("manage_groups"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(t, { t = it.take(30) }, label = { Text(tr("group_name")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn(tr("cancel")) { onDismiss() }
            Btn(tr("delete")) { onDelete() }
            Btn(tr("save"), primary = true) { if (t.isNotBlank()) onSave(t.trim()) }
        }
    }
}

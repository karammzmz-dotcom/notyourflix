package com.notyourflix.app

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*

/** Edit title, description, type, rating, genres and cover of an existing title. */
@Composable
fun EditScreen(vm: Vm, id: String) {
    val m = vm.media.firstOrNull { it.id == id }
    if (m == null) { LaunchedEffect(Unit) { vm.screen = Screen.Main }; return }
    val ctx = LocalContext.current
    var title by remember { mutableStateOf(m.title) }
    var desc by remember { mutableStateOf(m.description) }
    var type by remember { mutableStateOf(m.type) }
    var rating by remember { mutableStateOf(m.rating) }
    var genres by remember { mutableStateOf(m.genres) }
    var customGenre by remember { mutableStateOf("") }
    val pickCover = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) vm.setCover(m.id, u, false) }
    val pickCoverWide = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) vm.setCover(m.id, u, true) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Btn("←", Modifier.autoFocus()) { vm.screen = Screen.Detail(m.id) }
                Spacer(Modifier.width(14.dp))
                Text(tr("edit_title"), fontSize = 28.sp, fontWeight = FontWeight.Black)
            }
            OutlinedTextField(title, { title = it }, label = { Text(tr("title")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(desc, { desc = it }, label = { Text(tr("description")) }, minLines = 3, modifier = Modifier.fillMaxWidth())
            Text(tr("video_type"), color = Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill(tr("movie"), type == "movie") { type = "movie" }
                Pill(tr("series"), type == "series") { type = "series" }
            }
            Text(tr("age_rating"), color = Muted)
            ChipFlow { RATINGS.forEach { r -> Pill(r, rating == r) { rating = r } } }
            Text(tr("genres"), color = Muted)
            ChipFlow {
                (PRESET_GENRES + genres.filter { it !in PRESET_GENRES }).forEach { g ->
                    Pill(genreLabel(g), g in genres) { genres = if (g in genres) genres - g else genres + g }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(customGenre, { customGenre = it.take(20) }, label = { Text(tr("add_genre")) }, singleLine = true, modifier = Modifier.weight(1f))
                Btn("+") {
                    val g = customGenre.trim()
                    if (g.isNotEmpty() && g !in genres) genres = genres + g
                    customGenre = ""
                }
            }
            Text(tr("cover_vertical"), color = Muted)
            Btn(tr("change_cover")) { pickCover.launch(arrayOf("image/*")) }
            Text(tr("cover_horizontal"), color = Muted)
            Btn(tr("change_cover")) { pickCoverWide.launch(arrayOf("image/*")) }
            Spacer(Modifier.height(10.dp))
            Btn(tr("save"), Modifier.fillMaxWidth(), primary = true) {
                if (title.isBlank()) Toast.makeText(ctx, Strings.get(vm.prefs.lang, "fill_form"), Toast.LENGTH_SHORT).show()
                else { vm.updateMedia(m.id, title.trim(), desc.trim(), type, genres, rating); vm.screen = Screen.Detail(m.id) }
            }
        }
    }
}

/** Rename, reorder and delete episodes; add subtitles and intro timestamps to each. */
@Composable
fun EpisodesScreen(vm: Vm, id: String) {
    val m = vm.media.firstOrNull { it.id == id }
    if (m == null) { LaunchedEffect(Unit) { vm.screen = Screen.Main }; return }
    val ctx = LocalContext.current
    var idx by remember { mutableStateOf<Int?>(null) }
    var draft by remember { mutableStateOf<Episode?>(null) }
    var pendingSub by remember { mutableStateOf<Uri?>(null) }
    val pickSub = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) { persist(ctx, u); pendingSub = u }
    }

    fun move(i: Int, d: Int) {
        val j = i + d
        if (j !in m.episodes.indices) return
        val l = m.episodes.toMutableList()
        val t = l[i]; l[i] = l[j]; l[j] = t
        vm.setEpisodes(m.id, l)
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Btn("←", Modifier.autoFocus()) { vm.screen = Screen.Detail(m.id) }
            Spacer(Modifier.width(14.dp))
            Text(tr("manage_episodes") + "  ·  " + m.title, fontSize = 22.sp, fontWeight = FontWeight.Black, maxLines = 1)
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            itemsIndexed(m.episodes) { i, e ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CardC).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("${i + 1}. " + (e.title.ifBlank { "${tr("episode")} ${i + 1}" }), fontWeight = FontWeight.Bold, maxLines = 1)
                        val parts = mutableListOf<String>()
                        if (e.subs.isNotEmpty()) parts += e.subs.joinToString(", ") { it.label }
                        if (e.introStart >= 0 && e.introEnd > e.introStart) parts += tr("intro_label") + " " + fmtTime(e.introStart) + " - " + fmtTime(e.introEnd)
                        if (parts.isNotEmpty()) Text(parts.joinToString("  ·  "), color = Muted, fontSize = 12.sp, maxLines = 1)
                    }
                    Btn("↑") { move(i, -1) }
                    Btn("↓") { move(i, 1) }
                    Btn(tr("edit")) { idx = i; draft = e }
                }
            }
        }
    }

    val d = draft
    val di = idx
    if (d != null && di != null) {
        EpisodeEditor(
            d, m.episodes.size > 1,
            onChange = { draft = it },
            onAddSub = { pickSub.launch(arrayOf("*/*")) },
            onSave = {
                val l = m.episodes.toMutableList(); l[di] = d
                vm.setEpisodes(m.id, l); draft = null; idx = null
            },
            onDelete = {
                val l = m.episodes.toMutableList(); l.removeAt(di)
                vm.setEpisodes(m.id, l); draft = null; idx = null
            },
            onDismiss = { draft = null; idx = null }
        )
    }
    pendingSub?.let { u ->
        ChoiceDialog(tr("sub_lang"), LANGS, makeSub(u, displayName(ctx, u)).lang, { code ->
            draft = draft?.let { dd -> dd.copy(subs = dd.subs + makeSub(u, displayName(ctx, u)).copy(lang = code, label = langLabel(code))) }
            pendingSub = null
        }) { pendingSub = null }
    }
}

@Composable
fun EpisodeEditor(
    ep: Episode, canDelete: Boolean, onChange: (Episode) -> Unit, onAddSub: () -> Unit,
    onSave: () -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit
) {
    var s by remember { mutableStateOf(if (ep.introStart >= 0) fmtTime(ep.introStart) else "") }
    var e by remember { mutableStateOf(if (ep.introEnd >= 0) fmtTime(ep.introEnd) else "") }
    NyDialog(onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(tr("edit"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            OutlinedTextField(ep.title, { onChange(ep.copy(title = it.take(60))) }, label = { Text(tr("episode_title")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text(tr("intro_label"), color = Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(s, { s = it; onChange(ep.copy(introStart = parseTime(it) ?: -1)) }, label = { Text(tr("intro_start")) }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(e, { e = it; onChange(ep.copy(introEnd = parseTime(it) ?: -1)) }, label = { Text(tr("intro_end")) }, singleLine = true, modifier = Modifier.weight(1f))
            }
            Text(tr("subtitles"), color = Muted)
            ep.subs.forEach { sb ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${sb.label} (${sb.lang})", Modifier.weight(1f), maxLines = 1)
                    Btn(tr("remove")) { onChange(ep.copy(subs = ep.subs - sb)) }
                }
            }
            Btn(tr("add_subtitle"), Modifier.fillMaxWidth()) { onAddSub() }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Btn(tr("cancel")) { onDismiss() }
                if (canDelete) Btn(tr("delete")) { onDelete() }
                Btn(tr("save"), primary = true) { onSave() }
            }
        }
    }
}

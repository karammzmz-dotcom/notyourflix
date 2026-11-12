package com.notyourflix.app

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import coil.compose.AsyncImage
import java.io.File

@Composable
fun MainScreen(vm: Vm) {
    val profile = vm.current ?: return
    Column(Modifier.fillMaxSize()) {
        TopBar(vm.tab, { vm.tab = it }, profile) { vm.screen = Screen.Profiles }
        AnimatedContent(
            targetState = vm.tab,
            transitionSpec = {
                val dir = if (targetState > initialState) 1 else -1
                (fadeIn(tween(300)) + slideInHorizontally(tween(300)) { dir * it / 10 }) togetherWith
                    (fadeOut(tween(200)) + slideOutHorizontally(tween(200)) { -dir * it / 10 })
            },
            label = "tabs"
        ) { t ->
            when (t) {
                TAB_SEARCH -> SearchTab(vm)
                TAB_HOME -> HomeTab(vm)
                TAB_SHOWS -> TypeTab(vm, "series")
                TAB_MOVIES -> TypeTab(vm, "movie")
                else -> SettingsTab(vm)
            }
        }
    }
}

@Composable
fun TopBar(tab: Int, onTab: (Int) -> Unit, profile: Profile, onProfile: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < 560.dp
        Row(
            Modifier.fillMaxWidth().padding(horizontal = if (compact) 12.dp else 28.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("NY", fontSize = if (compact) 26.sp else 34.sp, fontWeight = FontWeight.Black, color = Red)
            Box(Modifier.weight(1f).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SearchTabIcon(tab == TAB_SEARCH) { onTab(TAB_SEARCH) }
                    listOf(TAB_HOME to "home", TAB_SHOWS to "shows", TAB_MOVIES to "movies", TAB_SETTINGS to "settings").forEach { (i, k) ->
                        Pill(tr(k), tab == i, small = compact) { onTab(i) }
                    }
                }
            }
            if (!compact) {
                Text(profile.name, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                Spacer(Modifier.width(10.dp))
            }
            Avatar(profile, 38.dp, Modifier.focusScale(1.12f, RoundedCornerShape(6.dp)) { onProfile() })
        }
    }
}

@Composable
fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp))
        content()
    }
}

@Composable
fun HomeTab(vm: Vm) {
    val list = vm.visibleMedia
    val isKids = vm.current?.kids == true
    if (list.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(tr("empty_title"), fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(tr("empty_sub"), color = Muted)
            Spacer(Modifier.height(18.dp))
            if (!isKids) Btn(tr("upload_movie"), Modifier.autoFocus(), primary = true) { vm.screen = Screen.Upload }
        }
        return
    }
    val cont = list.mapNotNull { m -> vm.progress[m.id]?.takeIf { it.positionMs > 3000 }?.let { m to it } }
        .sortedByDescending { it.second.updatedAt }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < 600.dp
        val pw = if (compact) 118.dp else 160.dp
        val lw = if (compact) 190.dp else 260.dp
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
            item { HeroBanner(vm, list.first(), compact) }
            if (cont.isNotEmpty()) item {
                Section(tr("continue_watching")) {
                    LazyRow(contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(cont, key = { it.first.id }) { (m, pr) ->
                            val frac = if (pr.durationMs > 0) pr.positionMs.toFloat() / pr.durationMs else 0f
                            MediaCard(m, Modifier.width(lw), true, frac) { vm.screen = Screen.Detail(m.id) }
                        }
                    }
                }
            }
            item {
                Section(tr("for_you")) {
                    LazyRow(contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(list, key = { it.id }) { m ->
                            MediaCard(m, Modifier.width(pw), false, null) { vm.screen = Screen.Detail(m.id) }
                        }
                    }
                }
            }
            vm.groups.forEach { g ->
                val gm = g.ids.mapNotNull { id -> list.firstOrNull { it.id == id } }
                if (gm.isNotEmpty()) item(key = "group_${g.id}") {
                    Section(g.name) {
                        LazyRow(contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            items(gm, key = { it.id }) { m ->
                                MediaCard(m, Modifier.width(pw), false, null) { vm.screen = Screen.Detail(m.id) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SearchTab(vm: Vm) {
    var q by rememberSaveable { mutableStateOf("") }
    var fGenre by rememberSaveable { mutableStateOf("") }
    var fRating by rememberSaveable { mutableStateOf("") }
    val res = vm.visibleMedia.filter { m ->
        (m.title.contains(q.trim(), ignoreCase = true) || m.genres.any { it.contains(q.trim(), ignoreCase = true) }) &&
            (fGenre.isEmpty() || fGenre in m.genres) && (fRating.isEmpty() || m.rating == fRating)
    }
    val arabic = vm.prefs.lang == "ar"
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= 720.dp) {
            Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.width(300.dp)) {
                    Box(Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(10.dp)).background(CardC).padding(horizontal = 14.dp), Alignment.CenterStart) {
                        Text(if (q.isEmpty()) tr("search_hint") else q, color = if (q.isEmpty()) Muted else Color.White, maxLines = 1)
                    }
                    Spacer(Modifier.height(12.dp))
                    OnScreenKeyboard(arabic, q) { q = it }
                }
                Column(Modifier.weight(1f)) {
                    SearchFilters(vm.visibleMedia, fGenre, fRating, { fGenre = it }, { fRating = it })
                    SearchResults(vm, res, Modifier.weight(1f))
                }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                OutlinedTextField(q, { q = it }, placeholder = { Text(tr("search_hint")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                SearchFilters(vm.visibleMedia, fGenre, fRating, { fGenre = it }, { fRating = it })
                SearchResults(vm, res, Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun SearchResults(vm: Vm, res: List<MediaEntry>, modifier: Modifier) {
    if (res.isEmpty()) {
        Box(modifier, Alignment.Center) { Text(tr("no_results"), color = Muted, fontSize = 18.sp) }
        return
    }
    LazyVerticalGrid(
        GridCells.Adaptive(130.dp), modifier, contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        items(res, key = { it.id }) { m -> MediaCard(m, Modifier.fillMaxWidth(), false, null) { vm.screen = Screen.Detail(m.id) } }
    }
}

@Composable
fun OnScreenKeyboard(arabic: Boolean, q: String, onChange: (String) -> Unit) {
    val letters = if (arabic) "ابتثجحخدذرزسشصضطظعغفقكلمنهوي".map { it.toString() } else ('A'..'Z').map { it.toString() }
    val keys = letters + listOf("SPACE", "DEL")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        keys.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { k ->
                    Box(
                        Modifier.weight(1f).height(46.dp).focusScale(1.1f, RoundedCornerShape(8.dp)) {
                            onChange(when (k) { "DEL" -> q.dropLast(1); "SPACE" -> "$q "; else -> q + k.lowercase() })
                        }.background(CardC),
                        contentAlignment = Alignment.Center
                    ) { Text(when (k) { "SPACE" -> tr("space"); "DEL" -> tr("delete"); else -> k }, fontSize = if (k.length > 1) 13.sp else 18.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

class SRow(val title: String, val value: String?, val action: () -> Unit)

@Composable
fun SettingsTab(vm: Vm) {
    val ctx = LocalContext.current
    val me = vm.current ?: return
    var dlg by remember { mutableStateOf("") }
    var pinErr by remember { mutableStateOf<String?>(null) }
    var firstPin by remember { mutableStateOf<String?>(null) }
    var guard by remember { mutableStateOf<(() -> Unit)?>(null) }
    var editGroup by remember { mutableStateOf<String?>(null) }
    val p = vm.prefs
    // Changes that loosen restrictions ask for the profile PIN first, if the profile has one.
    fun guarded(action: () -> Unit) {
        if (me.pin == null) action() else { guard = action; pinErr = null; dlg = "guard" }
    }
    fun adult(r: SRow): SRow? = if (me.kids) null else r

    val pickImg = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.setProfileImage(uri) }
    val pickFont = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.setCustomFont(uri) { ok ->
            Toast.makeText(ctx, Strings.get(vm.prefs.lang, if (ok) "saved" else "bad_font"), Toast.LENGTH_SHORT).show()
        }
    }
    val pickSound = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { persist(ctx, uri); vm.updatePrefs { it.copy(sound = "custom", soundUri = uri.toString()) } }
    }
    val pickIntro = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { persist(ctx, uri); vm.updatePrefs { it.copy(intro = "custom", introUri = uri.toString()) } }
    }
    val pickPIntro = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { persist(ctx, uri); vm.updatePrefs { it.copy(profileIntro = "custom", profileIntroUri = uri.toString()) } }
    }

    val fontOpts = listOf("default" to tr("font_default"), "sans" to tr("font_sans"), "serif" to tr("font_serif"), "mono" to tr("font_mono"), "cursive" to tr("font_hand")) +
        (if (p.fontPath != null) listOf("custom" to tr("my_font")) else emptyList()) +
        listOf("upload" to tr("custom"))
    val soundOpts = listOf("off" to tr("off"), "soft" to tr("soft"), "retro" to tr("retro"), "chime" to tr("chime"), "custom" to tr("custom"))
    val introOpts = listOf("none" to tr("none"), "zoom" to tr("zoom"), "glow" to tr("glow"), "custom" to tr("custom"))
    fun label(opts: List<Pair<String, String>>, k: String) = opts.firstOrNull { it.first == k }?.second

    val rows = listOfNotNull(
        SRow(tr("change_font"), label(fontOpts, p.font)) { dlg = "font" },
        SRow(tr("change_profile"), null) { vm.screen = Screen.Profiles },
        SRow(tr("change_image"), null) { dlg = "image" },
        SRow(tr("change_name"), me.name) { dlg = "name" },
        SRow(tr("change_lang"), if (p.lang == "ar") "العربية" else "English") { dlg = "lang" },
        adult(SRow(tr("upload_movie"), null) { vm.screen = Screen.Upload }),
        adult(SRow(tr("manage_groups"), null) { dlg = "groups" }),
        SRow(if (me.pin == null) tr("lock_add") else tr("lock_remove"), null) { pinErr = null; firstPin = null; dlg = if (me.pin == null) "lockAdd" else "lockRemove" },
        SRow(tr("kids_mode"), if (me.kids) tr("on") else tr("off")) {
            if (!me.kids) vm.setKids(true)
            else if (me.kidsPin != null) { guard = { vm.setKids(false) }; pinErr = null; dlg = "guardKids" }
            else guarded { vm.setKids(false) }
        },
        SRow(if (me.kidsPin == null) tr("kids_lock_add") else tr("kids_lock_remove"), null) {
            pinErr = null; firstPin = null
            dlg = if (me.kidsPin == null) "kidsLockAdd" else "kidsLockRemove"
        },
        SRow(tr("age_filter"), if (me.kids) tr("locked_kids") else if (me.maxRating == "NC-17") tr("no_limit") else me.maxRating) {
            if (me.kids) Toast.makeText(ctx, Strings.get(p.lang, "locked_kids"), Toast.LENGTH_SHORT).show() else dlg = "age"
        },
        adult(SRow(tr("new_profile"), null) { dlg = "new" }),
        SRow(tr("change_sound"), label(soundOpts, p.sound)) { dlg = "sound" },
        SRow(tr("change_intro"), label(introOpts, p.intro)) { dlg = "intro" },
        SRow(tr("change_intro_profile"), label(introOpts, p.profileIntro)) { dlg = "pintro" },
        adult(SRow(tr("delete_profile"), null) {
            if (vm.profiles.size <= 1) Toast.makeText(ctx, Strings.get(p.lang, "last_profile"), Toast.LENGTH_SHORT).show() else dlg = "delete"
        })
    )

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(me, 64.dp)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(tr("settings"), fontSize = 26.sp, fontWeight = FontWeight.Black)
                Text(me.name, color = Muted)
            }
        }
        Spacer(Modifier.height(14.dp))
        LazyVerticalGrid(
            GridCells.Adaptive(300.dp), contentPadding = PaddingValues(vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(rows) { r ->
                Row(
                    Modifier.fillMaxWidth().focusScale(1.03f, RoundedCornerShape(14.dp)) { r.action() }.background(CardC).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(r.title, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        if (r.value != null) Text(r.value, color = Muted, fontSize = 13.sp)
                    }
                }
            }
        }
    }

    when (dlg) {
        "font" -> ChoiceDialog(tr("change_font"), fontOpts, p.font, { k ->
            dlg = ""
            if (k == "upload") pickFont.launch(arrayOf("*/*")) else vm.updatePrefs { it.copy(font = k) }
        }) { dlg = "" }
        "lang" -> ChoiceDialog(tr("change_lang"), listOf("en" to "English", "ar" to "العربية"), p.lang, { k -> vm.updatePrefs { it.copy(lang = k) }; dlg = "" }) { dlg = "" }
        "sound" -> ChoiceDialog(tr("change_sound"), soundOpts, p.sound, { k ->
            dlg = ""
            if (k == "custom") pickSound.launch(arrayOf("audio/*")) else { vm.updatePrefs { it.copy(sound = k) }; vm.sfx.click(vm.prefs) }
        }) { dlg = "" }
        "intro" -> ChoiceDialog(tr("change_intro"), introOpts, p.intro, { k ->
            dlg = ""
            if (k == "custom") pickIntro.launch(arrayOf("video/*")) else vm.updatePrefs { it.copy(intro = k) }
        }) { dlg = "" }
        "pintro" -> ChoiceDialog(tr("change_intro_profile"), introOpts, p.profileIntro, { k ->
            dlg = ""
            if (k == "custom") pickPIntro.launch(arrayOf("video/*")) else vm.updatePrefs { it.copy(profileIntro = k) }
        }) { dlg = "" }
        "name" -> ProfileDialog(tr("change_name"), me.name, me.colorIndex, false, { n, _, _ -> vm.updateProfile(me.copy(name = n)); dlg = "" }) { dlg = "" }
        "new" -> ProfileDialog(tr("new_profile"), "", vm.profiles.size % avatarColors.size, true, { n, c, k -> vm.addProfile(n, c, k); dlg = "" }) { dlg = "" }
        "image" -> NyDialog({ dlg = "" }) {
            Text(tr("change_image"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(tr("color"), color = Muted)
            ColorRow(if (me.imagePath == null) me.colorIndex else -1) { vm.setProfileColor(it); dlg = "" }
            Spacer(Modifier.height(10.dp))
            Btn(tr("gallery"), Modifier.fillMaxWidth().autoFocus(), primary = true) { dlg = ""; pickImg.launch(arrayOf("image/*")) }
            Spacer(Modifier.height(8.dp))
            Btn(tr("default_face"), Modifier.fillMaxWidth()) { vm.setProfileColor(me.colorIndex); dlg = "" }
        }
        "lockAdd" -> PinDialog(if (firstPin == null) tr("set_pin") else tr("confirm_pin"), pinErr, { pin ->
            val f = firstPin
            if (f == null) { firstPin = pin; pinErr = null }
            else if (f == pin) { vm.setPin(pin); dlg = "" }
            else { pinErr = Strings.get(p.lang, "pin_mismatch"); firstPin = null }
        }) { dlg = "" }
        "lockRemove" -> PinDialog(tr("enter_pin"), pinErr, { pin ->
            if (vm.checkPin(me, pin)) { vm.setPin(null); dlg = "" } else pinErr = Strings.get(p.lang, "wrong_pin")
        }) { dlg = "" }
        "guard" -> PinDialog(tr("enter_pin"), pinErr, { pin ->
            if (vm.checkPin(me, pin)) { val a = guard; guard = null; dlg = ""; a?.invoke() } else pinErr = Strings.get(p.lang, "wrong_pin")
        }) { dlg = ""; guard = null }
        "guardKids" -> PinDialog(tr("enter_pin"), pinErr, { pin ->
            if (vm.checkKidsPin(me, pin)) { val a = guard; guard = null; dlg = ""; a?.invoke() } else pinErr = Strings.get(p.lang, "wrong_pin")
        }) { dlg = ""; guard = null }
        "kidsLockAdd" -> PinDialog(if (firstPin == null) tr("set_pin") else tr("confirm_pin"), pinErr, { pin ->
            val f = firstPin
            if (f == null) { firstPin = pin; pinErr = null }
            else if (f == pin) { vm.setKidsPin(pin); dlg = "" }
            else { pinErr = Strings.get(p.lang, "pin_mismatch"); firstPin = null }
        }) { dlg = "" }
        "kidsLockRemove" -> PinDialog(tr("enter_pin"), pinErr, { pin ->
            if (vm.checkKidsPin(me, pin)) { vm.setKidsPin(null); dlg = "" } else pinErr = Strings.get(p.lang, "wrong_pin")
        }) { dlg = "" }
        "age" -> ChoiceDialog(tr("age_filter"), RATINGS.map { it to (if (it == "NC-17") tr("no_limit") else it) }, me.maxRating, { k ->
            dlg = ""
            guarded { vm.setMaxRating(k) }
        }) { dlg = "" }
        "groups" -> NyDialog({ dlg = "" }) {
            Text(tr("manage_groups"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (vm.groups.isEmpty()) Text(tr("no_groups"), color = Muted)
                vm.groups.forEachIndexed { i, g ->
                    OptionRow("${g.name}  (${g.ids.size})", false, if (i == 0) Modifier.autoFocus() else Modifier) { editGroup = g.id; dlg = "groupEdit" }
                }
            }
            Spacer(Modifier.height(14.dp))
            Btn(tr("new_group"), Modifier.fillMaxWidth(), primary = true) { dlg = "groupNew" }
        }
        "groupNew" -> TextDialog(tr("new_group"), "", { n -> vm.addGroup(n); dlg = "groups" }) { dlg = "groups" }
        "groupEdit" -> {
            val g = vm.groups.firstOrNull { it.id == editGroup }
            if (g == null) dlg = "groups"
            else GroupEditDialog(g.name, { n -> vm.renameGroup(g.id, n); dlg = "groups" }, { vm.deleteGroup(g.id); dlg = "groups" }) { dlg = "groups" }
        }
        "delete" -> ConfirmDialog(tr("delete_profile_confirm"), { dlg = ""; vm.deleteProfile(me.id) }) { dlg = "" }
    }
}

@Composable
fun UploadScreen(vm: Vm) {
    val ctx = LocalContext.current
    var title by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("movie") }
    var cover by remember { mutableStateOf<Uri?>(null) }
    var videos by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var genres by remember { mutableStateOf<List<String>>(emptyList()) }
    var customGenre by remember { mutableStateOf("") }
    var subs by remember { mutableStateOf<List<Uri>>(emptyList()) }
    val pickSubs = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { l ->
        if (l.isNotEmpty()) { l.forEach { persist(ctx, it) }; subs = l }
    }
    var rating by remember { mutableStateOf("R") }
    var groupIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    val lang = vm.prefs.lang

    val pickCover = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { cover = it }
    var coverWide by remember { mutableStateOf<Uri?>(null) }
    val pickCoverWide = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { coverWide = it }
    var logo by remember { mutableStateOf<Uri?>(null) }
    val pickLogo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { logo = it }
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { l ->
        if (l.isNotEmpty()) { l.forEach { persist(ctx, it) }; videos = l }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Btn("←", Modifier.autoFocus()) { vm.screen = Screen.Main }
                Spacer(Modifier.width(14.dp))
                Text(tr("upload"), fontSize = 28.sp, fontWeight = FontWeight.Black)
            }
            Text(tr("video_type"), color = Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill(tr("movie"), type == "movie") { type = "movie" }
                Pill(tr("series"), type == "series") { type = "series" }
            }
            OutlinedTextField(title, { title = it }, label = { Text(tr("title")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(desc, { desc = it }, label = { Text(tr("description")) }, minLines = 3, modifier = Modifier.fillMaxWidth())
            Text(tr("cover_vertical"), color = Muted)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Btn(tr("upload_own_image")) { pickCover.launch(arrayOf("image/*")) }
                if (cover != null) Text(tr("selected"), color = Red, fontWeight = FontWeight.Bold)
            }
            Text(tr("cover_horizontal"), color = Muted)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Btn(tr("upload_own_image")) { pickCoverWide.launch(arrayOf("image/*")) }
                if (coverWide != null) Text(tr("selected"), color = Red, fontWeight = FontWeight.Bold)
            }
            Text(tr("title_logo"), color = Muted)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Btn(tr("upload_own_image")) { pickLogo.launch(arrayOf("image/*")) }
                if (logo != null) Text(tr("selected"), color = Red, fontWeight = FontWeight.Bold)
            }
            Text(tr("video_file"), color = Muted)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Btn(tr("upload")) { pickVideo.launch(arrayOf("video/*")) }
                if (videos.isNotEmpty()) Text("${videos.size}", color = Red, fontWeight = FontWeight.Bold)
            }
            Text(tr("subtitle_files"), color = Muted)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Btn(tr("upload")) { pickSubs.launch(arrayOf("*/*")) }
                if (subs.isNotEmpty()) Text("${subs.size}", color = Red, fontWeight = FontWeight.Bold)
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
            if (vm.groups.isNotEmpty()) {
                Text(tr("groups"), color = Muted)
                ChipFlow {
                    vm.groups.forEach { g -> Pill(g.name, g.id in groupIds) { groupIds = if (g.id in groupIds) groupIds - g.id else groupIds + g.id } }
                }
            }
            Spacer(Modifier.height(10.dp))
            Btn(if (busy) tr("working") else tr("finish"), Modifier.fillMaxWidth(), primary = true) {
                if (busy) return@Btn
                if (title.isBlank() || videos.isEmpty()) {
                    Toast.makeText(ctx, Strings.get(lang, "fill_form"), Toast.LENGTH_SHORT).show()
                } else {
                    busy = true
                    vm.addMedia(title.trim(), desc.trim(), type, genres, rating, groupIds.toList(), cover, coverWide, logo, videos, subs) {
                        Toast.makeText(ctx, Strings.get(lang, "saved"), Toast.LENGTH_SHORT).show()
                        vm.tab = TAB_HOME; vm.screen = Screen.Main
                    }
                }
            }
        }
    }
}

@Composable
fun DetailScreen(vm: Vm, id: String) {
    val m = vm.media.firstOrNull { it.id == id }
    if (m == null) { LaunchedEffect(Unit) { vm.screen = Screen.Main }; return }
    val pr = vm.progress[m.id]
    val resumable = pr != null && pr.positionMs > 3000
    var confirm by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val pickMore = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { l ->
        if (l.isNotEmpty()) { l.forEach { persist(ctx, it) }; vm.addEpisodes(m.id, l) }
    }
    val cp = m.poster()
    val bg = m.backdrop()
    val kids = vm.current?.kids == true
    var groupDlg by remember { mutableStateOf(false) }
    val meta = (if (m.type == "movie") tr("movie") else "${tr("series")} · ${m.videos.size} ${tr("episodes")}") + " · " + m.rating

    Box(Modifier.fillMaxSize()) {
        if (bg != null) AsyncImage(File(bg), null, Modifier.fillMaxSize().blur(12.dp).graphicsLayer { alpha = 0.35f }, contentScale = ContentScale.Crop)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 640.dp
            val info: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(m.title, fontSize = if (wide) 36.sp else 28.sp, fontWeight = FontWeight.Black)
                    Text(meta, color = Muted, fontWeight = FontWeight.Bold)
                    if (m.genres.isNotEmpty()) Text(m.genres.joinToString(", ") { genreText(vm.prefs.lang, it) }, color = Muted)
                    if (m.description.isNotBlank()) Text(m.description, fontSize = 16.sp, color = Color(0xFFDDDDE4))
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Btn(if (resumable) tr("resume") else tr("play"), Modifier.autoFocus(), primary = true) { vm.screen = Screen.Player(m.id, false) }
                        if (resumable) Btn(tr("restart")) { vm.screen = Screen.Player(m.id, true) }
                    }
                    if (!kids) {
                        ChipFlow {
                            Btn(tr("edit")) { vm.screen = Screen.Edit(m.id) }
                            Btn(tr("manage_episodes")) { vm.screen = Screen.Episodes(m.id) }
                            Btn(tr("add_to_group")) { groupDlg = true }
                            if (m.type == "series") Btn(tr("add_episodes")) { pickMore.launch(arrayOf("video/*")) }
                            Btn(tr("delete")) { confirm = true }
                        }
                    }
                }
            }
            val coverBox: @Composable (Modifier) -> Unit = { mod ->
                Box(mod.aspectRatio(2f / 3f).clip(RoundedCornerShape(14.dp)).background(CardC)) {
                    if (cp != null) AsyncImage(File(cp), m.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
            }
            if (wide) {
                Row(Modifier.fillMaxSize().padding(32.dp), horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                    coverBox(Modifier.width(220.dp))
                    Box(Modifier.weight(1f)) { info() }
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    coverBox(Modifier.width(160.dp))
                    info()
                }
            }
        }
        Btn("←", Modifier.align(Alignment.TopStart).padding(12.dp)) { vm.screen = Screen.Main }
    }
    if (groupDlg) GroupPickDialog(vm, m.id) { groupDlg = false }
    if (confirm) ConfirmDialog(tr("delete_confirm"), { confirm = false; vm.deleteMedia(m.id); vm.screen = Screen.Main }) { confirm = false }
}


@Composable
fun GroupPickDialog(vm: Vm, mediaId: String, onDismiss: () -> Unit) {
    var creating by remember { mutableStateOf(false) }
    if (creating) {
        TextDialog(tr("new_group"), "", { n -> val id = vm.addGroup(n); vm.toggleInGroup(id, mediaId); creating = false }) { creating = false }
        return
    }
    NyDialog(onDismiss) {
        Text(tr("add_to_group"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (vm.groups.isEmpty()) Text(tr("no_groups"), color = Muted)
            vm.groups.forEachIndexed { i, g ->
                OptionRow(g.name, mediaId in g.ids, if (i == 0) Modifier.autoFocus() else Modifier) { vm.toggleInGroup(g.id, mediaId) }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn(tr("new_group")) { creating = true }
            Btn(tr("done"), primary = true) { onDismiss() }
        }
    }
}


@Composable
fun SearchFilters(source: List<MediaEntry>, genre: String, rating: String, onGenre: (String) -> Unit, onRating: (String) -> Unit) {
    val vis = source
    val genres = vis.flatMap { it.genres }.distinct().sorted()
    val ratings = RATINGS.filter { r -> vis.any { it.rating == r } }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (genres.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
                item { Pill(tr("filter_all"), genre.isEmpty()) { onGenre("") } }
                items(genres) { g -> Pill(genreLabel(g), g == genre) { onGenre(if (g == genre) "" else g) } }
            }
        }
        if (ratings.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
                item { Pill(tr("filter_all"), rating.isEmpty()) { onRating("") } }
                items(ratings) { r -> Pill(r, r == rating) { onRating(if (r == rating) "" else r) } }
            }
        }
    }
}


/** Shows (series) or Movies tab: a grid of every title of that type, with genre/rating filters. */
@Composable
fun TypeTab(vm: Vm, type: String) {
    var fGenre by rememberSaveable(type) { mutableStateOf("") }
    var fRating by rememberSaveable(type) { mutableStateOf("") }
    val all = vm.visibleMedia.filter { it.type == type }
    val list = all.filter { (fGenre.isEmpty() || fGenre in it.genres) && (fRating.isEmpty() || it.rating == fRating) }
    if (all.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(tr("empty_title"), fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(tr("empty_sub"), color = Muted)
        }
        return
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        SearchFilters(all, fGenre, fRating, { fGenre = it }, { fRating = it })
        SearchResults(vm, list, Modifier.weight(1f))
    }
}


/** Big featured-title banner at the top of Home: backdrop, gradient, title, Play and More info. */
@Composable
fun HeroBanner(vm: Vm, m: MediaEntry, compact: Boolean) {
    val bg = m.backdrop()
    val pr = vm.progress[m.id]
    val resumable = pr != null && pr.positionMs > 3000
    val lang = vm.prefs.lang
    val meta = (listOf(m.rating, if (m.type == "movie") Strings.get(lang, "movie") else Strings.get(lang, "series")) +
        m.genres.take(3).map { genreText(lang, it) }).joinToString("  ·  ")
    Box(Modifier.fillMaxWidth().height(if (compact) 320.dp else 440.dp)) {
        if (bg != null) {
            AsyncImage(File(bg), m.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Box(Modifier.fillMaxSize().background(CardC))
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xCC000000), Color.Transparent))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x55000000), Bg))))
        Column(
            Modifier.align(Alignment.BottomStart).padding(start = 24.dp, end = 24.dp, bottom = 22.dp).widthIn(max = 560.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val logo = m.logoPath?.takeIf { File(it).exists() }
            if (logo != null) {
                AsyncImage(
                    File(logo), m.title,
                    Modifier.height(if (compact) 80.dp else 120.dp).widthIn(max = if (compact) 280.dp else 420.dp),
                    contentScale = ContentScale.Fit, alignment = Alignment.CenterStart
                )
            } else {
                Text(m.title, fontSize = if (compact) 28.sp else 40.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(meta, color = Color(0xFFDDDDE4), fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
            if (m.description.isNotBlank()) {
                Text(m.description, color = Color(0xFFDDDDE4), fontSize = 15.sp, maxLines = if (compact) 2 else 3, overflow = TextOverflow.Ellipsis)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Btn(if (resumable) tr("resume") else tr("play"), primary = true) { vm.screen = Screen.Player(m.id, false) }
                Btn(tr("more_info")) { vm.screen = Screen.Detail(m.id) }
            }
        }
    }
}

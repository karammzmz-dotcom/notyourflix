package com.notyourflix.app

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

data class Profile(
    val id: String, val name: String, val colorIndex: Int, val imagePath: String?, val pin: String?,
    val kids: Boolean = false, val maxRating: String = "NC-17", val kidsPin: String? = null
)
data class MediaGroup(val id: String, val name: String, val ids: List<String>)

val RATINGS = listOf("G", "PG", "PG-13", "R", "NC-17")
val PRESET_GENRES = listOf("Action", "Comedy", "Drama", "Horror", "Sci-Fi", "Romance", "Documentary", "Animation", "Family", "Thriller", "Fantasy", "Adventure")
fun ratingRank(r: String): Int = RATINGS.indexOf(r).let { if (it < 0) RATINGS.lastIndex else it }
/** Highest rating rank this profile may watch. Kids mode caps it at PG-13. */
fun Profile.maxRank(): Int = ratingRank(maxRating).let { if (kids) minOf(it, 2) else it }
fun Profile.allows(m: MediaEntry): Boolean = ratingRank(m.rating) <= maxRank()
data class Sub(val uri: String, val lang: String, val label: String, val mime: String)
data class Episode(
    val uri: String, val title: String = "", val subs: List<Sub> = emptyList(),
    val introStart: Long = -1, val introEnd: Long = -1
)
data class MediaEntry(
    val id: String, val title: String, val description: String, val type: String,
    val coverPath: String?, val episodes: List<Episode>, val addedAt: Long,
    val genres: List<String> = emptyList(), val rating: String = "R",
    val coverWidePath: String? = null, val logoPath: String? = null, val author: String = ""
) {
    val videos: List<String> get() = episodes.map { it.uri }
}

/** Vertical (poster) cover, falling back to the horizontal one. */
fun MediaEntry.poster(): String? = listOfNotNull(coverPath, coverWidePath).firstOrNull { File(it).exists() }
/** Horizontal (backdrop) cover, falling back to the vertical one. */
fun MediaEntry.backdrop(): String? = listOfNotNull(coverWidePath, coverPath).firstOrNull { File(it).exists() }

fun episodesToJson(l: List<Episode>): JSONArray {
    val a = JSONArray()
    l.forEach { e ->
        val subs = JSONArray()
        e.subs.forEach { s -> subs.put(JSONObject().put("u", s.uri).put("l", s.lang).put("b", s.label).put("m", s.mime)) }
        a.put(JSONObject().put("u", e.uri).put("t", e.title).put("s", subs).put("is", e.introStart).put("ie", e.introEnd))
    }
    return a
}

fun episodeFromJson(o: JSONObject): Episode {
    val sa = o.optJSONArray("s")
    val subs = if (sa == null) emptyList() else (0 until sa.length()).map { i ->
        val s = sa.getJSONObject(i)
        Sub(s.getString("u"), s.optString("l", "en"), s.optString("b"), s.optString("m", "application/x-subrip"))
    }
    return Episode(o.getString("u"), o.optString("t"), subs, o.optLong("is", -1), o.optLong("ie", -1))
}
data class Progress(val index: Int, val positionMs: Long, val durationMs: Long, val updatedAt: Long)
data class Prefs(
    val lang: String = "en", val font: String = "default",
    val sound: String = "soft", val soundUri: String? = null,
    val intro: String = "zoom", val introUri: String? = null,
    val profileIntro: String = "zoom", val profileIntroUri: String? = null,
    val fontPath: String? = null
)

const val TAB_SEARCH = 0
const val TAB_HOME = 1
const val TAB_SHOWS = 2
const val TAB_MOVIES = 3
const val TAB_SETTINGS = 4
const val TAB_COUNT = 5

data class YtState(
    val busy: Boolean = false, val stage: String = "", val detail: String = "",
    val progress: Float = -1f, val finished: Boolean = false, val error: Boolean = false
)

sealed interface Screen {
    data object Intro : Screen
    data object Profiles : Screen
    data class ProfileIntro(val id: String) : Screen
    data object Main : Screen
    data class Detail(val id: String) : Screen
    data class Episodes(val id: String) : Screen
    data class Edit(val id: String) : Screen
    data class Player(val id: String, val restart: Boolean) : Screen
    data object Upload : Screen
}

fun hashPin(s: String): String =
    MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

class Store(app: Application) {
    private val sp = app.getSharedPreferences("ny", 0)

    fun loadProfiles(): List<Profile> {
        val a = JSONArray(sp.getString("profiles", "[]"))
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Profile(o.getString("id"), o.getString("name"), o.optInt("c"),
                o.optString("img").ifEmpty { null }, o.optString("pin").ifEmpty { null },
                o.optBoolean("k"), o.optString("m", "NC-17"), o.optString("kp").ifEmpty { null })
        }
    }
    fun saveProfiles(l: List<Profile>) {
        val a = JSONArray()
        l.forEach { p ->
            a.put(JSONObject().put("id", p.id).put("name", p.name).put("c", p.colorIndex)
                .put("img", p.imagePath ?: "").put("pin", p.pin ?: "").put("k", p.kids).put("m", p.maxRating).put("kp", p.kidsPin ?: ""))
        }
        sp.edit().putString("profiles", a.toString()).apply()
    }

    fun loadMedia(): List<MediaEntry> {
        val a = JSONArray(sp.getString("media", "[]"))
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            val ea = o.optJSONArray("eps")
            val eps = if (ea != null) (0 until ea.length()).map { i -> episodeFromJson(ea.getJSONObject(i)) }
            else o.getJSONArray("videos").let { v -> (0 until v.length()).map { i -> Episode(v.getString(i)) } }
            MediaEntry(o.getString("id"), o.getString("title"), o.optString("desc"), o.optString("type", "movie"),
                o.optString("cover").ifEmpty { null }, eps, o.optLong("added"),
                o.optJSONArray("genres")?.let { g -> (0 until g.length()).map { i -> g.getString(i) } } ?: emptyList(),
                o.optString("rating", "R"), o.optString("coverw").ifEmpty { null }, o.optString("logo").ifEmpty { null }, o.optString("author"))
        }
    }
    fun saveMedia(l: List<MediaEntry>) {
        val a = JSONArray()
        l.forEach { m ->
            a.put(JSONObject().put("id", m.id).put("title", m.title).put("desc", m.description).put("type", m.type)
                .put("cover", m.coverPath ?: "").put("videos", JSONArray(m.videos)).put("eps", episodesToJson(m.episodes)).put("added", m.addedAt).put("genres", JSONArray(m.genres)).put("rating", m.rating).put("coverw", m.coverWidePath ?: "").put("logo", m.logoPath ?: "").put("author", m.author))
        }
        sp.edit().putString("media", a.toString()).apply()
    }

    fun loadSubLang(pid: String): String = sp.getString("sublang_$pid", "off") ?: "off"
    fun saveSubLang(pid: String, l: String) { sp.edit().putString("sublang_$pid", l).apply() }

    fun loadGroups(): List<MediaGroup> {
        val a = JSONArray(sp.getString("groups", "[]"))
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            val ids = o.getJSONArray("ids")
            MediaGroup(o.getString("id"), o.getString("name"), (0 until ids.length()).map { i -> ids.getString(i) })
        }
    }
    fun saveGroups(l: List<MediaGroup>) {
        val a = JSONArray()
        l.forEach { g -> a.put(JSONObject().put("id", g.id).put("name", g.name).put("ids", JSONArray(g.ids))) }
        sp.edit().putString("groups", a.toString()).apply()
    }

    fun loadProgress(pid: String): Map<String, Progress> {
        val o = JSONObject(sp.getString("progress_$pid", "{}"))
        val out = HashMap<String, Progress>()
        o.keys().forEach { k ->
            val p = o.getJSONObject(k)
            out[k] = Progress(p.optInt("i"), p.optLong("p"), p.optLong("d"), p.optLong("u"))
        }
        return out
    }
    fun saveProgress(pid: String, m: Map<String, Progress>) {
        val o = JSONObject()
        m.forEach { (k, p) -> o.put(k, JSONObject().put("i", p.index).put("p", p.positionMs).put("d", p.durationMs).put("u", p.updatedAt)) }
        sp.edit().putString("progress_$pid", o.toString()).apply()
    }
    fun removeProgress(pid: String) { sp.edit().remove("progress_$pid").apply() }

    fun loadPrefs(): Prefs {
        val o = JSONObject(sp.getString("prefs", "{}"))
        val d = Prefs()
        return Prefs(
            o.optString("lang", d.lang), o.optString("font", d.font),
            o.optString("sound", d.sound), o.optString("soundUri").ifEmpty { null },
            o.optString("intro", d.intro), o.optString("introUri").ifEmpty { null },
            o.optString("pintro", d.profileIntro), o.optString("pintroUri").ifEmpty { null },
            o.optString("fontPath").ifEmpty { null }
        )
    }
    fun savePrefs(p: Prefs) {
        val o = JSONObject().put("lang", p.lang).put("font", p.font).put("sound", p.sound).put("soundUri", p.soundUri ?: "")
            .put("intro", p.intro).put("introUri", p.introUri ?: "").put("pintro", p.profileIntro).put("pintroUri", p.profileIntroUri ?: "").put("fontPath", p.fontPath ?: "")
        sp.edit().putString("prefs", o.toString()).apply()
    }
}

class Vm(private val app: Application) : AndroidViewModel(app) {
    val store = Store(app)
    val sfx = Sfx(app)
    val profiles = mutableStateListOf<Profile>()
    val media = mutableStateListOf<MediaEntry>()
    val groups = mutableStateListOf<MediaGroup>()
    var prefs by mutableStateOf(store.loadPrefs())
    var currentId by mutableStateOf<String?>(null)
    var screen by mutableStateOf<Screen>(Screen.Intro)
    var tab by mutableIntStateOf(TAB_HOME)
    var subLang by mutableStateOf("off")
    var inPip by mutableStateOf(false)
    var yt by mutableStateOf(YtState())
    var progress by mutableStateOf<Map<String, Progress>>(emptyMap())

    val current: Profile? get() = profiles.firstOrNull { it.id == currentId }
    /** Media this profile is allowed to see (age filter / kids mode applied). */
    val visibleMedia: List<MediaEntry>
        get() { val p = current; return if (p == null) media.toList() else media.filter { p.allows(it) } }

    init {
        profiles.addAll(store.loadProfiles())
        if (profiles.isEmpty()) {
            profiles.add(Profile(UUID.randomUUID().toString(), "Profile 1", 0, null, null))
            store.saveProfiles(profiles.toList())
        }
        media.addAll(store.loadMedia())
        groups.addAll(store.loadGroups())
    }

    fun updatePrefs(f: (Prefs) -> Prefs) { prefs = f(prefs); store.savePrefs(prefs) }

    fun enterProfile(p: Profile) {
        currentId = p.id
        progress = store.loadProgress(p.id)
        subLang = store.loadSubLang(p.id)
        tab = TAB_HOME
        screen = if (prefs.profileIntro == "none") Screen.Main else Screen.ProfileIntro(p.id)
    }

    fun addProfile(name: String, color: Int, kids: Boolean = false) {
        profiles.add(Profile(UUID.randomUUID().toString(), name, color, null, null, kids))
        store.saveProfiles(profiles.toList())
    }
    fun updateProfile(p: Profile) {
        val i = profiles.indexOfFirst { it.id == p.id }
        if (i >= 0) { profiles[i] = p; store.saveProfiles(profiles.toList()) }
    }
    fun deleteProfile(id: String) {
        if (profiles.size <= 1) return
        profiles.removeAll { it.id == id }
        store.saveProfiles(profiles.toList()); store.removeProgress(id)
        currentId = null; screen = Screen.Profiles
    }
    fun chooseSubLang(l: String) { subLang = l; currentId?.let { store.saveSubLang(it, l) } }

    fun setKidsPin(pin: String?) { current?.let { updateProfile(it.copy(kidsPin = pin?.let(::hashPin))) } }
    fun checkKidsPin(p: Profile, pin: String) = p.kidsPin == hashPin(pin)

    fun setKids(on: Boolean) { current?.let { updateProfile(it.copy(kids = on)) } }
    fun setMaxRating(r: String) { current?.let { updateProfile(it.copy(maxRating = r)) } }

    fun addGroup(name: String): String {
        val g = MediaGroup(UUID.randomUUID().toString(), name, emptyList())
        groups.add(g); store.saveGroups(groups.toList())
        return g.id
    }
    fun renameGroup(id: String, name: String) {
        val i = groups.indexOfFirst { it.id == id }
        if (i >= 0) { groups[i] = groups[i].copy(name = name); store.saveGroups(groups.toList()) }
    }
    fun deleteGroup(id: String) { groups.removeAll { it.id == id }; store.saveGroups(groups.toList()) }
    fun toggleInGroup(gid: String, mid: String) {
        val i = groups.indexOfFirst { it.id == gid }
        if (i < 0) return
        val g = groups[i]
        groups[i] = g.copy(ids = if (mid in g.ids) g.ids - mid else g.ids + mid)
        store.saveGroups(groups.toList())
    }

    fun setPin(pin: String?) { current?.let { updateProfile(it.copy(pin = pin?.let(::hashPin))) } }
    fun checkPin(p: Profile, pin: String) = p.pin == hashPin(pin)

    fun setProfileColor(c: Int) { current?.let { updateProfile(it.copy(colorIndex = c, imagePath = null)) } }
    fun setProfileImage(uri: Uri) {
        viewModelScope.launch {
            val path = withContext(Dispatchers.IO) { copyImage(uri) }
            val cur = current
            if (path != null && cur != null) updateProfile(cur.copy(imagePath = path))
        }
    }

    fun saveProgress(mid: String, idx: Int, pos: Long, dur: Long) {
        val pid = currentId ?: return
        progress = progress + (mid to Progress(idx, pos, dur, System.currentTimeMillis()))
        store.saveProgress(pid, progress)
    }

    fun addMedia(
        title: String, desc: String, author: String, type: String, genres: List<String>, rating: String, groupIds: List<String>,
        cover: Uri?, coverWide: Uri?, logo: Uri?, videos: List<Uri>, subs: List<Uri>, done: () -> Unit
    ) {
        viewModelScope.launch {
            val (paths, eps) = withContext(Dispatchers.IO) {
                val eps = buildEpisodes(app, videos, subs)
                val vertical = cover?.let { copyImage(it) }
                // no horizontal cover given: use a frame from the video as the backdrop
                val wide = coverWide?.let { copyImage(it) } ?: frameCover(Uri.parse(eps.first().uri))
                Pair(Triple(vertical, wide, logo?.let { copyImage(it) }), eps)
            }
            val id = UUID.randomUUID().toString()
            media.add(0, MediaEntry(id, title, desc, type, paths.first, eps, System.currentTimeMillis(), genres, rating, paths.second, paths.third, author))
            store.saveMedia(media.toList())
            groupIds.forEach { toggleInGroup(it, id) }
            done()
        }
    }
    fun addEpisodes(id: String, uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val eps = withContext(Dispatchers.IO) { buildEpisodes(app, uris, emptyList()) }
            val i = media.indexOfFirst { it.id == id }
            if (i >= 0) {
                media[i] = media[i].copy(episodes = media[i].episodes + eps)
                store.saveMedia(media.toList())
            }
        }
    }

    fun setEpisodes(id: String, eps: List<Episode>) {
        val i = media.indexOfFirst { it.id == id }
        if (i < 0 || eps.isEmpty()) return
        media[i] = media[i].copy(episodes = eps)
        store.saveMedia(media.toList())
    }

    fun updateMedia(id: String, title: String, desc: String, author: String, type: String, genres: List<String>, rating: String) {
        val i = media.indexOfFirst { it.id == id }
        if (i < 0) return
        media[i] = media[i].copy(title = title, description = desc, author = author, type = type, genres = genres, rating = rating)
        store.saveMedia(media.toList())
    }

    fun setCover(id: String, uri: Uri, wide: Boolean = false) {
        viewModelScope.launch {
            val path = withContext(Dispatchers.IO) { copyImage(uri) } ?: return@launch
            val i = media.indexOfFirst { it.id == id }
            if (i >= 0) {
                val cur = media[i]
                val old = if (wide) cur.coverWidePath else cur.coverPath
                media[i] = if (wide) cur.copy(coverWidePath = path) else cur.copy(coverPath = path)
                store.saveMedia(media.toList())
                old?.let { runCatching { File(it).delete() } }
            }
        }
    }

    fun setCustomFont(uri: Uri, done: (Boolean) -> Unit) {
        viewModelScope.launch {
            val path = withContext(Dispatchers.IO) {
                val f = File(app.filesDir, "font_${UUID.randomUUID()}.ttf")
                try {
                    app.contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                    if (Typeface.Builder(f).build() == null) { f.delete(); null } else f.absolutePath
                } catch (e: Exception) { f.delete(); null }
            }
            if (path != null) {
                val old = prefs.fontPath
                updatePrefs { it.copy(font = "custom", fontPath = path) }
                old?.let { runCatching { File(it).delete() } }
            }
            done(path != null)
        }
    }

    fun removeCover(id: String, wide: Boolean) {
        val i = media.indexOfFirst { it.id == id }
        if (i < 0) return
        val cur = media[i]
        val old = if (wide) cur.coverWidePath else cur.coverPath
        media[i] = if (wide) cur.copy(coverWidePath = null) else cur.copy(coverPath = null)
        store.saveMedia(media.toList())
        old?.let { runCatching { File(it).delete() } }
    }

    /** Title logo image, shown only on the Home banner. Pass null to remove it. */
    fun setLogo(id: String, uri: Uri?) {
        viewModelScope.launch {
            val path = if (uri == null) null else (withContext(Dispatchers.IO) { copyImage(uri) } ?: return@launch)
            val i = media.indexOfFirst { it.id == id }
            if (i >= 0) {
                val old = media[i].logoPath
                media[i] = media[i].copy(logoPath = path)
                store.saveMedia(media.toList())
                old?.let { runCatching { File(it).delete() } }
            }
        }
    }

    fun importYoutube(link: String) {
        if (yt.busy) return
        val vid = YouTube.videoId(link)
        if (vid == null) { yt = YtState(stage = "yt_bad_link", error = true); return }
        yt = YtState(busy = true, stage = "yt_info")
        viewModelScope.launch {
            val files = ArrayList<File>()
            try {
                val entry = withContext(Dispatchers.IO) {
                    val info = YouTube.fetchInfo(vid)

                    var thumb: String? = null
                    val tu = info.thumbUrl
                    if (tu != null) {
                        yt = YtState(busy = true, stage = "yt_thumb")
                        val f = File(app.filesDir, "img_${UUID.randomUUID()}.jpg")
                        files.add(f)
                        runCatching { YouTube.downloadFile(tu, f); thumb = f.absolutePath }
                    }

                    yt = YtState(busy = true, stage = "yt_subs")
                    val subs = ArrayList<Sub>()
                    for (c in YouTube.pickCaptions(info.captions)) {
                        runCatching {
                            val text = YouTube.getText(YouTube.vttUrl(c.url))
                            if (text.isNotBlank()) {
                                val f = File(app.filesDir, "yt_${vid}_${c.lang}_${subs.size}.vtt")
                                f.writeText(text)
                                files.add(f)
                                val label = if (LANGS.any { it.first == c.lang }) langLabel(c.lang) else c.label.ifBlank { c.lang }
                                subs.add(Sub(Uri.fromFile(f).toString(), c.lang, label, "text/vtt"))
                            }
                        }
                    }

                    val vf = File(app.filesDir, "yt_$vid.mp4")
                    files.add(vf)
                    yt = YtState(busy = true, stage = "yt_video", progress = 0f)
                    var last = -1
                    YouTube.downloadFile(info.videoUrl, vf, info.videoSize, info.ua) { p ->
                        val pct = (p * 100).toInt()
                        if (pct != last) { last = pct; yt = YtState(busy = true, stage = "yt_video", detail = "$pct%", progress = p) }
                    }

                    MediaEntry(
                        UUID.randomUUID().toString(), info.title, info.description.take(2000), "movie", null,
                        listOf(Episode(Uri.fromFile(vf).toString(), "", subs)), System.currentTimeMillis(),
                        emptyList(), "R", thumb, null, info.author
                    )
                }
                media.add(0, entry)
                store.saveMedia(media.toList())
                yt = YtState(stage = "yt_done", finished = true)
            } catch (e: Exception) {
                files.forEach { runCatching { it.delete() } }
                yt = YtState(stage = "yt_fail", detail = e.message ?: "", error = true)
            }
        }
    }

    fun deleteMedia(id: String) {
        val m = media.firstOrNull { it.id == id } ?: return
        media.remove(m)
        m.coverPath?.let { runCatching { File(it).delete() } }
        m.coverWidePath?.let { runCatching { File(it).delete() } }
        m.logoPath?.let { runCatching { File(it).delete() } }
        // files this app downloaded itself (YouTube imports) live in app storage; remove them too
        m.episodes.forEach { e ->
            if (e.uri.startsWith("file:")) runCatching { File(Uri.parse(e.uri).path!!).delete() }
            e.subs.filter { it.uri.startsWith("file:") }.forEach { s -> runCatching { File(Uri.parse(s.uri).path!!).delete() } }
        }
        store.saveMedia(media.toList())
        groups.indices.toList().forEach { i -> if (id in groups[i].ids) groups[i] = groups[i].copy(ids = groups[i].ids - id) }
        store.saveGroups(groups.toList())
    }

    private fun copyImage(uri: Uri): String? = runCatching {
        val f = File(app.filesDir, "img_${UUID.randomUUID()}.jpg")
        app.contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
        f.absolutePath
    }.getOrNull()

    private fun frameCover(uri: Uri): String? = runCatching {
        val r = MediaMetadataRetriever()
        r.setDataSource(app, uri)
        val bmp = r.getFrameAtTime(2_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        r.release()
        bmp?.let {
            val f = File(app.filesDir, "cov_${UUID.randomUUID()}.jpg")
            f.outputStream().use { o -> it.compress(Bitmap.CompressFormat.JPEG, 85, o) }
            f.absolutePath
        }
    }.getOrNull()
}

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
    val kids: Boolean = false, val maxRating: String = "NC-17"
)
data class MediaGroup(val id: String, val name: String, val ids: List<String>)

val RATINGS = listOf("G", "PG", "PG-13", "R", "NC-17")
val PRESET_GENRES = listOf("Action", "Comedy", "Drama", "Horror", "Sci-Fi", "Romance", "Documentary", "Animation", "Family", "Thriller", "Fantasy", "Adventure")
fun ratingRank(r: String): Int = RATINGS.indexOf(r).let { if (it < 0) RATINGS.lastIndex else it }
/** Highest rating rank this profile may watch. Kids mode caps it at PG-13. */
fun Profile.maxRank(): Int = ratingRank(maxRating).let { if (kids) minOf(it, 2) else it }
fun Profile.allows(m: MediaEntry): Boolean = ratingRank(m.rating) <= maxRank()
data class MediaEntry(
    val id: String, val title: String, val description: String, val type: String,
    val coverPath: String?, val videos: List<String>, val addedAt: Long,
    val genres: List<String> = emptyList(), val rating: String = "R"
)
data class Progress(val index: Int, val positionMs: Long, val durationMs: Long, val updatedAt: Long)
data class Prefs(
    val lang: String = "en", val font: String = "default",
    val sound: String = "soft", val soundUri: String? = null,
    val intro: String = "zoom", val introUri: String? = null,
    val profileIntro: String = "zoom", val profileIntroUri: String? = null,
    val fontPath: String? = null
)

sealed interface Screen {
    data object Intro : Screen
    data object Profiles : Screen
    data class ProfileIntro(val id: String) : Screen
    data object Main : Screen
    data class Detail(val id: String) : Screen
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
                o.optBoolean("k"), o.optString("m", "NC-17"))
        }
    }
    fun saveProfiles(l: List<Profile>) {
        val a = JSONArray()
        l.forEach { p ->
            a.put(JSONObject().put("id", p.id).put("name", p.name).put("c", p.colorIndex)
                .put("img", p.imagePath ?: "").put("pin", p.pin ?: "").put("k", p.kids).put("m", p.maxRating))
        }
        sp.edit().putString("profiles", a.toString()).apply()
    }

    fun loadMedia(): List<MediaEntry> {
        val a = JSONArray(sp.getString("media", "[]"))
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            val v = o.getJSONArray("videos")
            MediaEntry(o.getString("id"), o.getString("title"), o.optString("desc"), o.optString("type", "movie"),
                o.optString("cover").ifEmpty { null }, (0 until v.length()).map { i -> v.getString(i) }, o.optLong("added"),
                o.optJSONArray("genres")?.let { g -> (0 until g.length()).map { i -> g.getString(i) } } ?: emptyList(),
                o.optString("rating", "R"))
        }
    }
    fun saveMedia(l: List<MediaEntry>) {
        val a = JSONArray()
        l.forEach { m ->
            a.put(JSONObject().put("id", m.id).put("title", m.title).put("desc", m.description).put("type", m.type)
                .put("cover", m.coverPath ?: "").put("videos", JSONArray(m.videos)).put("added", m.addedAt).put("genres", JSONArray(m.genres)).put("rating", m.rating))
        }
        sp.edit().putString("media", a.toString()).apply()
    }

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
    var tab by mutableIntStateOf(0)
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
        tab = 0
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
        title: String, desc: String, type: String, genres: List<String>, rating: String, groupIds: List<String>,
        cover: Uri?, videos: List<Uri>, done: () -> Unit
    ) {
        viewModelScope.launch {
            val path = withContext(Dispatchers.IO) { cover?.let { copyImage(it) } ?: frameCover(videos.first()) }
            val id = UUID.randomUUID().toString()
            media.add(0, MediaEntry(id, title, desc, type, path, videos.map { it.toString() }, System.currentTimeMillis(), genres, rating))
            store.saveMedia(media.toList())
            groupIds.forEach { toggleInGroup(it, id) }
            done()
        }
    }
    fun addEpisodes(id: String, uris: List<Uri>) {
        val i = media.indexOfFirst { it.id == id }
        if (i < 0 || uris.isEmpty()) return
        val m = media[i]
        media[i] = m.copy(videos = m.videos + uris.map { it.toString() })
        store.saveMedia(media.toList())
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

    fun deleteMedia(id: String) {
        val m = media.firstOrNull { it.id == id } ?: return
        media.remove(m)
        m.coverPath?.let { runCatching { File(it).delete() } }
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

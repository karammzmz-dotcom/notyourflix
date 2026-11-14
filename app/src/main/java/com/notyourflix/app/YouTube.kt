package com.notyourflix.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class YtException(msg: String) : Exception(msg)
class YtPlaylist(val title: String, val description: String, val videos: List<Pair<String, String>>)
class YtCaption(val url: String, val lang: String, val label: String, val auto: Boolean)
class YtInfo(
    val id: String, val title: String, val author: String, val description: String,
    val thumbUrl: String?, val videoUrl: String, val videoSize: Long, val ua: String, val captions: List<YtCaption>
)

private class YtClient(
    val name: String, val id: String, val version: String, val ua: String,
    val extra: Map<String, Any>, val auth: Boolean = false
)

/**
 * Minimal YouTube importer. It talks to YouTube's unofficial mobile "player" endpoint, which can change or
 * be blocked at any time. It tries several client identities in turn and reports the real error if all fail.
 */
object YouTube {
    private const val UA = "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    private val CLIENTS = listOf(
        YtClient(
            "ANDROID_VR", "28", "1.60.19",
            "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            mapOf("androidSdkVersion" to 32, "deviceMake" to "Oculus", "deviceModel" to "Quest 3", "osName" to "Android", "osVersion" to "12L")
        ),
        YtClient(
            "ANDROID", "3", "20.10.38",
            "com.google.android.youtube/20.10.38 (Linux; U; Android 11) gzip",
            mapOf("androidSdkVersion" to 30, "osName" to "Android", "osVersion" to "11")
        ),
        YtClient(
            "ANDROID", "3", "19.09.37",
            "com.google.android.youtube/19.09.37 (Linux; U; Android 11) gzip",
            mapOf("androidSdkVersion" to 30)
        )
    )

    /** Cookies from the in-app YouTube sign-in (null = signed out). Set by the app. */
    @Volatile
    var cookies: String? = null

    // Clients that accept a signed-in session. Tried first when the user is signed in.
    private val AUTH_CLIENTS = listOf(
        YtClient(
            "TVHTML5", "7", "7.20250310.16.00", "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version",
            mapOf("clientScreen" to "WATCH"), true
        ),
        YtClient("MWEB", "2", "2.20250311.03.00", UA, emptyMap(), true),
        YtClient("WEB", "1", "2.20250310.01.00", UA, emptyMap(), true)
    )

    private fun authHeader(ck: String): String? {
        val sid = Regex("(?:^|;\\s*)SAPISID=([^;]+)").find(ck)?.groupValues?.get(1)
            ?: Regex("(?:^|;\\s*)__Secure-3PAPISID=([^;]+)").find(ck)?.groupValues?.get(1)
            ?: return null
        val ts = System.currentTimeMillis() / 1000
        val hash = MessageDigest.getInstance("SHA-1").digest("$ts $sid https://www.youtube.com".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "SAPISIDHASH ${ts}_$hash"
    }

    fun videoId(link: String): String? {
        val s = link.trim()
        val patterns = listOf(
            Regex("""(?:youtu\.be/|youtube\.com/(?:shorts/|embed/|live/|v/))([A-Za-z0-9_-]{11})"""),
            Regex("""[?&]v=([A-Za-z0-9_-]{11})""")
        )
        for (p in patterns) p.find(s)?.let { return it.groupValues[1] }
        return if (Regex("[A-Za-z0-9_-]{11}").matches(s)) s else null
    }

    private fun errorText(c: HttpURLConnection, code: Int): String {
        val body = runCatching { c.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
        val msg = runCatching { JSONObject(body).optJSONObject("error")?.optString("message") }.getOrNull()
        return "HTTP $code" + (msg?.takeIf { it.isNotBlank() }?.let { " - " + it.take(160) } ?: "")
    }

    private fun open(url: String, range: String? = null, ua: String = UA, extra: Map<String, String> = emptyMap()): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.setRequestProperty("User-Agent", ua)
        if (range != null) c.setRequestProperty("Range", range)
        extra.forEach { (k, v) -> c.setRequestProperty(k, v) }
        val code = c.responseCode
        if (code !in 200..299) throw YtException(errorText(c, code))
        return c
    }

    fun getText(url: String): String = open(url).inputStream.bufferedReader().use { it.readText() }

    private fun post(url: String, body: String, client: YtClient): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("User-Agent", client.ua)
        c.setRequestProperty("X-YouTube-Client-Name", client.id)
        c.setRequestProperty("X-YouTube-Client-Version", client.version)
        c.setRequestProperty("Origin", "https://www.youtube.com")
        val ck = cookies
        if (client.auth && ck != null) {
            c.setRequestProperty("Cookie", ck)
            authHeader(ck)?.let { c.setRequestProperty("Authorization", it) }
            c.setRequestProperty("X-Goog-AuthUser", "0")
            c.setRequestProperty("X-Origin", "https://www.youtube.com")
        }
        c.outputStream.use { it.write(body.toByteArray()) }
        val code = c.responseCode
        if (code !in 200..299) throw IOException(errorText(c, code))
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    fun fetchInfo(id: String): YtInfo {
        var playability: Exception? = null
        var last: Exception? = null
        val list = if (cookies != null) AUTH_CLIENTS + CLIENTS else CLIENTS
        for (client in list) {
            try {
                return fetchWith(client, id)
            } catch (e: YtException) {
                if (e.message?.startsWith("HTTP") != true && e.message != "no direct video link available" && playability == null) playability = e
                last = e
            } catch (e: Exception) {
                last = e
            }
        }
        throw YtException((playability ?: last)?.message ?: "failed")
    }

    private fun fetchWith(client: YtClient, id: String): YtInfo {
        val cj = JSONObject().put("clientName", client.name).put("clientVersion", client.version)
            .put("hl", "en").put("gl", "US").put("timeZone", "UTC").put("utcOffsetMinutes", 0)
        client.extra.forEach { (k, v) -> cj.put(k, v) }
        val body = JSONObject().put("videoId", id).put("contentCheckOk", true).put("racyCheckOk", true)
            .put("context", JSONObject().put("client", cj)).toString()
        val r = JSONObject(post("https://www.youtube.com/youtubei/v1/player?prettyPrint=false", body, client))

        val ps = r.optJSONObject("playabilityStatus")
        val status = ps?.optString("status") ?: ""
        if (status != "OK") throw YtException(ps?.optString("reason")?.ifBlank { null } ?: status.ifBlank { "unavailable" })

        val vd = r.optJSONObject("videoDetails") ?: throw YtException("no video details")

        // best progressive MP4 (video + audio in one file)
        val formats = r.optJSONObject("streamingData")?.optJSONArray("formats")
        var best: JSONObject? = null
        if (formats != null) {
            for (i in 0 until formats.length()) {
                val f = formats.getJSONObject(i)
                if (!f.has("url")) continue
                if (!f.optString("mimeType").startsWith("video/mp4")) continue
                val cur = best
                if (cur == null || f.optInt("height") > cur.optInt("height")) best = f
            }
        }
        val chosen = best ?: throw YtException("no direct video link available")

        val thumbs = vd.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
        val thumb = if (thumbs != null && thumbs.length() > 0) thumbs.getJSONObject(thumbs.length() - 1).optString("url").ifBlank { null } else null

        val caps = ArrayList<YtCaption>()
        val tracks = r.optJSONObject("captions")?.optJSONObject("playerCaptionsTracklistRenderer")?.optJSONArray("captionTracks")
        if (tracks != null) {
            for (i in 0 until tracks.length()) {
                val t = tracks.getJSONObject(i)
                val n = t.optJSONObject("name")
                val name = n?.optString("simpleText")?.ifBlank { null }
                    ?: n?.optJSONArray("runs")?.optJSONObject(0)?.optString("text") ?: ""
                caps.add(YtCaption(t.optString("baseUrl"), t.optString("languageCode").substringBefore('-'), name, t.optString("kind") == "asr"))
            }
        }

        return YtInfo(
            id, vd.optString("title"), vd.optString("author"), vd.optString("shortDescription"),
            thumb, chosen.getString("url"), chosen.optString("contentLength").toLongOrNull() ?: 0L, client.ua, caps
        )
    }

    fun playlistId(link: String): String? = Regex("[?&]list=([A-Za-z0-9_-]+)").find(link)?.groupValues?.get(1)

    private fun textOf(o: JSONObject?): String =
        o?.optString("simpleText")?.ifBlank { null } ?: o?.optJSONArray("runs")?.optJSONObject(0)?.optString("text") ?: ""

    private fun collect(n: Any?, key: String, out: MutableList<JSONObject>) {
        when (n) {
            is JSONObject -> {
                val keys = n.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = n.opt(k)
                    if (k == key && v is JSONObject) out.add(v) else collect(v, key, out)
                }
            }
            is JSONArray -> for (i in 0 until n.length()) collect(n.opt(i), key, out)
        }
    }

    private val WEB = YtClient("WEB", "1", "2.20250310.01.00", UA, emptyMap())

    private fun browse(body: JSONObject): JSONObject {
        val client = JSONObject().put("clientName", WEB.name).put("clientVersion", WEB.version).put("hl", "en").put("gl", "US")
        body.put("context", JSONObject().put("client", client))
        return JSONObject(post("https://www.youtube.com/youtubei/v1/browse?prettyPrint=false", body.toString(), WEB))
    }

    /** Reads every video of a public playlist, following YouTube's "continuation" pages until the end. */
    fun fetchPlaylist(listId: String): YtPlaylist {
        var data = browse(JSONObject().put("browseId", "VL$listId"))
        val meta = data.optJSONObject("metadata")?.optJSONObject("playlistMetadataRenderer")
        val vids = ArrayList<Pair<String, String>>()
        val seen = HashSet<String>()
        var pages = 0
        while (true) {
            val found = ArrayList<JSONObject>()
            collect(data, "playlistVideoRenderer", found)
            for (r in found) {
                val id = r.optString("videoId")
                if (id.length == 11 && seen.add(id)) vids.add(Pair(id, textOf(r.optJSONObject("title"))))
            }
            val conts = ArrayList<JSONObject>()
            collect(data, "continuationItemRenderer", conts)
            val token = conts.firstNotNullOfOrNull {
                it.optJSONObject("continuationEndpoint")?.optJSONObject("continuationCommand")?.optString("token")?.ifBlank { null }
            }
            pages++
            if (token == null || pages > 2000) break
            data = browse(JSONObject().put("continuation", token))
        }
        if (vids.isEmpty()) throw YtException("no videos found in this playlist (it may be private)")
        return YtPlaylist(meta?.optString("title").orEmpty(), meta?.optString("description").orEmpty(), vids)
    }

    /** One track per supported language (manual captions preferred), at most four. */
    fun pickCaptions(all: List<YtCaption>): List<YtCaption> {
        val out = ArrayList<YtCaption>()
        for ((code, _) in LANGS) {
            val l = all.filter { it.lang == code && it.url.isNotBlank() }
            val c = l.firstOrNull { !it.auto } ?: l.firstOrNull()
            if (c != null) out.add(c)
        }
        if (out.isEmpty()) all.firstOrNull { it.url.isNotBlank() }?.let { out.add(it) }
        return out.take(4)
    }

    fun vttUrl(base: String): String =
        if ("fmt=" in base) base.replace(Regex("fmt=[^&]*"), "fmt=vtt") else "$base&fmt=vtt"

    fun downloadFile(url: String, out: File, expected: Long = 0, ua: String = UA, onProgress: (Float) -> Unit = {}) {
        out.outputStream().use { downloadTo(url, it, expected, ua, onProgress) }
    }

    fun downloadTo(url: String, os: OutputStream, expected: Long = 0, ua: String = UA, onProgress: (Float) -> Unit = {}) {
        val buf = ByteArray(64 * 1024)
        var done = 0L
        fun pump(c: HttpURLConnection) {
            c.inputStream.use { ins ->
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    os.write(buf, 0, n)
                    done += n
                    if (expected > 0) onProgress(done.toFloat() / expected)
                }
            }
        }
        if (expected > 0) {
            val chunk = 9_437_184L
            var start = 0L
            while (start < expected) {
                val end = minOf(start + chunk, expected) - 1
                val c = open(url, "bytes=$start-$end", ua)
                pump(c)
                if (c.responseCode == 200) break // server ignored the range and sent everything
                start = end + 1
            }
        } else {
            pump(open(url, null, ua))
        }
    }
}

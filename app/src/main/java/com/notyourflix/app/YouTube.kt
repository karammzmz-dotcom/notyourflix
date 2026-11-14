package com.notyourflix.app

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class YtException(msg: String) : Exception(msg)
class YtCaption(val url: String, val lang: String, val label: String, val auto: Boolean)
class YtInfo(
    val id: String, val title: String, val author: String, val description: String,
    val thumbUrl: String?, val videoUrl: String, val videoSize: Long, val captions: List<YtCaption>
)

/**
 * Minimal YouTube importer. It talks to YouTube's unofficial mobile "player" endpoint, which can change or
 * be blocked at any time, so every step reports a clear error instead of crashing.
 */
object YouTube {
    private const val UA = "com.google.android.youtube/19.09.37 (Linux; U; Android 11) gzip"
    private const val CLIENT_VERSION = "19.09.37"

    fun videoId(link: String): String? {
        val s = link.trim()
        val patterns = listOf(
            Regex("""(?:youtu\.be/|youtube\.com/(?:shorts/|embed/|live/|v/))([A-Za-z0-9_-]{11})"""),
            Regex("""[?&]v=([A-Za-z0-9_-]{11})""")
        )
        for (p in patterns) p.find(s)?.let { return it.groupValues[1] }
        return if (Regex("[A-Za-z0-9_-]{11}").matches(s)) s else null
    }

    private fun open(url: String, range: String? = null): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.setRequestProperty("User-Agent", UA)
        if (range != null) c.setRequestProperty("Range", range)
        val code = c.responseCode
        if (code !in 200..299) throw YtException("HTTP $code")
        return c
    }

    fun getText(url: String): String = open(url).inputStream.bufferedReader().use { it.readText() }

    private fun post(url: String, body: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("X-YouTube-Client-Name", "3")
        c.setRequestProperty("X-YouTube-Client-Version", CLIENT_VERSION)
        c.outputStream.use { it.write(body.toByteArray()) }
        val code = c.responseCode
        if (code !in 200..299) throw IOException("HTTP $code")
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    fun fetchInfo(id: String): YtInfo {
        val client = JSONObject().put("clientName", "ANDROID").put("clientVersion", CLIENT_VERSION)
            .put("androidSdkVersion", 30).put("hl", "en").put("gl", "US")
        val body = JSONObject().put("videoId", id).put("contentCheckOk", true).put("racyCheckOk", true)
            .put("context", JSONObject().put("client", client)).toString()
        val r = JSONObject(post("https://www.youtube.com/youtubei/v1/player?prettyPrint=false", body))

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
            thumb, chosen.getString("url"), chosen.optString("contentLength").toLongOrNull() ?: 0L, caps
        )
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

    fun downloadFile(url: String, out: File, expected: Long = 0, onProgress: (Float) -> Unit = {}) {
        out.outputStream().use { os ->
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
                    val c = open(url, "bytes=$start-$end")
                    pump(c)
                    if (c.responseCode == 200) break // server ignored the range and sent everything
                    start = end + 1
                }
            } else {
                pump(open(url))
            }
        }
    }
}

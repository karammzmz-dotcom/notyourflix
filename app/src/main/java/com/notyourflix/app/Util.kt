package com.notyourflix.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

fun displayName(ctx: Context, uri: Uri): String = runCatching {
    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
}.getOrNull() ?: uri.lastPathSegment ?: "video"

fun baseName(n: String): String = n.substringBeforeLast('.', n)

/** "Ep2" sorts before "Ep10". */
fun naturalCompare(a: String, b: String): Int {
    val rx = Regex("\\d+|\\D+")
    val ra = rx.findAll(a.lowercase()).map { it.value }.toList()
    val rb = rx.findAll(b.lowercase()).map { it.value }.toList()
    for (i in 0 until minOf(ra.size, rb.size)) {
        val x = ra[i]
        val y = rb[i]
        val c = if (x[0].isDigit() && y[0].isDigit()) x.toBigInteger().compareTo(y.toBigInteger()) else x.compareTo(y)
        if (c != 0) return c
    }
    return ra.size - rb.size
}

fun fmtTime(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** Accepts "83", "1:23" or "1:02:03". Returns null if it isn't a time. */
fun parseTime(s: String): Long? {
    val parts = s.trim().split(":").map { it.trim().toLongOrNull() ?: return null }
    if (parts.isEmpty() || parts.size > 3) return null
    var sec = 0L
    parts.forEach { sec = sec * 60 + it }
    return sec * 1000
}

val LANGS = listOf(
    "en" to "English", "ar" to "العربية", "fr" to "Français", "es" to "Español", "de" to "Deutsch", "tr" to "Türkçe"
)
private val LANG_TAGS = mapOf(
    "en" to "en", "eng" to "en", "english" to "en",
    "ar" to "ar", "ara" to "ar", "arabic" to "ar",
    "fr" to "fr", "fre" to "fr", "fra" to "fr", "french" to "fr",
    "es" to "es", "spa" to "es", "spanish" to "es",
    "de" to "de", "ger" to "de", "deu" to "de", "german" to "de",
    "tr" to "tr", "tur" to "tr", "turkish" to "tr"
)

fun langLabel(code: String): String = LANGS.firstOrNull { it.first == code }?.second ?: code
fun langTagOf(base: String): String? = if ('.' in base) LANG_TAGS[base.substringAfterLast('.').lowercase()] else null
fun stripLangTag(base: String): String = if (langTagOf(base) != null) base.substringBeforeLast('.') else base

/** Builds a subtitle entry from a picked file. Language comes from a tag like "movie.ar.srt", else English. */
fun makeSub(uri: Uri, name: String): Sub {
    val lang = langTagOf(baseName(name)) ?: "en"
    val mime = if (name.lowercase().endsWith(".vtt")) "text/vtt" else "application/x-subrip"
    return Sub(uri.toString(), lang, langLabel(lang), mime)
}

/** Sorts files naturally, titles episodes from file names and matches subtitle files to videos by name. */
fun buildEpisodes(ctx: Context, videos: List<Uri>, subs: List<Uri>): List<Episode> {
    val vs = videos.map { it to displayName(ctx, it) }.sortedWith { a, b -> naturalCompare(a.second, b.second) }
    val ss = subs.map { Triple(it, displayName(ctx, it), 0) }
    return vs.map { (u, n) ->
        val base = baseName(n)
        val mine = ss.filter { (_, sn, _) -> vs.size == 1 || stripLangTag(baseName(sn)).equals(base, ignoreCase = true) }
        Episode(u.toString(), if (vs.size > 1) base else "", mine.map { (su, sn, _) -> makeSub(su, sn) })
    }
}

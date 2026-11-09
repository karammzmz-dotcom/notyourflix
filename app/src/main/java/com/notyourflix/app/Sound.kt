package com.notyourflix.app

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

fun persist(ctx: Context, uri: Uri) {
    runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
}

/** Sound effects. Presets are synthesized (no asset files needed); "custom" plays a file the user picked. */
class Sfx(private val ctx: Context) {
    private val cache = HashMap<String, ShortArray>()
    private val handler = Handler(Looper.getMainLooper())
    private val rate = 22050

    fun click(p: Prefs) = play(p, false)
    fun intro(p: Prefs) = play(p, true)

    private fun play(p: Prefs, intro: Boolean) {
        when (p.sound) {
            "off" -> return
            "custom" -> playFile(p.soundUri, if (intro) 4000L else 1200L)
            else -> {
                val data = cache.getOrPut("${p.sound}_$intro") { gen(p.sound, intro) }
                playPcm(data)
            }
        }
    }

    private fun gen(kind: String, intro: Boolean): ShortArray = when (kind) {
        "retro" -> if (intro) synth(listOf(Triple(262.0, 0.0, 0.25), Triple(330.0, 0.12, 0.25), Triple(392.0, 0.24, 0.25), Triple(523.0, 0.36, 0.4)), true, 5.0)
        else synth(listOf(Triple(660.0, 0.0, 0.06)), true, 12.0)
        "chime" -> if (intro) synth(listOf(Triple(523.0, 0.0, 0.9), Triple(659.0, 0.15, 0.9), Triple(784.0, 0.3, 1.0)), false, 3.5)
        else synth(listOf(Triple(1318.0, 0.0, 0.2)), false, 14.0)
        else -> if (intro) synth(listOf(Triple(98.0, 0.0, 1.4), Triple(147.0, 0.0, 1.4), Triple(196.0, 0.0, 1.4)), false, 2.2)
        else synth(listOf(Triple(880.0, 0.0, 0.07)), false, 30.0)
    }

    private fun synth(notes: List<Triple<Double, Double, Double>>, square: Boolean, decay: Double): ShortArray {
        val total = notes.maxOf { it.second + it.third }
        val out = DoubleArray((total * rate).toInt() + 1)
        for ((f, s, l) in notes) {
            val start = (s * rate).toInt()
            val n = (l * rate).toInt()
            for (i in 0 until n) {
                val t = i.toDouble() / rate
                val ph = 2 * PI * f * t
                val v = if (square) (if (sin(ph) >= 0) 0.5 else -0.5) else sin(ph)
                val env = min(1.0, t * 200) * exp(-decay * t)
                if (start + i < out.size) out[start + i] += v * env * 0.35
            }
        }
        return ShortArray(out.size) { (out[it].coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort() }
    }

    private fun playPcm(data: ShortArray) {
        runCatching {
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(data.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC).build()
            track.write(data, 0, data.size)
            track.play()
            handler.postDelayed({ runCatching { track.release() } }, data.size * 1000L / rate + 300)
        }
    }

    private fun playFile(uri: String?, maxMs: Long) {
        if (uri == null) return
        runCatching {
            val mp = MediaPlayer()
            mp.setDataSource(ctx, Uri.parse(uri))
            mp.setOnPreparedListener { it.start() }
            mp.setOnCompletionListener { runCatching { it.release() } }
            mp.setOnErrorListener { m, _, _ -> runCatching { m.release() }; true }
            mp.prepareAsync()
            handler.postDelayed({ runCatching { mp.stop(); mp.release() } }, maxMs)
        }
    }
}

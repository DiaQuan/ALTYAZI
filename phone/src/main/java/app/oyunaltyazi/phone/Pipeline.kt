package app.oyunaltyazi.phone

import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.common.model.DownloadConditions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

/** OCR çıktısını sabitler: aynı metin art arda iki okumada görünce "yeni satır" sayar. */
class TextStabilizer {
    sealed class Event {
        data class New(val text: String) : Event()
        object Clear : Event()
    }

    private var candidate: String? = null
    private var candidateCount = 0
    private var emitted: String? = null
    private var emptyCount = 0

    fun feed(raw: String): Event? {
        val t = raw.replace(Regex("\\s+"), " ").trim()
        if (t.length < 3) {
            emptyCount++
            if (emptyCount >= 3 && emitted != null) {
                emitted = null
                candidate = null
                candidateCount = 0
                return Event.Clear
            }
            return null
        }
        emptyCount = 0
        val c = candidate
        if (c != null && similar(c, t)) {
            candidateCount++
        } else {
            candidate = t
            candidateCount = 1
        }
        val cand = candidate ?: return null
        val e = emitted
        if (candidateCount >= 2 && (e == null || !similar(e, cand))) {
            emitted = cand
            return Event.New(cand)
        }
        return null
    }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }.take(400)

    private fun similar(a: String, b: String): Boolean {
        val x = norm(a)
        val y = norm(b)
        if (x == y) return true
        val m = maxOf(x.length, y.length)
        if (m == 0) return true
        if (abs(x.length - y.length) > m * 0.2) return false
        return levenshtein(x, y).toDouble() / m < 0.15
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val tmp = prev; prev = cur; cur = tmp
        }
        return prev[b.length]
    }
}

/** ML Kit cihaz içi çeviri (kaynak dil -> Türkçe) + çeviri önbelleği. */
class TranslationEngine(private val cache: TranslationCache, private val sourceLang: String) {
    private val translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(sourceLang)
            .setTargetLanguage(TranslateLanguage.TURKISH)
            .build()
    )
    private var ready = false

    suspend fun translate(text: String): String {
        val key = sha1("$sourceLang|${text.lowercase()}")
        cache.get(key)?.let { return it }
        if (!ready) {
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            ready = true
        }
        val out = translator.translate(text).await()
        cache.put(key, out)
        return out
    }

    fun close() = translator.close()
}

/** PC'de çalışan Yazgı TTS sunucusundan WAV alır; sonuç diskte önbelleğe alınır. */
class TtsClient(private val baseUrl: String, private val voice: String, private val cache: AudioCache) {
    suspend fun synth(text: String): ByteArray = withContext(Dispatchers.IO) {
        val key = sha1("$voice|$text")
        cache.get(key)?.let { return@withContext it }
        val conn = (URL(baseUrl.trimEnd('/') + "/tts").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            conn.outputStream.use {
                it.write(JSONObject().put("text", text).put("voice", voice).toString().toByteArray())
            }
            if (conn.responseCode != 200) throw IOException("TTS HTTP ${conn.responseCode}")
            val bytes = conn.inputStream.use { it.readBytes() }
            cache.put(key, bytes)
            bytes
        } finally {
            conn.disconnect()
        }
    }
}

package app.oyunaltyazi.phone

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

fun sha1(s: String): String =
    MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

/** Çeviri önbelleği: aynı oyun satırı tekrar gelirse çeviri API/modeli çağrılmaz. */
class TranslationCache(private val file: File, private val max: Int = 5000) {
    private val map = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > max
    }
    private var dirty = 0

    init {
        try {
            if (file.exists()) {
                val json = JSONObject(file.readText())
                for (k in json.keys()) map[k] = json.getString(k)
            }
        } catch (e: Exception) {
            map.clear()
        }
    }

    @Synchronized fun get(key: String): String? = map[key]

    @Synchronized fun put(key: String, value: String) {
        map[key] = value
        if (++dirty >= 20) save()
    }

    @Synchronized fun save() {
        dirty = 0
        try {
            file.writeText(JSONObject(map as Map<*, *>).toString())
        } catch (e: Exception) {
        }
    }
}

/** Dublaj önbelleği: aynı satır aynı sesle bir kez üretilir, sonra diskten gelir. */
class AudioCache(private val dir: File, private val maxBytes: Long = 400L * 1024 * 1024) {
    private var puts = 0

    init {
        dir.mkdirs()
    }

    @Synchronized fun get(key: String): ByteArray? {
        val f = File(dir, "$key.wav")
        if (!f.exists()) return null
        f.setLastModified(System.currentTimeMillis())
        return try {
            f.readBytes()
        } catch (e: Exception) {
            null
        }
    }

    @Synchronized fun put(key: String, bytes: ByteArray) {
        try {
            File(dir, "$key.wav").writeBytes(bytes)
        } catch (e: Exception) {
            return
        }
        if (++puts % 10 == 0) trim()
    }

    private fun trim() {
        val files = dir.listFiles() ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= maxBytes * 8 / 10) break
            total -= f.length()
            f.delete()
        }
    }
}

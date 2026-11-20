package app.oyunaltyazi.phone

import android.content.Context

data class Settings(
    val tvIp: String = "",
    val tvCode: String = "",
    val serverUrl: String = "http://192.168.1.10:8000",
    val voice: String = "male-01",
    val sourceLang: String = "en",
    val dub: Boolean = true,
    val cropTop: Int = 70,
    val cropBottom: Int = 100,
) {
    fun save(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("tvIp", tvIp)
            .putString("tvCode", tvCode)
            .putString("serverUrl", serverUrl)
            .putString("voice", voice)
            .putString("sourceLang", sourceLang)
            .putBoolean("dub", dub)
            .putInt("cropTop", cropTop)
            .putInt("cropBottom", cropBottom)
            .apply()
    }

    companion object {
        private const val PREFS = "settings"

        fun load(ctx: Context): Settings {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val d = Settings()
            return Settings(
                tvIp = p.getString("tvIp", d.tvIp) ?: d.tvIp,
                tvCode = p.getString("tvCode", d.tvCode) ?: d.tvCode,
                serverUrl = p.getString("serverUrl", d.serverUrl) ?: d.serverUrl,
                voice = p.getString("voice", d.voice) ?: d.voice,
                sourceLang = p.getString("sourceLang", d.sourceLang) ?: d.sourceLang,
                dub = p.getBoolean("dub", d.dub),
                cropTop = p.getInt("cropTop", d.cropTop),
                cropBottom = p.getInt("cropBottom", d.cropBottom),
            )
        }
    }
}

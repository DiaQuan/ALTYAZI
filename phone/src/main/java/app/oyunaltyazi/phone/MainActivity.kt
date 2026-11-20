package app.oyunaltyazi.phone

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {

    private val langs = listOf(
        "en" to "English", "es" to "Español", "fr" to "Français",
        "de" to "Deutsch", "it" to "Italiano", "pt" to "Português",
    )

    private lateinit var tvIp: EditText
    private lateinit var tvCode: EditText
    private lateinit var serverUrl: EditText
    private lateinit var voice: EditText
    private lateinit var lang: Spinner
    private lateinit var dub: Switch
    private lateinit var cropTop: EditText
    private lateinit var cropBottom: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = Settings.load(this)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        fun label(res: Int) {
            col.addView(TextView(this).apply {
                setText(res)
                setPadding(0, pad / 2, 0, 0)
            })
        }

        fun edit(value: String, numeric: Boolean = false): EditText {
            val e = EditText(this).apply {
                setText(value)
                setSingleLine()
                if (numeric) inputType = InputType.TYPE_CLASS_NUMBER
            }
            col.addView(e)
            return e
        }

        col.addView(TextView(this).apply { setText(R.string.how_to) })
        label(R.string.tv_ip); tvIp = edit(s.tvIp)
        label(R.string.tv_code); tvCode = edit(s.tvCode, numeric = true)
        label(R.string.server_url); serverUrl = edit(s.serverUrl)
        label(R.string.voice); voice = edit(s.voice)
        label(R.string.source_lang)
        lang = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity, android.R.layout.simple_spinner_dropdown_item, langs.map { it.second }
            )
            setSelection(langs.indexOfFirst { it.first == s.sourceLang }.coerceAtLeast(0))
        }
        col.addView(lang)
        dub = Switch(this).apply {
            setText(R.string.dub_mode)
            isChecked = s.dub
            setPadding(0, pad, 0, pad)
        }
        col.addView(dub)
        label(R.string.crop_top); cropTop = edit(s.cropTop.toString(), numeric = true)
        label(R.string.crop_bottom); cropBottom = edit(s.cropBottom.toString(), numeric = true)

        col.addView(Button(this).apply {
            setText(R.string.start)
            setOnClickListener { start() }
        })
        col.addView(Button(this).apply {
            setText(R.string.stop)
            setOnClickListener {
                startService(Intent(this@MainActivity, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
            }
        })

        setContentView(ScrollView(this).apply { addView(col) })
    }

    private fun start() {
        val top = (cropTop.text.toString().toIntOrNull() ?: 70).coerceIn(0, 90)
        val bottom = (cropBottom.text.toString().toIntOrNull() ?: 100).coerceIn(top + 5, 100)
        Settings(
            tvIp = tvIp.text.toString().trim(),
            tvCode = tvCode.text.toString().trim(),
            serverUrl = serverUrl.text.toString().trim(),
            voice = voice.text.toString().trim(),
            sourceLang = langs[lang.selectedItemPosition].first,
            dub = dub.isChecked,
            cropTop = top,
            cropBottom = bottom,
        ).save(this)

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CAPTURE && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, CaptureService::class.java)
                .putExtra(CaptureService.EXTRA_CODE, resultCode)
                .putExtra(CaptureService.EXTRA_DATA, data)
            startForegroundService(i)
        }
    }

    private companion object {
        const val REQ_CAPTURE = 100
    }
}

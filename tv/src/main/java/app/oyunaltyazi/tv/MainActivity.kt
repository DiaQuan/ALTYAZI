package app.oyunaltyazi.tv

import android.app.Activity
import android.content.Context
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.media.tv.TvView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom

class MainActivity : Activity(), SubtitleServer.Listener {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var tvView: TvView
    private lateinit var statusView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var server: SubtitleServer
    private lateinit var dub: DubPlayer

    private var inputs: List<TvInputInfo> = emptyList()
    private var inputIndex = 0
    private var phoneConnected = false
    private var note = ""

    private val pairingCode = (SecureRandom().nextInt(900_000) + 100_000).toString()
    private val clearSubtitle = Runnable { subtitleView.text = "" }
    private val unduck = Runnable { tvView.setStreamVolume(1f) }
    private val hideStatus = Runnable { statusView.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        tvView = findViewById(R.id.tv_view)
        statusView = findViewById(R.id.status)
        subtitleView = findViewById(R.id.subtitle)

        val tvm = getSystemService(Context.TV_INPUT_SERVICE) as TvInputManager
        inputs = tvm.tvInputList.filter { it.isPassthroughInputType && it.type == TvInputInfo.TYPE_HDMI }
        val saved = getPreferences(Context.MODE_PRIVATE).getString("input", null)
        inputIndex = inputs.indexOfFirst { it.id == saved }.coerceAtLeast(0)

        tvView.setCallback(object : TvView.TvInputCallback() {
            override fun onVideoAvailable(inputId: String?) {
                note = ""
                refreshStatus()
            }

            override fun onVideoUnavailable(inputId: String?, reason: Int) {
                note = getString(R.string.video_unavailable, reason)
                refreshStatus()
            }

            override fun onConnectionFailed(inputId: String?) {
                note = getString(R.string.connection_failed)
                refreshStatus()
            }
        })

        dub = DubPlayer { busy -> ui.post { onDubBusy(busy) } }
        refreshStatus()
        showSubtitle(getString(R.string.demo_subtitle), 8_000)

        server = SubtitleServer(PORT, pairingCode, this)
        server.start()
    }

    override fun onStart() {
        super.onStart()
        tuneCurrent()
    }

    override fun onStop() {
        tvView.reset()
        super.onStop()
    }

    override fun onDestroy() {
        server.stop()
        dub.release()
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_LEFT -> { switchInput(-1); true }
        KeyEvent.KEYCODE_DPAD_RIGHT -> { switchInput(1); true }
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> {
            refreshStatus(); true
        }
        else -> super.onKeyDown(keyCode, event)
    }

    // --- HDMI ---

    private fun switchInput(delta: Int) {
        if (inputs.size < 2) return
        inputIndex = (inputIndex + delta + inputs.size) % inputs.size
        tuneCurrent()
    }

    private fun tuneCurrent() {
        val info = inputs.getOrNull(inputIndex)
        if (info == null) {
            note = getString(R.string.no_hdmi)
        } else {
            getPreferences(Context.MODE_PRIVATE).edit().putString("input", info.id).apply()
            tvView.tune(info.id, TvContract.buildChannelUriForPassthroughInput(info.id))
            tvView.setStreamVolume(1f)
        }
        refreshStatus()
    }

    // --- SubtitleServer.Listener (arka plan thread'lerinden çağrılır) ---

    override fun onPhoneConnected(connected: Boolean) {
        ui.post {
            phoneConnected = connected
            refreshStatus()
        }
    }

    override fun onSubtitle(text: String, durationMs: Long) {
        ui.post { showSubtitle(text, durationMs) }
    }

    override fun onAudio(wav: ByteArray, interrupt: Boolean) {
        dub.play(wav, interrupt)
    }

    override fun onClear() {
        dub.stop()
        ui.post {
            ui.removeCallbacks(clearSubtitle)
            subtitleView.text = ""
        }
    }

    // --- UI ---

    private fun onDubBusy(busy: Boolean) {
        if (busy) {
            ui.removeCallbacks(unduck)
            tvView.setStreamVolume(DUCK_LEVEL)
        } else {
            ui.postDelayed(unduck, 400)
        }
    }

    private fun showSubtitle(text: String, durationMs: Long) {
        ui.removeCallbacks(clearSubtitle)
        subtitleView.text = text
        ui.postDelayed(clearSubtitle, durationMs.coerceIn(500L, 15_000L))
    }

    private fun refreshStatus() {
        val head = getString(if (phoneConnected) R.string.status_connected else R.string.status_waiting)
        val ip = localIpAddress() ?: "?"
        val inputName = inputs.getOrNull(inputIndex)?.loadLabel(this)?.toString() ?: "-"
        var text = getString(R.string.status_format, head, pairingCode, ip, PORT, inputName)
        if (note.isNotEmpty()) text += "\n\n$note"
        statusView.text = text
        statusView.visibility = View.VISIBLE
        ui.removeCallbacks(hideStatus)
        if (phoneConnected && note.isEmpty()) ui.postDelayed(hideStatus, 6_000)
    }

    private fun localIpAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces()
            ?.toList()
            ?.flatMap { it.inetAddresses.toList() }
            ?.firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
            ?.hostAddress
    }.getOrNull()

    companion object {
        const val PORT = 8765
        const val DUCK_LEVEL = 0.15f
    }
}

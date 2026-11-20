package app.oyunaltyazi.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ekran yakalama -> OCR -> sabitleme -> çeviri (önbellekli) -> TV'ye altyazı
 * -> (dublaj açıksa) Yazgı TTS (önbellekli) -> TV'ye ses.
 */
class CaptureService : Service() {

    private val handlerThread = HandlerThread("capture").apply { start() }
    private val handler by lazy { Handler(handlerThread.looper) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val busy = AtomicBoolean(false)

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var capW = 0
    private var capH = 0
    private var lastFrameAt = 0L
    private var job: Job? = null
    private var started = false

    private lateinit var settings: Settings
    private lateinit var tv: TvClient
    private lateinit var ocr: TextRecognizer
    private lateinit var translator: TranslationEngine
    private lateinit var translationCache: TranslationCache
    private lateinit var tts: TtsClient
    private val stabilizer = TextStabilizer()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (started) return START_NOT_STICKY
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        started = true
        startInForeground(getString(R.string.notif_starting))

        settings = Settings.load(this)
        ocr = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        translationCache = TranslationCache(File(filesDir, "translations.json"))
        translator = TranslationEngine(translationCache, settings.sourceLang)
        tts = TtsClient(settings.serverUrl, settings.voice, AudioCache(File(cacheDir, "dub")))
        tv = TvClient(settings.tvIp, TV_PORT, settings.tvCode) { state ->
            notify(
                when (state) {
                    "connected" -> getString(R.string.notif_tv_connected)
                    "bad_code" -> getString(R.string.notif_tv_bad_code)
                    else -> getString(R.string.notif_tv_retry)
                }
            )
        }
        tv.start()

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = mpm.getMediaProjection(code, data)
        projection = p
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, handler)
        startCapture(p)
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, handler)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try {
            getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        } catch (e: Exception) {
        }
        if (started) {
            scope.cancel()
            tv.stop()
            virtualDisplay?.release()
            reader?.close()
            try {
                projection?.stop()
            } catch (e: Exception) {
            }
            translationCache.save()
            translator.close()
            ocr.close()
        }
        handlerThread.quitSafely()
        super.onDestroy()
    }

    // --- Ekran yakalama ---

    private fun realSize(): Pair<Int, Int> {
        val dm = DisplayMetrics()
        val d = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        @Suppress("DEPRECATION")
        d.getRealMetrics(dm)
        return dm.widthPixels to dm.heightPixels
    }

    private fun newReader(w: Int, h: Int): ImageReader =
        ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2).also {
            it.setOnImageAvailableListener({ r -> onImage(r) }, handler)
        }

    private fun startCapture(p: MediaProjection) {
        val (w, h) = realSize()
        capW = w
        capH = h
        val r = newReader(w, h)
        reader = r
        virtualDisplay = p.createVirtualDisplay(
            "game-dub", w, h, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler
        )
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            val (w, h) = realSize()
            if (w == capW && h == capH) return
            capW = w
            capH = h
            val old = reader
            val r = newReader(w, h)
            virtualDisplay?.resize(w, h, resources.displayMetrics.densityDpi)
            virtualDisplay?.surface = r.surface
            reader = r
            old?.close()
        }
    }

    private fun onImage(r: ImageReader) {
        val image = try {
            r.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: return
        try {
            val now = SystemClock.uptimeMillis()
            if (now - lastFrameAt < FRAME_INTERVAL_MS || !busy.compareAndSet(false, true)) return
            lastFrameAt = now
            val bmp = try {
                toCroppedBitmap(image)
            } catch (e: Exception) {
                busy.set(false)
                return
            }
            scope.launch {
                try {
                    val text = ocr.process(InputImage.fromBitmap(bmp, 0)).await().text.replace('\n', ' ')
                    handleText(text)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "frame failed", e)
                } finally {
                    bmp.recycle()
                    busy.set(false)
                }
            }
        } finally {
            image.close()
        }
    }

    private fun toCroppedBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val rowPixels = plane.rowStride / plane.pixelStride
        val full = Bitmap.createBitmap(rowPixels, image.height, Bitmap.Config.ARGB_8888)
        full.copyPixelsFromBuffer(plane.buffer)
        val top = (image.height * settings.cropTop / 100).coerceIn(0, image.height - 20)
        val bottom = (image.height * settings.cropBottom / 100).coerceIn(top + 20, image.height)
        val out = Bitmap.createBitmap(full, 0, top, image.width, bottom - top)
        if (out !== full) full.recycle()
        return out
    }

    // --- Boru hattı ---

    private fun handleText(text: String) {
        when (val ev = stabilizer.feed(text)) {
            is TextStabilizer.Event.New -> startPipeline(ev.text)
            TextStabilizer.Event.Clear -> {
                job?.cancel()
                tv.sendClear()
            }
            null -> {}
        }
    }

    private fun startPipeline(source: String) {
        job?.cancel()
        job = scope.launch {
            try {
                val tr = translator.translate(source).trim()
                if (tr.isEmpty()) return@launch
                tv.sendSubtitle(tr, (1500 + tr.length * 55L).coerceIn(2000L, 12000L))
                if (settings.dub) {
                    val wav = tts.synth(tr)
                    ensureActive()
                    tv.sendAudio(wav, true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notify("${getString(R.string.notif_error)}: ${e.message}")
            }
        }
    }

    // --- Bildirim ---

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.notif_running))
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    private fun startInForeground(text: String) {
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun notify(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    companion object {
        const val ACTION_STOP = "app.oyunaltyazi.phone.STOP"
        const val EXTRA_CODE = "projection_code"
        const val EXTRA_DATA = "projection_data"
        private const val TV_PORT = 8765
        private const val FRAME_INTERVAL_MS = 700L
        private const val NOTIF_ID = 1
        private const val CHANNEL = "capture"
        private const val TAG = "CaptureService"
    }
}

package app.oyunaltyazi.tv

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * WAV (16-bit PCM) dublaj seslerini sırayla çalar. Çalmaya başlarken ve bitirirken
 * [onBusyChanged] çağrılır (oyun sesini kısmak/açmak için).
 */
class DubPlayer(private val onBusyChanged: (Boolean) -> Unit) {

    private val executor = Executors.newSingleThreadExecutor()
    private val generation = AtomicInteger(0)
    private val pending = AtomicInteger(0)

    fun play(wav: ByteArray, interrupt: Boolean) {
        val gen = if (interrupt) generation.incrementAndGet() else generation.get()
        if (pending.getAndIncrement() == 0) onBusyChanged(true)
        executor.execute {
            try {
                if (gen == generation.get()) playWav(wav, gen)
            } catch (e: Exception) {
                // bozuk ses verisi vb.
            } finally {
                if (pending.decrementAndGet() == 0) onBusyChanged(false)
            }
        }
    }

    /** Çalanı ve sıradakileri iptal eder. */
    fun stop() {
        generation.incrementAndGet()
    }

    fun release() {
        stop()
        executor.shutdownNow()
    }

    private class WavInfo(val sampleRate: Int, val channels: Int, val dataOffset: Int, val dataLength: Int)

    private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun le32(b: ByteArray, o: Int) = le16(b, o) or (le16(b, o + 2) shl 16)

    private fun parseWav(b: ByteArray): WavInfo? {
        if (b.size < 44 || String(b, 0, 4, Charsets.US_ASCII) != "RIFF" ||
            String(b, 8, 4, Charsets.US_ASCII) != "WAVE"
        ) return null
        var pos = 12
        var rate = 24000
        var ch = 1
        var bits = 16
        while (pos + 8 <= b.size) {
            val id = String(b, pos, 4, Charsets.US_ASCII)
            val size = le32(b, pos + 4)
            if (id == "fmt ") {
                ch = le16(b, pos + 10)
                rate = le32(b, pos + 12)
                bits = le16(b, pos + 22)
            } else if (id == "data") {
                val avail = b.size - (pos + 8)
                val len = if (size <= 0 || size > avail) avail else size
                return if (bits == 16 && (ch == 1 || ch == 2)) WavInfo(rate, ch, pos + 8, len) else null
            }
            if (size < 0) return null
            pos += 8 + size + (size and 1)
        }
        return null
    }

    private fun playWav(wav: ByteArray, gen: Int) {
        val info = parseWav(wav) ?: return
        val mask = if (info.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBuf = AudioTrack.getMinBufferSize(info.sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(info.sampleRate)
                    .setChannelMask(mask)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, 16384))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        try {
            track.play()
            var off = info.dataOffset
            val end = info.dataOffset + info.dataLength
            while (off < end) {
                if (gen != generation.get()) return
                val n = track.write(wav, off, minOf(4096, end - off))
                if (n <= 0) break
                off += n
            }
            val frames = info.dataLength / (2 * info.channels)
            val deadline = SystemClock.uptimeMillis() + frames * 1000L / info.sampleRate + 1000
            while (gen == generation.get() && track.playbackHeadPosition < frames &&
                SystemClock.uptimeMillis() < deadline
            ) {
                Thread.sleep(20)
            }
        } finally {
            try {
                track.pause()
                track.flush()
            } catch (e: Exception) {
            }
            track.release()
        }
    }
}

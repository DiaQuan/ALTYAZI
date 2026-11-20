package app.oyunaltyazi.phone

import android.util.Base64
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** TV uygulamasına bağlanır, kuyruktaki mesajları gönderir, kopunca yeniden bağlanır. */
class TvClient(
    private val ip: String,
    private val port: Int,
    private val code: String,
    private val onState: (String) -> Unit, // "connected" | "retry" | "bad_code"
) {
    @Volatile private var running = false
    @Volatile private var socket: Socket? = null
    private val queue = LinkedBlockingQueue<String>(32)

    fun start() {
        running = true
        thread(name = "tv-client", isDaemon = true) { loop() }
    }

    fun stop() {
        running = false
        queue.clear()
        try {
            socket?.close()
        } catch (e: Exception) {
        }
    }

    fun sendSubtitle(text: String, durationMs: Long) =
        enqueue(JSONObject().put("type", "subtitle").put("text", text).put("durationMs", durationMs))

    fun sendAudio(wav: ByteArray, interrupt: Boolean) = enqueue(
        JSONObject().put("type", "audio").put("interrupt", interrupt)
            .put("data", Base64.encodeToString(wav, Base64.NO_WRAP))
    )

    fun sendClear() = enqueue(JSONObject().put("type", "clear"))

    private fun enqueue(j: JSONObject) {
        if (!running) return
        val line = j.toString()
        if (!queue.offer(line)) {
            queue.clear()
            queue.offer(line)
        }
    }

    private fun loop() {
        var backoff = 1_000L
        while (running) {
            try {
                Socket().use { s ->
                    socket = s
                    s.connect(InetSocketAddress(ip, port), 5_000)
                    s.tcpNoDelay = true
                    s.soTimeout = 5_000
                    val out = BufferedOutputStream(s.getOutputStream())
                    val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                    fun write(line: String) {
                        out.write(line.toByteArray(Charsets.UTF_8))
                        out.write('\n'.code)
                        out.flush()
                    }

                    write(JSONObject().put("type", "hello").put("code", code).toString())
                    val reply = reader.readLine() ?: throw IOException("closed")
                    if (JSONObject(reply).optString("type") != "ready") {
                        onState("bad_code")
                        running = false
                        return@use
                    }
                    onState("connected")
                    backoff = 1_000L
                    while (running) {
                        val next = queue.poll(10, TimeUnit.SECONDS)
                            ?: JSONObject().put("type", "ping").toString()
                        while (reader.ready()) reader.readLine() // pong'ları boşalt
                        write(next)
                    }
                }
            } catch (e: Exception) {
                if (running) {
                    onState("retry")
                    try {
                        Thread.sleep(backoff)
                    } catch (_: InterruptedException) {
                    }
                    backoff = minOf(backoff * 2, 8_000L)
                }
            } finally {
                socket = null
            }
        }
    }
}

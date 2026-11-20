package app.oyunaltyazi.tv

import android.util.Base64
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.Reader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * Satır tabanlı TCP sunucusu. Her satır bir JSON mesajıdır (UTF-8).
 *
 *  Telefon -> TV:
 *    {"type":"hello","code":"123456"}
 *    {"type":"subtitle","text":"Merhaba","durationMs":4000}
 *    {"type":"audio","interrupt":true,"data":"<base64 WAV>"}
 *    {"type":"clear"}            (altyazıyı siler, çalan dublajı durdurur)
 *    {"type":"ping"}
 *  TV -> Telefon:
 *    {"type":"ready"} | {"type":"error","reason":"bad_code"} | {"type":"pong"}
 *
 * Not: sadece yerel ağ için ve şifresiz. İlerde TLS eklenmeli.
 */
class SubtitleServer(
    private val port: Int,
    private val pairingCode: String,
    private val listener: Listener,
) {
    interface Listener {
        fun onPhoneConnected(connected: Boolean)
        fun onSubtitle(text: String, durationMs: Long)
        fun onAudio(wav: ByteArray, interrupt: Boolean)
        fun onClear()
    }

    @Volatile private var running = false
    @Volatile private var serverSocket: ServerSocket? = null

    fun start() {
        if (running) return
        running = true
        thread(name = "subtitle-accept", isDaemon = true) {
            try {
                ServerSocket().use { server ->
                    server.reuseAddress = true
                    server.bind(InetSocketAddress(port))
                    serverSocket = server
                    while (running) {
                        val client = try {
                            server.accept()
                        } catch (e: Exception) {
                            break
                        }
                        thread(name = "subtitle-client", isDaemon = true) { handle(client) }
                    }
                }
            } catch (e: Exception) {
                // Port meşgul olabilir.
            }
        }
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
        }
    }

    private fun codeMatches(candidate: String): Boolean =
        MessageDigest.isEqual(candidate.toByteArray(), pairingCode.toByteArray())

    private fun readLineBounded(reader: Reader, max: Int): String? {
        val sb = StringBuilder()
        while (true) {
            val c = reader.read()
            if (c == -1) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString()
            if (sb.length >= max) throw IOException("line too long")
            sb.append(c.toChar())
        }
    }

    private fun handle(socket: Socket) {
        var authed = false
        try {
            socket.use { s ->
                s.soTimeout = 30_000 // telefon her ~10 sn'de ping atmalı
                s.tcpNoDelay = true
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                val writer = OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8)

                fun send(json: JSONObject) {
                    writer.write(json.toString())
                    writer.write("\n")
                    writer.flush()
                }

                while (running) {
                    val line = readLineBounded(reader, if (authed) MAX_LINE_AUTHED else MAX_LINE_PRE_AUTH)
                        ?: break
                    val msg = try {
                        JSONObject(line)
                    } catch (e: Exception) {
                        continue
                    }
                    when (msg.optString("type")) {
                        "hello" -> {
                            if (codeMatches(msg.optString("code"))) {
                                authed = true
                                listener.onPhoneConnected(true)
                                send(JSONObject().put("type", "ready"))
                            } else {
                                Thread.sleep(1000) // kaba kuvvet denemelerini yavaşlat
                                send(JSONObject().put("type", "error").put("reason", "bad_code"))
                                break
                            }
                        }
                        "ping" -> if (authed) send(JSONObject().put("type", "pong"))
                        "subtitle" -> if (authed) {
                            listener.onSubtitle(msg.optString("text"), msg.optLong("durationMs", 4000))
                        }
                        "audio" -> if (authed) {
                            val wav = Base64.decode(msg.optString("data"), Base64.DEFAULT)
                            listener.onAudio(wav, msg.optBoolean("interrupt", true))
                        }
                        "clear" -> if (authed) listener.onClear()
                    }
                }
            }
        } catch (e: Exception) {
            // zaman aşımı / bağlantı koptu
        } finally {
            if (authed) listener.onPhoneConnected(false)
        }
    }

    private companion object {
        const val MAX_LINE_PRE_AUTH = 4 * 1024
        const val MAX_LINE_AUTHED = 6 * 1024 * 1024
    }
}

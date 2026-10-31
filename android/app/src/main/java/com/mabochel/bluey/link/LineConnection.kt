package com.mabochel.bluey.link

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The phone's side of one encrypted, newline-delimited link to the Mac (LineConnection in Swift).
 *
 * Handshake: the Mac sends {"challenge": base64(32 bytes)}, the phone answers {"proof": HMAC}, then the
 * Mac's first sealed record must be the "paired" command. Only then is the link authenticated.
 * Callbacks go through [deliver], so the app can run them on its main thread.
 */
class LineConnection(
    private val address: InetSocketAddress,
    private val secret: ByteArray,
    private val deliver: (() -> Unit) -> Unit,
    private val listener: Listener,
) {
    interface Listener {
        fun onAuthenticated(link: LineConnection)
        fun onPacket(link: LineConnection, packet: Packet)
        fun onClosed(link: LineConnection, reason: String)
    }

    @Volatile var authenticated = false
        private set

    private val closed = AtomicBoolean(false)
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "bluey-link-write").apply { isDaemon = true } }
    private val timer = Executors.newSingleThreadScheduledExecutor { Thread(it, "bluey-link-timer").apply { isDaemon = true } }
    @Volatile private var socket: Socket? = null
    @Volatile private var output: OutputStream? = null
    @Volatile private var channel: SecureChannel? = null

    fun start() {
        Thread({ run() }, "bluey-link-read").apply { isDaemon = true }.start()
        timer.schedule({ if (!authenticated) cancel("The Mac didn't finish pairing in time.") }, 8, TimeUnit.SECONDS)
    }

    fun send(packet: Packet) {
        if (!authenticated || closed.get()) return
        val channel = channel ?: return
        try {
            writer.execute {
                try {
                    writeLine(channel.seal(packet))
                } catch (e: Exception) {
                    cancel("Send failed: ${e.message}")
                }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            // The link closed a moment ago; the packet has nowhere to go.
        }
    }

    fun cancel(reason: String = "Closed") {
        if (!closed.compareAndSet(false, true)) return
        timer.shutdownNow()
        // Close after anything already queued (like a last "focusPause") has gone out.
        try {
            writer.execute { runCatching { socket?.close() } }
            writer.shutdown()
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            runCatching { socket?.close() }
        }
        deliver { listener.onClosed(this, reason) }
    }

    private fun run() {
        try {
            val s = Socket()
            socket = s
            s.tcpNoDelay = true
            s.keepAlive = true
            s.connect(address, 5000)
            if (closed.get()) { s.close(); return }
            output = s.getOutputStream()
            readLines(s.getInputStream())
            cancel("The Mac closed the connection.")
        } catch (e: Exception) {
            cancel(e.message ?: "Connection failed")
        }
    }

    private fun readLines(input: InputStream) {
        val line = ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        while (!closed.get()) {
            val count = input.read(chunk)
            if (count < 0) return
            var start = 0
            for (i in 0 until count) {
                if (chunk[i] == '\n'.code.toByte()) {
                    line.write(chunk, start, i - start)
                    accept(line.toString(Charsets.UTF_8.name()))
                    line.reset()
                    start = i + 1
                }
            }
            line.write(chunk, start, count - start)
            if (line.size() > 16 * 1024 * 1024) throw IOException("Line too long")
        }
    }

    private fun accept(line: String) {
        val channel = channel
        if (channel != null) {
            val packet = channel.open(line) // throws on tampering or replay, which closes the link
            if (!authenticated) {
                if (packet?.command != "paired") throw IOException("The Mac didn't accept this pairing.")
                authenticated = true
                deliver { if (!closed.get()) listener.onAuthenticated(this) }
            } else if (packet != null) {
                deliver { if (!closed.get()) listener.onPacket(this, packet) }
            }
            return
        }
        // Plain-text handshake: the Mac's challenge.
        val json = runCatching { BlueyJson.parseToJsonElement(line) as JsonObject }.getOrNull()
            ?: throw IOException("Unexpected handshake")
        val text = (json["challenge"] as? JsonPrimitive)?.contentOrNull ?: throw IOException("No challenge")
        val challenge = runCatching { Base64.getDecoder().decode(text) }.getOrNull()
        if (challenge == null || challenge.size != 32) throw IOException("Bad challenge")
        val secure = SecureChannel(secret, challenge, server = false)
        this.channel = secure
        val proof = Base64.getEncoder().encodeToString(BlueyCrypto.proof(secret, challenge))
        val reply = BlueyJson.encodeToString(JsonObject.serializer(), JsonObject(mapOf("proof" to JsonPrimitive(proof))))
        writer.execute {
            try { writeLine(reply) } catch (e: Exception) { cancel("Handshake failed") }
        }
    }

    private fun writeLine(text: String) {
        val out = output ?: throw IOException("Not connected")
        out.write((text + "\n").toByteArray(Charsets.UTF_8))
        out.flush()
    }
}

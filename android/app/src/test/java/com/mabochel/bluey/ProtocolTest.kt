package com.mabochel.bluey

import com.mabochel.bluey.focus.FocusActivity
import com.mabochel.bluey.focus.FocusSession
import com.mabochel.bluey.focus.FocusSync
import com.mabochel.bluey.focus.focusPercent
import com.mabochel.bluey.link.BlueyCrypto
import com.mabochel.bluey.link.BlueyJson
import com.mabochel.bluey.link.FaceState
import com.mabochel.bluey.link.LineConnection
import com.mabochel.bluey.link.Packet
import com.mabochel.bluey.link.PairingRecord
import com.mabochel.bluey.link.SecureChannel
import com.mabochel.bluey.voice.Chirper
import com.mabochel.bluey.voice.Resampler
import com.mabochel.bluey.voice.VoiceTurnBuffer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

class CryptoTest {
    @Test fun hkdfMatchesRfc5869() {
        // RFC 5869 test case 1.
        val okm = BlueyCrypto.hkdf(hex("0b".repeat(22)), hex("000102030405060708090a0b0c"),
            hex("f0f1f2f3f4f5f6f7f8f9"), 42)
        assertArrayEquals(hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"), okm)
    }

    @Test fun hmacMatchesRfc4231() {
        // RFC 4231 test case 2.
        val mac = BlueyCrypto.hmacSha256("Jefe".toByteArray(), "what do ya want for nothing?".toByteArray())
        assertArrayEquals(hex("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843"), mac)
    }

    @Test fun proofVerifiesOnlyWithTheSameSecret() {
        val secret = BlueyCrypto.randomBytes(32)
        val challenge = BlueyCrypto.randomBytes(32)
        val proof = BlueyCrypto.proof(secret, challenge)
        assertTrue(BlueyCrypto.verifies(proof, secret, challenge))
        assertFalse(BlueyCrypto.verifies(proof, BlueyCrypto.randomBytes(32), challenge))
        assertFalse(BlueyCrypto.verifies(proof, secret, BlueyCrypto.randomBytes(32)))
    }
}

class SecureChannelTest {
    private val secret = BlueyCrypto.randomBytes(32)
    private val challenge = BlueyCrypto.randomBytes(32)

    @Test fun roundTripsBothDirectionsInOrder() {
        val mac = SecureChannel(secret, challenge, server = true)
        val phone = SecureChannel(secret, challenge, server = false)
        assertEquals("paired", phone.open(mac.seal(Packet(command = "paired")))?.command)
        assertEquals(0.25, phone.open(mac.seal(Packet(face = FaceState(gazeX = 0.25, mood = "happy"))))?.face?.gazeX)
        assertEquals("Pixel", mac.open(phone.seal(Packet(hello = "Pixel")))?.hello)
    }

    @Test(expected = SecureChannel.InvalidRecord::class)
    fun rejectsReplay() {
        val mac = SecureChannel(secret, challenge, server = true)
        val phone = SecureChannel(secret, challenge, server = false)
        val line = mac.seal(Packet(command = "wake"))
        phone.open(line)
        phone.open(line)
    }

    @Test(expected = SecureChannel.InvalidRecord::class)
    fun rejectsTampering() {
        val mac = SecureChannel(secret, challenge, server = true)
        val phone = SecureChannel(secret, challenge, server = false)
        val bytes = Base64.getDecoder().decode(mac.seal(Packet(command = "wake")))
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        phone.open(Base64.getEncoder().encodeToString(bytes))
    }

    @Test(expected = SecureChannel.InvalidRecord::class)
    fun rejectsItsOwnDirection() {
        val phone = SecureChannel(secret, challenge, server = false)
        val other = SecureChannel(secret, challenge, server = false)
        other.open(phone.seal(Packet(command = "echo")))
    }

    @Test fun recordJsonMatchesSwiftShape() {
        // Decrypt with a raw AES-GCM key derived by hand, to check the exact record layout.
        val mac = SecureChannel(secret, challenge, server = true)
        val key = BlueyCrypto.hkdf(secret, challenge, "bluey-v1-mac-to-phone".toByteArray(), 32)
        val sealed = Base64.getDecoder().decode(mac.seal(Packet(command = "paired")))
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"),
            javax.crypto.spec.GCMParameterSpec(128, sealed, 0, 12))
        val json = String(cipher.doFinal(sealed, 12, sealed.size - 12))
        assertEquals("""{"sequence":0,"packet":{"command":"paired"}}""", json)
    }
}

class PairingRecordTest {
    private val secret = ByteArray(32) { it.toByte() }

    private fun swiftQr(service: String, secretB64: String, version: Int = 1): String {
        // Swift's JSONEncoder escapes "/" — include that to be sure we read it.
        val json = """{"version":$version,"service":"${service.replace("/", "\\/")}","secret":"${secretB64.replace("/", "\\/")}"}"""
        return "bluey-pair:" + Base64.getEncoder().encodeToString(json.toByteArray())
    }

    @Test fun parsesTheMacsQrText() {
        val record = PairingRecord.parse(swiftQr("Riley's MacBook Pro", Base64.getEncoder().encodeToString(secret)))
        assertNotNull(record)
        assertEquals("Riley's MacBook Pro", record!!.service)
        assertArrayEquals(secret, record.key)
        // SHA-256 of bytes 0…31, first 12 bytes as hex.
        assertEquals("630dcd2966c4336691125448", record.identifier)
    }

    @Test fun rejectsBadCodes() {
        assertNull(PairingRecord.parse("hello"))
        assertNull(PairingRecord.parse("bluey-pair:not base64!"))
        assertNull(PairingRecord.parse(swiftQr("Mac", Base64.getEncoder().encodeToString(ByteArray(16)))))
        assertNull(PairingRecord.parse(swiftQr("Mac", Base64.getEncoder().encodeToString(secret), version = 2)))
        assertNull(PairingRecord.parse(swiftQr("", Base64.getEncoder().encodeToString(secret))))
    }
}

class FocusSyncTest {
    /** Shaped exactly like JSONEncoder output from FocusCompanion on the Mac. */
    private val swiftJson = """
        {"current":{"id":"3F2504E0-4F89-11D3-9A0C-0305E82C3301","task":"Edit the Codex video","duration":1800,
         "startedAt":783612000.5,"elapsed":95.2,"status":"running","detail":"Next check in 30 seconds",
         "checks":[{"id":"A1","elapsed":30,"activity":"on_task","probability":0.08,"confidence":0.9,
                    "probabilities":{"on_task":0.9,"off_task":0.08,"uncertain":0.02},"sampleDuration":30,"nudged":false},
                   {"id":"A2","elapsed":60,"activity":"off_task","probability":0.71,"confidence":0.6,"nudged":true},
                   {"id":"A3","elapsed":90,"activity":"paused","probability":0,"confidence":0,"nudged":false}],
         "developerMode":false,"usedDeveloperMode":false,"realElapsed":95.2},
         "history":[],"revision":12,"sentAt":783612095.1,"developerMode":false}
    """.trimIndent()

    @Test fun decodesTheMacsState() {
        val sync = BlueyJson.decodeFromString(FocusSync.serializer(), swiftJson)
        val session = sync.current!!
        assertEquals(12, sync.revision)
        assertEquals("Edit the Codex video", session.task)
        assertTrue(session.isRunning)
        assertEquals(1800.0 - 95.2, session.remaining, 1e-9)
        assertEquals("28:25", FocusSession.clock(session.remaining))
        // 783612000.5 s after 2001-01-01 is 2025-10-31.
        assertEquals((783612000.5 + 978307200) * 1000, session.startedMillis.toDouble(), 1.0)
        assertEquals(0.9, session.checks[0].estimate(FocusActivity.ON_TASK)!!, 1e-9)
        // An older check only kept P(off_task): never infer the others.
        assertEquals(0.71, session.checks[1].estimate(FocusActivity.OFF_TASK)!!, 1e-9)
        assertNull(session.checks[1].estimate(FocusActivity.ON_TASK))
        assertNull(session.checks[2].estimate(FocusActivity.OFF_TASK))
        assertEquals("71%", 0.71.focusPercent())
        assertEquals("65.3%", 0.653.focusPercent())
    }

    @Test fun clockRoundsUpLikeSwift() {
        assertEquals("30:00", FocusSession.clock(1800.0))
        assertEquals("00:01", FocusSession.clock(0.2))
        assertEquals("00:00", FocusSession.clock(-5.0))
    }
}

class VoiceTest {
    @Test fun turnBufferHoldsAudioUntilConnected() {
        val buffer = VoiceTurnBuffer()
        buffer.begin()
        assertNull(buffer.append(ByteArray(2400), ready = false))
        assertFalse(buffer.canCommit)
        assertNull(buffer.append(ByteArray(2400), ready = false))
        assertTrue(buffer.canCommit)
        assertEquals(2, buffer.connected().size)
        assertEquals(100, buffer.append(ByteArray(100), ready = true)?.size)
    }

    @Test fun turnBufferDropsEverythingOnOverflow() {
        val buffer = VoiceTurnBuffer()
        repeat(201) { buffer.append(ByteArray(4800), ready = false) }
        assertTrue(buffer.overflowed)
        assertFalse(buffer.canCommit)
        assertTrue(buffer.connected().isEmpty())
    }

    @Test fun resamplerKeepsDuration() {
        for (rate in intArrayOf(48_000, 44_100, 16_000)) {
            val resampler = Resampler(rate, 24_000)
            var out = 0
            repeat(20) { out += resampler.process(ShortArray(rate / 20) { 1000 }, rate / 20).size }
            assertEquals("rate $rate", 24_000.0, out.toDouble(), 3.0) // one second in, one second out
        }
    }

    @Test fun chirpLastsAboutAsLongAsOnTheIphone() {
        val samples = Chirper.synthesize(3)
        val seconds = samples.size / 24_000.0
        assertTrue(seconds in 0.3..0.45)
        assertTrue(samples.all { it in -1f..1f })
    }
}

/** The phone's LineConnection against a Mac-side server written from LineConnection.swift's server half. */
class LoopbackLinkTest {
    @Test fun handshakeAuthenticatesAndCarriesPackets() {
        val secret = BlueyCrypto.randomBytes(32)
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val fromPhone = LinkedBlockingQueue<Packet>()
        val macThread = Thread {
            server.accept().use { socket ->
                val input = BufferedReader(InputStreamReader(socket.getInputStream()))
                val out = socket.getOutputStream()
                fun line(s: String) { out.write((s + "\n").toByteArray()); out.flush() }
                val challenge = BlueyCrypto.randomBytes(32)
                line("""{"challenge":"${Base64.getEncoder().encodeToString(challenge).replace("/", "\\/")}"}""")
                val proof = BlueyJson.parseToJsonElement(input.readLine()).jsonObject["proof"]!!.jsonPrimitive.content
                check(BlueyCrypto.verifies(Base64.getDecoder().decode(proof), secret, challenge))
                val channel = SecureChannel(secret, challenge, server = true)
                line(channel.seal(Packet(command = "paired")))
                line(channel.seal(Packet(hello = "Test Mac")))
                line(channel.seal(Packet(face = FaceState(0.5, -0.5, "pointing", 0.3))))
                repeat(2) { fromPhone.put(channel.open(input.readLine())!!) }
            }
        }.apply { start() }

        val authenticated = CountDownLatch(1)
        val received = LinkedBlockingQueue<Packet>()
        lateinit var link: LineConnection
        link = LineConnection(InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort), secret, { it() },
            object : LineConnection.Listener {
                override fun onAuthenticated(link: LineConnection) {
                    link.send(Packet(hello = "Pixel 9"))
                    link.send(Packet(command = "focusSync"))
                    authenticated.countDown()
                }
                override fun onPacket(link: LineConnection, packet: Packet) { received.put(packet) }
                override fun onClosed(link: LineConnection, reason: String) {}
            })
        link.start()
        assertTrue(authenticated.await(5, TimeUnit.SECONDS))
        assertEquals("Test Mac", received.poll(5, TimeUnit.SECONDS)?.hello)
        assertEquals("pointing", received.poll(5, TimeUnit.SECONDS)?.face?.mood)
        assertEquals("Pixel 9", fromPhone.poll(5, TimeUnit.SECONDS)?.hello)
        assertEquals("focusSync", fromPhone.poll(5, TimeUnit.SECONDS)?.command)
        link.cancel()
        macThread.join(2000)
        server.close()
    }

    @Test fun wrongSecretNeverAuthenticates() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val macSecret = BlueyCrypto.randomBytes(32)
        Thread {
            runCatching {
                server.accept().use { socket ->
                    val input = BufferedReader(InputStreamReader(socket.getInputStream()))
                    val challenge = BlueyCrypto.randomBytes(32)
                    socket.getOutputStream().write("""{"challenge":"${Base64.getEncoder().encodeToString(challenge)}"}""".toByteArray() + 10)
                    val proof = BlueyJson.parseToJsonElement(input.readLine()).jsonObject["proof"]!!.jsonPrimitive.content
                    // The Mac drops the connection when the proof doesn't verify.
                    check(!BlueyCrypto.verifies(Base64.getDecoder().decode(proof), macSecret, challenge))
                }
            }
        }.start()
        val closed = CountDownLatch(1)
        var authenticated = false
        LineConnection(InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort), BlueyCrypto.randomBytes(32), { it() },
            object : LineConnection.Listener {
                override fun onAuthenticated(link: LineConnection) { authenticated = true }
                override fun onPacket(link: LineConnection, packet: Packet) {}
                override fun onClosed(link: LineConnection, reason: String) { closed.countDown() }
            }).start()
        assertTrue(closed.await(10, TimeUnit.SECONDS))
        assertFalse(authenticated)
        server.close()
    }
}

/**
 * Runs only when scripts/mock_mac.py is listening (see android/README.md): an independent Python
 * implementation of the Mac's side, using a different crypto library, checks the bytes on the wire.
 */
class MockMacInteropTest {
    @Test fun talksToTheIndependentMockMac() {
        val port = System.getenv("BLUEY_MOCK_MAC_PORT")?.toIntOrNull()
        val qr = System.getenv("BLUEY_MOCK_MAC_QR")
        assumeTrue("mock Mac not running", port != null && qr != null)
        val record = PairingRecord.parse(qr!!)!!
        val authenticated = CountDownLatch(1)
        val received = LinkedBlockingQueue<Packet>()
        LineConnection(InetSocketAddress(InetAddress.getLoopbackAddress(), port!!), record.key!!, { it() },
            object : LineConnection.Listener {
                override fun onAuthenticated(link: LineConnection) {
                    link.send(Packet(hello = "Android test"))
                    link.send(Packet(command = "focusSync"))
                    link.send(Packet(command = "realtimeToken", callID = "CALL-1"))
                    authenticated.countDown()
                }
                override fun onPacket(link: LineConnection, packet: Packet) { received.put(packet) }
                override fun onClosed(link: LineConnection, reason: String) {}
            }).start()
        assertTrue(authenticated.await(5, TimeUnit.SECONDS))
        val packets = (1..4).mapNotNull { received.poll(5, TimeUnit.SECONDS) }
        assertEquals("Mock Mac", packets[0].hello)
        assertEquals("thinking", packets[1].face?.mood)
        val state = BlueyJson.decodeFromString(FocusSync.serializer(), packets[2].text!!)
        assertEquals("Write the Android port", state.current?.task)
        assertEquals("CALL-1", packets[3].callID)
        assertEquals("ek_mock", packets[3].text)
    }
}


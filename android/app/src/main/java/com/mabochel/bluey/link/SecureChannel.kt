package com.mabochel.bluey.link

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The QR pairing credential. Same JSON and "bluey-pair:" text as Shared/SecurePairing.swift,
 * so the Mac's existing QR code works unchanged.
 */
@Serializable
data class PairingRecord(
    val version: Int = 1,
    /** The Mac's Bonjour service name. */
    val service: String,
    /** 32 random bytes, base64. Never log or sync this. */
    val secret: String,
) {
    val key: ByteArray?
        get() = runCatching { Base64.getDecoder().decode(secret) }.getOrNull()?.takeIf { it.size == 32 }

    /** Public fingerprint the Mac advertises in its Bonjour TXT record as "bluey-id". */
    val identifier: String
        get() = key?.let { k ->
            MessageDigest.getInstance("SHA-256").digest(k).take(12).joinToString("") { "%02x".format(it) }
        } ?: "invalid"

    companion object {
        const val PREFIX = "bluey-pair:"

        fun parse(raw: String): PairingRecord? {
            val text = raw.trim()
            if (!text.startsWith(PREFIX) || text.length >= 2048) return null
            val json = runCatching { String(Base64.getDecoder().decode(text.removePrefix(PREFIX)), Charsets.UTF_8) }
                .getOrNull() ?: return null
            val record = runCatching { BlueyJson.decodeFromString<PairingRecord>(json) }.getOrNull() ?: return null
            if (record.version != 1 || record.key == null || record.service.isEmpty()) return null
            return record
        }
    }
}

object BlueyCrypto {
    private val random = SecureRandom()

    fun randomBytes(count: Int): ByteArray = ByteArray(count).also { random.nextBytes(it) }

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }

    /** RFC 5869 HKDF-SHA256, the same as CryptoKit's HKDF<SHA256>.deriveKey. */
    fun hkdf(inputKey: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmacSha256(if (salt.isEmpty()) ByteArray(32) else salt, inputKey)
        val out = java.io.ByteArrayOutputStream()
        var block = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            block = hmacSha256(prk, block + info + byteArrayOf(counter.toByte()))
            out.write(block)
            counter++
        }
        return out.toByteArray().copyOf(length)
    }

    private val pairLabel = "bluey-pair-v1".toByteArray(Charsets.UTF_8)

    /** What the phone sends to prove it holds the secret: HMAC(secret, "bluey-pair-v1" + challenge). */
    fun proof(secret: ByteArray, challenge: ByteArray): ByteArray = hmacSha256(secret, pairLabel + challenge)

    fun verifies(proof: ByteArray, secret: ByteArray, challenge: ByteArray): Boolean =
        MessageDigest.isEqual(proof, proof(secret, challenge))
}

/**
 * Challenge-derived direction keys and ordered AES-GCM records, like SecureChannel in Swift.
 * Each record is base64(nonce ‖ ciphertext ‖ tag) of {"sequence": n, "packet": {...}}.
 */
class SecureChannel(secret: ByteArray, challenge: ByteArray, server: Boolean) {
    private val sendKey: ByteArray
    private val receiveKey: ByteArray
    private var sent = 0
    private var received = 0
    private val random = SecureRandom()

    @Serializable
    private data class Record(val sequence: Int, val packet: Packet)

    class InvalidRecord : Exception("invalid record")

    init {
        fun key(direction: String) =
            BlueyCrypto.hkdf(secret, challenge, ("bluey-v1-$direction").toByteArray(Charsets.UTF_8), 32)
        sendKey = key(if (server) "mac-to-phone" else "phone-to-mac")
        receiveKey = key(if (server) "phone-to-mac" else "mac-to-phone")
    }

    /** Returns one base64 line (without the newline). */
    @Synchronized
    fun seal(packet: Packet): String {
        val json = BlueyJson.encodeToString(Record(sent, packet)).toByteArray(Charsets.UTF_8)
        val nonce = ByteArray(12).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sendKey, "AES"), GCMParameterSpec(128, nonce))
        val sealed = nonce + cipher.doFinal(json)
        sent++
        return Base64.getEncoder().encodeToString(sealed)
    }

    /**
     * Opens one line. Throws on tampering, replay or reordering. A record that decrypts but holds a
     * packet this build doesn't understand returns null (and still counts), so a newer Mac can't stall us.
     */
    @Synchronized
    fun open(line: String): Packet? {
        val sealed = runCatching { Base64.getDecoder().decode(line.trim()) }.getOrNull() ?: throw InvalidRecord()
        if (sealed.size < 12 + 16) throw InvalidRecord()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(receiveKey, "AES"), GCMParameterSpec(128, sealed, 0, 12))
        val plain = try {
            cipher.doFinal(sealed, 12, sealed.size - 12)
        } catch (e: Exception) {
            throw InvalidRecord()
        }
        val json = BlueyJson.parseToJsonElement(String(plain, Charsets.UTF_8))
        val sequence = runCatching {
            (json as kotlinx.serialization.json.JsonObject)["sequence"]!!.toString().toInt()
        }.getOrNull() ?: throw InvalidRecord()
        if (sequence != received) throw InvalidRecord()
        received++
        return runCatching { BlueyJson.decodeFromJsonElement(Record.serializer(), json).packet }.getOrNull()
    }
}

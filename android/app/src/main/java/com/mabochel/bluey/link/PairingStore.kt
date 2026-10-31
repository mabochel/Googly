package com.mabochel.bluey.link

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.serialization.encodeToString
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps pairing credentials (one per Mac, by Bonjour name) encrypted with a key that lives in the
 * Android Keystore and never leaves this device, like the iPhone's device-only Keychain item.
 * App backups are turned off in the manifest.
 */
class PairingStore(context: Context) {
    private val prefs = context.getSharedPreferences("pairing", Context.MODE_PRIVATE)
    private val alias = "bluey-pairing"

    /** The record for [service], or the only/first saved one when [service] is null. */
    fun load(service: String?): PairingRecord? {
        val name = service ?: prefs.all.keys.firstOrNull { it.startsWith(PREFIX) }?.removePrefix(PREFIX) ?: return null
        val sealed = prefs.getString(PREFIX + name, null) ?: return null
        return runCatching {
            val bytes = Base64.getDecoder().decode(sealed)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
            val json = String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
            BlueyJson.decodeFromString<PairingRecord>(json)
        }.getOrNull()
    }

    fun save(record: PairingRecord): Boolean = runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(BlueyJson.encodeToString(record).toByteArray(Charsets.UTF_8))
        prefs.edit().putString(PREFIX + record.service, Base64.getEncoder().encodeToString(sealed)).commit()
    }.getOrDefault(false)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFIX = "record:"
    }
}

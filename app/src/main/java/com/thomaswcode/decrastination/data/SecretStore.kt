package com.thomaswcode.decrastination.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The credentials and keys the app holds. Their values are never logged, shown or exported. */
enum class Secret {
    PowerPlannerUsername,
    PowerPlannerPassword,

    /** The web API's session for the account, reused until it stops working. */
    PowerPlannerSession,
    PowerPlannerAccountId,
    GmailAddress,
    GmailAppPassword,
    AnthropicApiKey,

    /** The parent override's authenticator secret (Phase 3). */
    TotpSecret,
}

/** Encrypts the secrets file. The real one keeps its key in the Android Keystore. */
interface SecretCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(sealed: ByteArray): ByteArray
}

/**
 * The secrets, encrypted as one file under a key that never leaves the Android Keystore. It
 * replaces EncryptedSharedPreferences (planned, now deprecated) with the same idea and no
 * dependency. A file that can't be decrypted (the key lost with a reinstall) reads as empty, and
 * the setup checklist shows which credentials need entering again.
 */
class SecretStore(private val file: File, private val cipher: SecretCipher) {
    private val mutex = Mutex()
    private val _values = MutableStateFlow(load())

    /** Which secrets are set, for the setup checklist. Never the values. */
    private val _present = MutableStateFlow(_values.value.keys)
    val present: StateFlow<Set<Secret>> = _present.asStateFlow()

    operator fun get(secret: Secret): String? = _values.value[secret]

    fun has(secret: Secret): Boolean = !_values.value[secret].isNullOrEmpty()

    /** Sets (or, with null or blank, removes) each of [values]. */
    suspend fun put(values: Map<Secret, String?>) = mutex.withLock {
        val next = _values.value.toMutableMap()
        for ((key, value) in values) {
            if (value.isNullOrBlank()) next.remove(key) else next[key] = value
        }
        if (next != _values.value) {
            withContext(Dispatchers.IO) { write(next) }
            _values.value = next
            _present.value = next.keys
        }
    }

    suspend fun put(secret: Secret, value: String?) = put(mapOf(secret to value))

    private fun load(): Map<Secret, String> {
        if (!file.exists()) return emptyMap()
        return runCatching {
            val plain = cipher.decrypt(file.readBytes()).decodeToString()
            JsonStore.json.decodeFromString(serializer, plain)
                .mapNotNull { (key, value) -> Secret.entries.firstOrNull { it.name == key }?.let { it to value } }
                .toMap()
        }.getOrDefault(emptyMap())
    }

    private fun write(values: Map<Secret, String>) {
        val plain = JsonStore.json.encodeToString(serializer, values.mapKeys { it.key.name })
        val dir = requireNotNull(file.absoluteFile.parentFile)
        dir.mkdirs()
        val tmp = File(dir, "${file.name}.tmp")
        tmp.writeBytes(cipher.encrypt(plain.encodeToByteArray()))
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        val serializer = MapSerializer(String.serializer(), String.serializer())
    }
}

/**
 * AES-256-GCM under a Keystore key made for this purpose. Usable while the phone is locked, since
 * the sync runs in the background. Output: the 12-byte IV, then the ciphertext and tag.
 */
class KeystoreCipher(private val alias: String = "decrastination-secrets") : SecretCipher {

    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun decrypt(sealed: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
        return cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

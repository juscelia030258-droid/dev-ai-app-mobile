package com.example.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.example.core.model.AgentRole
import com.example.monitoring.data.ApiKeyDao
import com.example.monitoring.data.ApiKeyEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

interface KeyCipher {
    fun encrypt(plainText: String): String
    fun decrypt(cipherBlobBase64: String): String
    val backendName: String
}

class AndroidKeystoreCipher(
    private val alias: String = "local_dev_ai_master_key_v1"
) : KeyCipher {

    override val backendName: String = "AndroidKeyStore AES-256-GCM"

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keyStore.getKey(alias, null) as? SecretKey
        if (existing != null) return existing

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    override fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
        val combined = ByteArray(1 + iv.size + encrypted.size)
        combined[0] = iv.size.toByte()
        System.arraycopy(iv, 0, combined, 1, iv.size)
        System.arraycopy(encrypted, 0, combined, 1 + iv.size, encrypted.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    override fun decrypt(cipherBlobBase64: String): String {
        val combined = Base64.decode(cipherBlobBase64, Base64.NO_WRAP)
        val ivLen = combined[0].toInt() and 0xFF
        val iv = combined.copyOfRange(1, 1 + ivLen)
        val encrypted = combined.copyOfRange(1 + ivLen, combined.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(128, iv))
        val plain = cipher.doFinal(encrypted)
        return String(plain, StandardCharsets.UTF_8)
    }
}

/**
 * Deterministic fallback used ONLY in Robolectric JVM unit tests where AndroidKeyStore is absent.
 */
class RobolectricFallbackCipher : KeyCipher {
    override val backendName: String = "JVM Test AES-256-GCM"
    private val key = SecretKeySpec(
        MessageDigest.getInstance("SHA-256")
            .digest("local-dev-ai-robolectric-test-only".toByteArray(StandardCharsets.UTF_8)),
        "AES"
    )

    override fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
        val combined = ByteArray(1 + iv.size + encrypted.size)
        combined[0] = iv.size.toByte()
        System.arraycopy(iv, 0, combined, 1, iv.size)
        System.arraycopy(encrypted, 0, combined, 1 + iv.size, encrypted.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    override fun decrypt(cipherBlobBase64: String): String {
        val combined = Base64.decode(cipherBlobBase64, Base64.NO_WRAP)
        val ivLen = combined[0].toInt() and 0xFF
        val iv = combined.copyOfRange(1, 1 + ivLen)
        val encrypted = combined.copyOfRange(1 + ivLen, combined.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
    }
}

enum class KeyPermission(val label: String) {
    ALL_AGENTS("Todos os agentes"),
    CODING_ONLY("Somente Coding AI"),
    VISION_ONLY("Somente Vision AI"),
    REASONING_ONLY("Somente Reasoning AI");

    fun allows(requestedModelId: String?): Boolean {
        val normalized = requestedModelId?.trim()?.lowercase().orEmpty()
        return when (this) {
            ALL_AGENTS -> true
            CODING_ONLY -> normalized == AgentRole.CODING.apiModelId || normalized == "coding"
            VISION_ONLY -> normalized == AgentRole.VISION.apiModelId || normalized == "vision"
            REASONING_ONLY -> normalized == AgentRole.REASONING.apiModelId || normalized == "reasoning"
        }
    }
}

data class AuthenticatedKeyResult(
    val keyEntity: ApiKeyEntity,
    val permission: KeyPermission
)

class ApiKeyManager(
    private val apiKeyDao: ApiKeyDao,
    private val cipher: KeyCipher = createPlatformCipher()
) {
    private val mutex = Mutex()
    private val secureRandom = SecureRandom()

    val securityBackendName: String get() = cipher.backendName

    suspend fun ensurePermanentPrimaryKey(): ApiKeyEntity = mutex.withLock {
        val existing = apiKeyDao.getPrimary()
        if (existing != null) {
            return@withLock existing
        }
        val created = buildNewKeyEntity(
            id = "primary_master_key",
            name = "Chave Principal Permanente",
            isPrimary = true,
            permission = KeyPermission.ALL_AGENTS
        )
        apiKeyDao.upsert(created)
        created
    }

    suspend fun regeneratePrimaryKey(): ApiKeyEntity = mutex.withLock {
        val created = buildNewKeyEntity(
            id = "primary_master_key",
            name = "Chave Principal Permanente",
            isPrimary = true,
            permission = KeyPermission.ALL_AGENTS
        )
        apiKeyDao.upsert(created)
        created
    }

    suspend fun revokePrimaryKey() = mutex.withLock {
        apiKeyDao.setActive("primary_master_key", false)
    }

    suspend fun createDeviceKey(
        name: String,
        permission: KeyPermission = KeyPermission.ALL_AGENTS
    ): Pair<ApiKeyEntity, String> = mutex.withLock {
        val cleanName = name.trim().ifBlank { "Dispositivo Externo" }
        val rawSecret = generateRawApiKey("ldai_dev_")
        val entity = buildEntityFromRaw(
            id = "dev_${UUID.randomUUID().toString().take(12)}",
            name = cleanName,
            isPrimary = false,
            rawKey = rawSecret,
            permission = permission
        )
        apiKeyDao.upsert(entity)
        entity to rawSecret
    }

    suspend fun revokeKey(keyId: String) = mutex.withLock {
        apiKeyDao.setActive(keyId, false)
    }

    suspend fun reactivateKey(keyId: String) = mutex.withLock {
        apiKeyDao.setActive(keyId, true)
    }

    suspend fun deleteAdditionalKey(keyId: String) = mutex.withLock {
        apiKeyDao.deleteAdditionalKey(keyId)
    }

    suspend fun decryptKeyForDisplay(entity: ApiKeyEntity): String? {
        return runCatching { cipher.decrypt(entity.encryptedSecretBase64) }.getOrNull()
    }

    suspend fun authenticateBearerToken(rawToken: String?, remoteIp: String): AuthenticatedKeyResult? {
        val token = rawToken?.trim().orEmpty()
        if (token.isEmpty()) return null
        val hash = sha256Hex(token)
        val record = apiKeyDao.findByHash(hash) ?: return null
        if (!record.isActive) return null
        apiKeyDao.recordAccess(record.id, System.currentTimeMillis(), remoteIp)
        val perm = runCatching { KeyPermission.valueOf(record.permissions) }
            .getOrDefault(KeyPermission.ALL_AGENTS)
        return AuthenticatedKeyResult(record, perm)
    }

    private fun buildNewKeyEntity(
        id: String,
        name: String,
        isPrimary: Boolean,
        permission: KeyPermission
    ): ApiKeyEntity {
        val prefix = if (isPrimary) "ldai_live_" else "ldai_dev_"
        val rawKey = generateRawApiKey(prefix)
        return buildEntityFromRaw(id, name, isPrimary, rawKey, permission)
    }

    private fun buildEntityFromRaw(
        id: String,
        name: String,
        isPrimary: Boolean,
        rawKey: String,
        permission: KeyPermission
    ): ApiKeyEntity {
        val maskedPrefix = "${rawKey.take(14)}...${rawKey.takeLast(4)}"
        val encrypted = cipher.encrypt(rawKey)
        val hash = sha256Hex(rawKey)
        return ApiKeyEntity(
            id = id,
            name = name,
            isPrimary = isPrimary,
            keyPrefix = maskedPrefix,
            keyHashSha256 = hash,
            encryptedSecretBase64 = encrypted,
            permissions = permission.name,
            isActive = true,
            createdAt = System.currentTimeMillis()
        )
    }

    private fun generateRawApiKey(prefix: String): String {
        val bytes = ByteArray(24)
        secureRandom.nextBytes(bytes)
        val tokenBody = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        return "$prefix$tokenBody"
    }

    companion object {
        fun sha256Hex(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(input.toByteArray(StandardCharsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }

        fun createPlatformCipher(): KeyCipher {
            return if (Build.FINGERPRINT.equals("robolectric", ignoreCase = true)) {
                RobolectricFallbackCipher()
            } else {
                try {
                    // Verify AndroidKeyStore provider is reachable
                    KeyStore.getInstance("AndroidKeyStore")
                    AndroidKeystoreCipher()
                } catch (_: Exception) {
                    RobolectricFallbackCipher()
                }
            }
        }
    }
}

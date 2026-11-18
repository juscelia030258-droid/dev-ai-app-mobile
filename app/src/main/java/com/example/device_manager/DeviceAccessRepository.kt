package com.example.device_manager

import com.example.monitoring.data.ApiKeyEntity
import com.example.monitoring.data.ConnectedDeviceDao
import com.example.monitoring.data.ConnectedDeviceEntity
import kotlinx.coroutines.flow.Flow

class DeviceAccessRepository(
    private val connectedDeviceDao: ConnectedDeviceDao
) {
    val devicesFlow: Flow<List<ConnectedDeviceEntity>> = connectedDeviceDao.observeAll()

    suspend fun isDeviceBlocked(remoteIp: String, userAgent: String, keyId: String): Boolean {
        val fp = buildFingerprint(remoteIp, userAgent, keyId)
        return connectedDeviceDao.getByFingerprint(fp)?.isBlocked == true
    }

    suspend fun recordDeviceActivity(
        remoteIp: String,
        userAgent: String,
        key: ApiKeyEntity
    ) {
        val cleanUa = userAgent.trim().ifBlank { "HTTP Client" }.take(80)
        val clientLabel = inferClientLabel(cleanUa, key.name)
        val fp = buildFingerprint(remoteIp, cleanUa, key.id)
        val existing = connectedDeviceDao.getByFingerprint(fp)
        val now = System.currentTimeMillis()
        if (existing != null) {
            connectedDeviceDao.upsert(
                existing.copy(
                    keyName = key.name,
                    lastSeenAt = now,
                    totalRequests = existing.totalRequests + 1
                )
            )
        } else {
            connectedDeviceDao.upsert(
                ConnectedDeviceEntity(
                    deviceFingerprint = fp,
                    keyId = key.id,
                    keyName = key.name,
                    clientName = clientLabel,
                    ipAddress = remoteIp,
                    userAgent = cleanUa,
                    firstSeenAt = now,
                    lastSeenAt = now,
                    totalRequests = 1,
                    isBlocked = false
                )
            )
        }
    }

    suspend fun setDeviceBlocked(fingerprint: String, blocked: Boolean) {
        connectedDeviceDao.setBlocked(fingerprint, blocked)
    }

    private fun buildFingerprint(ip: String, userAgent: String, keyId: String): String {
        return "${ip}_${keyId}_${userAgent.hashCode()}"
    }

    private fun inferClientLabel(userAgent: String, keyName: String): String {
        return when {
            userAgent.contains("Qoder", ignoreCase = true) -> "Qoder IDE ($keyName)"
            userAgent.contains("OpenAI", ignoreCase = true) -> "OpenAI SDK ($keyName)"
            userAgent.contains("curl", ignoreCase = true) -> "cURL CLI ($keyName)"
            userAgent.contains("Macintosh", ignoreCase = true) || userAgent.contains("Darwin", ignoreCase = true) -> "MacBook ($keyName)"
            else -> "$keyName ($userAgent)"
        }.take(64)
    }
}

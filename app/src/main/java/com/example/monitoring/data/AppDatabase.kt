package com.example.monitoring.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "local_models")
data class LocalModelEntity(
    @PrimaryKey val id: String,
    val name: String,
    val filePath: String,
    val mmprojPath: String? = null,
    val format: String,
    val runtimeBackend: String,
    val sizeBytes: Long,
    val estimatedRamMb: Long,
    val supportsVision: Boolean,
    val quantization: String,
    val compatibilityStatus: String,
    val compatibilityDetail: String,
    val sha256: String? = null,
    val importedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "agent_configs")
data class AgentConfigEntity(
    @PrimaryKey val roleId: String,
    val enabled: Boolean = true,
    val assignedModelId: String? = null,
    val runtimeBackend: String = "LLAMA_CPP",
    val contextLength: Int = 4096,
    val maxTokens: Int = 1024,
    val temperature: Float = 0.25f,
    val topP: Float = 0.92f,
    val topK: Int = 40,
    val threads: Int = 4,
    val systemPromptOverride: String? = null
)

@Entity(tableName = "agent_history")
data class AgentHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val roleId: String,
    val source: String, // "UI" or "API"
    val modelIdUsed: String,
    val promptPreview: String,
    val responsePreview: String,
    val hasImage: Boolean = false,
    val durationMs: Long = 0,
    val success: Boolean = true,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "multi_agent_runs")
data class MultiAgentRunEntity(
    @PrimaryKey val taskId: String,
    val originalRequest: String,
    val source: String, // "UI" or "API"
    val status: String, // "RUNNING", "COMPLETED", "FAILED", "ABORTED_TIMEOUT"
    val planJson: String,
    val stepsJson: String,
    val revisionCount: Int = 0,
    val finalResponse: String = "",
    val errorMessage: String? = null,
    val totalDurationMs: Long = 0,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "api_keys")
data class ApiKeyEntity(
    @PrimaryKey val id: String,
    val name: String,
    val isPrimary: Boolean,
    val keyPrefix: String,
    val keyHashSha256: String,
    val encryptedSecretBase64: String,
    val permissions: String, // "ALL_AGENTS", "CODING_ONLY", "VISION_ONLY", "REASONING_ONLY"
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val lastAccessAt: Long? = null,
    val lastClientIp: String? = null,
    val requestCount: Long = 0
)

@Entity(tableName = "connected_devices")
data class ConnectedDeviceEntity(
    @PrimaryKey val deviceFingerprint: String,
    val keyId: String,
    val keyName: String,
    val clientName: String,
    val ipAddress: String,
    val userAgent: String,
    val firstSeenAt: Long = System.currentTimeMillis(),
    val lastSeenAt: Long = System.currentTimeMillis(),
    val totalRequests: Long = 1,
    val isBlocked: Boolean = false
)

@Entity(tableName = "api_request_logs")
data class ApiRequestLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val method: String,
    val path: String,
    val modelRequested: String?,
    val remoteIp: String,
    val keyName: String?,
    val statusCode: Int,
    val latencyMs: Long,
    val streaming: Boolean,
    val summary: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Dao
interface LocalModelDao {
    @Query("SELECT * FROM local_models ORDER BY importedAt DESC")
    fun observeAll(): Flow<List<LocalModelEntity>>

    @Query("SELECT * FROM local_models")
    suspend fun getAllOnce(): List<LocalModelEntity>

    @Query("SELECT * FROM local_models WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): LocalModelEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(model: LocalModelEntity)

    @Query("DELETE FROM local_models WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface AgentConfigDao {
    @Query("SELECT * FROM agent_configs")
    fun observeAll(): Flow<List<AgentConfigEntity>>

    @Query("SELECT * FROM agent_configs WHERE roleId = :roleId LIMIT 1")
    suspend fun getByRole(roleId: String): AgentConfigEntity?

    @Query("SELECT * FROM agent_configs")
    suspend fun getAllOnce(): List<AgentConfigEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(config: AgentConfigEntity)
}

@Dao
interface AgentHistoryDao {
    @Query("SELECT * FROM agent_history ORDER BY createdAt DESC LIMIT 100")
    fun observeRecent(): Flow<List<AgentHistoryEntity>>

    @Query("SELECT * FROM agent_history WHERE roleId = :roleId ORDER BY createdAt DESC LIMIT 30")
    fun observeByRole(roleId: String): Flow<List<AgentHistoryEntity>>

    @Insert
    suspend fun insert(entry: AgentHistoryEntity)

    @Query("DELETE FROM agent_history")
    suspend fun clearAll()
}

@Dao
interface MultiAgentRunDao {
    @Query("SELECT * FROM multi_agent_runs ORDER BY createdAt DESC LIMIT 40")
    fun observeRecent(): Flow<List<MultiAgentRunEntity>>

    @Query("SELECT * FROM multi_agent_runs WHERE taskId = :taskId LIMIT 1")
    suspend fun getById(taskId: String): MultiAgentRunEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(run: MultiAgentRunEntity)
}

@Dao
interface ApiKeyDao {
    @Query("SELECT * FROM api_keys ORDER BY isPrimary DESC, createdAt ASC")
    fun observeAll(): Flow<List<ApiKeyEntity>>

    @Query("SELECT * FROM api_keys")
    suspend fun getAllOnce(): List<ApiKeyEntity>

    @Query("SELECT * FROM api_keys WHERE isPrimary = 1 LIMIT 1")
    suspend fun getPrimary(): ApiKeyEntity?

    @Query("SELECT * FROM api_keys WHERE keyHashSha256 = :hash LIMIT 1")
    suspend fun findByHash(hash: String): ApiKeyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(key: ApiKeyEntity)

    @Query("UPDATE api_keys SET isActive = :active WHERE id = :id")
    suspend fun setActive(id: String, active: Boolean)

    @Query("UPDATE api_keys SET lastAccessAt = :timestamp, lastClientIp = :ip, requestCount = requestCount + 1 WHERE id = :id")
    suspend fun recordAccess(id: String, timestamp: Long, ip: String)

    @Query("DELETE FROM api_keys WHERE id = :id AND isPrimary = 0")
    suspend fun deleteAdditionalKey(id: String)
}

@Dao
interface ConnectedDeviceDao {
    @Query("SELECT * FROM connected_devices ORDER BY lastSeenAt DESC")
    fun observeAll(): Flow<List<ConnectedDeviceEntity>>

    @Query("SELECT * FROM connected_devices WHERE deviceFingerprint = :fp LIMIT 1")
    suspend fun getByFingerprint(fp: String): ConnectedDeviceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(device: ConnectedDeviceEntity)

    @Query("UPDATE connected_devices SET isBlocked = :blocked WHERE deviceFingerprint = :fp")
    suspend fun setBlocked(fp: String, blocked: Boolean)
}

@Dao
interface ApiRequestLogDao {
    @Query("SELECT * FROM api_request_logs ORDER BY timestamp DESC LIMIT 150")
    fun observeRecent(): Flow<List<ApiRequestLogEntity>>

    @Insert
    suspend fun insert(log: ApiRequestLogEntity)

    @Query("DELETE FROM api_request_logs")
    suspend fun clearAll()
}

@Database(
    entities = [
        LocalModelEntity::class,
        AgentConfigEntity::class,
        AgentHistoryEntity::class,
        MultiAgentRunEntity::class,
        ApiKeyEntity::class,
        ConnectedDeviceEntity::class,
        ApiRequestLogEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun localModelDao(): LocalModelDao
    abstract fun agentConfigDao(): AgentConfigDao
    abstract fun agentHistoryDao(): AgentHistoryDao
    abstract fun multiAgentRunDao(): MultiAgentRunDao
    abstract fun apiKeyDao(): ApiKeyDao
    abstract fun connectedDeviceDao(): ConnectedDeviceDao
    abstract fun apiRequestLogDao(): ApiRequestLogDao
}

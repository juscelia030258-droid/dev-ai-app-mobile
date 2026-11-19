package com.example.model_manager

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.core.model.AgentRole
import com.example.core.model.CompatibilityStatus
import com.example.core.model.DownloadProgressState
import com.example.core.model.ModelFormat
import com.example.core.model.RecommendedModelPreset
import com.example.core.model.RuntimeBackend
import com.example.inference.ModelValidator
import com.example.memory.MemoryBudgetManager
import com.example.monitoring.data.AgentConfigDao
import com.example.monitoring.data.AgentConfigEntity
import com.example.monitoring.data.LocalModelDao
import com.example.monitoring.data.LocalModelEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

class ModelRepository(
    private val context: Context,
    private val localModelDao: LocalModelDao,
    private val agentConfigDao: AgentConfigDao,
    private val memoryBudgetManager: MemoryBudgetManager
) {
    private val modelsDir: File = File(context.filesDir, "models").apply { mkdirs() }

    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val _downloadState = MutableStateFlow<DownloadProgressState?>(null)
    val downloadState: StateFlow<DownloadProgressState?> = _downloadState.asStateFlow()

    @Volatile
    private var activeCall: Call? = null
    @Volatile
    private var downloadCancelled: Boolean = false

    val modelsFlow: Flow<List<LocalModelEntity>> = localModelDao.observeAll()

    val recommendedPresets: List<RecommendedModelPreset> = listOf(
        RecommendedModelPreset(
            id = "qwen2_5_coder_3b_q4_k_m",
            name = "Qwen2.5-Coder 3B Instruct (Q4_K_M)",
            recommendedRole = AgentRole.CODING,
            format = ModelFormat.GGUF,
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-Coder-3B-Instruct-GGUF/resolve/main/qwen2.5-coder-3b-instruct-q4_k_m.gguf",
            estimatedSizeMb = 2100L,
            estimatedRamMb = 2900L,
            supportsVision = false,
            quantization = "Q4_K_M",
            notes = "Modelo especializado em programação; contexto adaptativo e execução sequencial recomendados em celulares."
        ),
        RecommendedModelPreset(
            id = "smolvlm_500m_vision",
            name = "SmolVLM 500M Instruct (GGUF + mmproj)",
            recommendedRole = AgentRole.VISION,
            format = ModelFormat.GGUF,
            downloadUrl = "https://huggingface.co/ggml-org/SmolVLM-500M-Instruct-GGUF/resolve/main/SmolVLM-500M-Instruct-Q8_0.gguf",
            mmprojDownloadUrl = "https://huggingface.co/ggml-org/SmolVLM-500M-Instruct-GGUF/resolve/main/mmproj-SmolVLM-500M-Instruct-Q8_0.gguf",
            estimatedSizeMb = 620L,
            estimatedRamMb = 1150L,
            supportsVision = true,
            quantization = "Q8_0",
            notes = "Visão multimodal real com projetor mmproj para análise de capturas de tela e UI."
        ),
        RecommendedModelPreset(
            id = "phi4_mini_instruct_q4_k_m",
            name = "Phi-4-mini-instruct (Q4_K_M)",
            recommendedRole = AgentRole.REASONING,
            format = ModelFormat.GGUF,
            downloadUrl = "https://huggingface.co/tensorblock/Phi-4-mini-instruct-GGUF/resolve/main/Phi-4-mini-instruct-Q4_K_M.gguf",
            estimatedSizeMb = 2492L,
            estimatedRamMb = 3300L,
            supportsVision = false,
            quantization = "Q4_K_M",
            notes = "Modelo geral para raciocínio e planejamento; contexto adaptativo e execução sequencial recomendados em celulares."
        ),
        RecommendedModelPreset(
            id = "qwen3_0_6b_litert",
            name = "Qwen3 0.6B (LiteRT-LM)",
            recommendedRole = AgentRole.REASONING,
            format = ModelFormat.LITERT_LM,
            downloadUrl = "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B.litertlm",
            estimatedSizeMb = 610L,
            estimatedRamMb = 1050L,
            supportsVision = false,
            quantization = "INT4/LiteRT",
            notes = "Runtime oficial Google AI Edge LiteRT-LM para execução ultraleve em CPU/GPU."
        ),
        RecommendedModelPreset(
            id = "gemma4_e2b_litert",
            name = "Gemma 4 E2B IT (LiteRT-LM)",
            recommendedRole = AgentRole.CODING,
            format = ModelFormat.LITERT_LM,
            downloadUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
            estimatedSizeMb = 2600L,
            estimatedRamMb = 3350L,
            supportsVision = true,
            quantization = "INT4/LiteRT",
            notes = "Modelo Google AI Edge de alta capacidade para Galaxy A56 (execução sequencial recomendada)."
        )
    )

    suspend fun importModelFromUri(
        modelUri: Uri,
        mmprojUri: Uri? = null,
        customName: String? = null,
        assignToRole: AgentRole? = null,
        supportsVisionHint: Boolean = false
    ): Result<LocalModelEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val originalName = resolveDisplayName(modelUri) ?: "modelo_${System.currentTimeMillis()}.gguf"
            val sanitizedName = originalName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val destFile = File(modelsDir, "${UUID.randomUUID().toString().take(6)}_$sanitizedName")

            val sha256 = copyUriToFile(modelUri, destFile)

            var mmprojDest: File? = null
            if (mmprojUri != null) {
                val projOrig = resolveDisplayName(mmprojUri) ?: "mmproj_${System.currentTimeMillis()}.gguf"
                val projSanitized = projOrig.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                mmprojDest = File(modelsDir, "${UUID.randomUUID().toString().take(6)}_$projSanitized")
                copyUriToFile(mmprojUri, mmprojDest)
            }

            val budgetMb = memoryBudgetManager.state.value.userConfiguredRamLimitMb
            val validation = ModelValidator.validateModelFile(
                file = destFile,
                mmprojFile = mmprojDest,
                safeRamBudgetMb = budgetMb,
                supportsVisionFlag = supportsVisionHint || mmprojDest != null
            )

            if (validation.status == CompatibilityStatus.CORRUPTED_OR_INVALID) {
                destFile.delete()
                mmprojDest?.delete()
                throw IllegalArgumentException(validation.detail)
            }

            val entity = LocalModelEntity(
                id = "mdl_${UUID.randomUUID().toString().take(10)}",
                name = customName?.takeIf { it.isNotBlank() } ?: originalName.substringBeforeLast('.'),
                filePath = destFile.absolutePath,
                mmprojPath = mmprojDest?.absolutePath,
                format = validation.format.name,
                runtimeBackend = validation.recommendedRuntime.name,
                sizeBytes = validation.sizeBytes,
                estimatedRamMb = validation.estimatedRamMb,
                supportsVision = validation.supportsVision,
                quantization = validation.quantizationHint,
                compatibilityStatus = validation.status.name,
                compatibilityDetail = validation.detail,
                sha256 = sha256
            )
            localModelDao.upsert(entity)
            if (assignToRole != null) {
                assignModelToAgent(assignToRole, entity.id, validation.recommendedRuntime)
            }
            entity
        }
    }

    suspend fun downloadModelFromUrl(
        presetId: String,
        displayName: String,
        url: String,
        mmprojUrl: String? = null,
        assignToRole: AgentRole? = null,
        supportsVision: Boolean = false
    ): Result<LocalModelEntity> = withContext(Dispatchers.IO) {
        downloadCancelled = false
        var destFile: File? = null
        var mmprojFile: File? = null
        runCatching {
            val fileName = url.substringAfterLast('/').substringBefore('?')
                .ifBlank { "${presetId}.gguf" }
                .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val targetModelFile = File(modelsDir, "${UUID.randomUUID().toString().take(6)}_$fileName")
            destFile = targetModelFile

            val sha = downloadSingleFile(presetId, fileName, url, targetModelFile)

            if (!mmprojUrl.isNullOrBlank()) {
                val projName = mmprojUrl.substringAfterLast('/').substringBefore('?')
                    .ifBlank { "${presetId}_mmproj.gguf" }
                    .replace(Regex("[^a-zA-Z0-9._-]"), "_")
                val targetProjFile = File(modelsDir, "${UUID.randomUUID().toString().take(6)}_$projName")
                mmprojFile = targetProjFile
                downloadSingleFile(presetId, projName, mmprojUrl, targetProjFile)
            }

            val budgetMb = memoryBudgetManager.state.value.userConfiguredRamLimitMb
            val validation = ModelValidator.validateModelFile(
                file = targetModelFile,
                mmprojFile = mmprojFile,
                safeRamBudgetMb = budgetMb,
                supportsVisionFlag = supportsVision || mmprojFile != null
            )

            if (validation.status == CompatibilityStatus.CORRUPTED_OR_INVALID) {
                targetModelFile.delete()
                mmprojFile?.delete()
                throw IllegalArgumentException(validation.detail)
            }

            val entity = LocalModelEntity(
                id = "mdl_${UUID.randomUUID().toString().take(10)}",
                name = displayName,
                filePath = targetModelFile.absolutePath,
                mmprojPath = mmprojFile?.absolutePath,
                format = validation.format.name,
                runtimeBackend = validation.recommendedRuntime.name,
                sizeBytes = validation.sizeBytes,
                estimatedRamMb = validation.estimatedRamMb,
                supportsVision = validation.supportsVision,
                quantization = validation.quantizationHint,
                compatibilityStatus = validation.status.name,
                compatibilityDetail = validation.detail,
                sha256 = sha
            )
            localModelDao.upsert(entity)
            if (assignToRole != null) {
                assignModelToAgent(assignToRole, entity.id, validation.recommendedRuntime)
            }
            _downloadState.value = null
            entity
        }.onFailure { err ->
            destFile?.delete()
            mmprojFile?.delete()
            if (downloadCancelled) {
                _downloadState.value = null
            } else {
                _downloadState.value = _downloadState.value?.copy(
                    isDownloading = false,
                    error = err.message ?: "Falha no download"
                )
            }
        }
    }

    fun cancelDownload() {
        downloadCancelled = true
        runCatching { activeCall?.cancel() }
        activeCall = null
        _downloadState.value = null
    }

    fun clearDownloadStatus() {
        _downloadState.value = null
    }

    suspend fun attachMmprojToModel(modelId: String, mmprojUri: Uri): Result<LocalModelEntity> =
        withContext(Dispatchers.IO) {
            runCatching {
                val existing = localModelDao.getById(modelId)
                    ?: throw IllegalStateException("Modelo não encontrado.")
                val projName = resolveDisplayName(mmprojUri) ?: "mmproj.gguf"
                val destProj = File(modelsDir, "${UUID.randomUUID().toString().take(6)}_${projName.replace(Regex("[^a-zA-Z0-9._-]"), "_")}")
                copyUriToFile(mmprojUri, destProj)
                existing.mmprojPath?.let { runCatching { File(it).delete() } }

                val budgetMb = memoryBudgetManager.state.value.userConfiguredRamLimitMb
                val validation = ModelValidator.validateModelFile(
                    file = File(existing.filePath),
                    mmprojFile = destProj,
                    safeRamBudgetMb = budgetMb,
                    supportsVisionFlag = true
                )
                val updated = existing.copy(
                    mmprojPath = destProj.absolutePath,
                    supportsVision = validation.supportsVision,
                    estimatedRamMb = validation.estimatedRamMb,
                    compatibilityStatus = validation.status.name,
                    compatibilityDetail = validation.detail
                )
                localModelDao.upsert(updated)
                updated
            }
        }

    suspend fun revalidateAllModels() = withContext(Dispatchers.IO) {
        val budgetMb = memoryBudgetManager.state.value.userConfiguredRamLimitMb
        localModelDao.getAllOnce().forEach { entity ->
            val validation = ModelValidator.validateModelFile(
                file = File(entity.filePath),
                mmprojFile = entity.mmprojPath?.let { File(it) },
                safeRamBudgetMb = budgetMb,
                supportsVisionFlag = entity.supportsVision
            )
            localModelDao.upsert(
                entity.copy(
                    estimatedRamMb = validation.estimatedRamMb,
                    compatibilityStatus = validation.status.name,
                    compatibilityDetail = validation.detail
                )
            )
        }
    }

    suspend fun assignModelToAgent(
        role: AgentRole,
        modelId: String?,
        preferredRuntime: RuntimeBackend? = null
    ) {
        val current = agentConfigDao.getByRole(role.id) ?: AgentConfigEntity(roleId = role.id)
        val model = modelId?.let { localModelDao.getById(it) }
        val resolvedRuntime = preferredRuntime?.name ?: model?.runtimeBackend ?: current.runtimeBackend
        agentConfigDao.upsert(
            current.copy(
                assignedModelId = modelId,
                runtimeBackend = resolvedRuntime
            )
        )
    }

    suspend fun deleteModel(modelId: String) = withContext(Dispatchers.IO) {
        val existing = localModelDao.getById(modelId) ?: return@withContext
        runCatching { File(existing.filePath).delete() }
        existing.mmprojPath?.let { runCatching { File(it).delete() } }
        agentConfigDao.getAllOnce().forEach { cfg ->
            if (cfg.assignedModelId == modelId) {
                agentConfigDao.upsert(cfg.copy(assignedModelId = null))
            }
        }
        localModelDao.deleteById(modelId)
    }

    private fun downloadSingleFile(
        presetId: String,
        fileName: String,
        url: String,
        destFile: File
    ): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "LocalDevAI-Android/1.0")
            .build()

        downloadClient.newCall(request).also { activeCall = it }.execute().use { response ->
            if (!response.isSuccessful) {
                destFile.delete()
                throw IllegalStateException("HTTP ${response.code} ao baixar $fileName")
            }
            val body = response.body ?: throw IllegalStateException("Resposta vazia ao baixar $fileName")
            val totalBytes = body.contentLength()
            val digest = MessageDigest.getInstance("SHA-256")
            var bytesRead = 0L

            _downloadState.value = DownloadProgressState(
                presetId = presetId,
                fileName = fileName,
                bytesRead = 0L,
                totalBytes = totalBytes,
                progressPercent = 0,
                isDownloading = true
            )

            body.byteStream().use { input ->
                FileOutputStream(destFile).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var lastEmit = 0L
                    while (!downloadCancelled) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        bytesRead += read
                        val now = System.currentTimeMillis()
                        if (now - lastEmit > 250L) {
                            lastEmit = now
                            val pct = if (totalBytes > 0L) ((bytesRead * 100L) / totalBytes).toInt().coerceIn(0, 100) else 0
                            _downloadState.value = DownloadProgressState(
                                presetId = presetId,
                                fileName = fileName,
                                bytesRead = bytesRead,
                                totalBytes = totalBytes,
                                progressPercent = pct,
                                isDownloading = true
                            )
                        }
                    }
                    output.flush()
                }
            }
            activeCall = null
            if (downloadCancelled) {
                destFile.delete()
                throw IllegalStateException("Download cancelado pelo usuário.")
            }
            // A successful HTTP status does not guarantee the entire model arrived.
            // Reject truncated files whenever the server supplied a Content-Length.
            if (totalBytes > 0L && bytesRead != totalBytes) {
                destFile.delete()
                throw IllegalStateException(
                    "Download incompleto de $fileName: recebidos $bytesRead de $totalBytes bytes."
                )
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }

    private fun copyUriToFile(uri: Uri, destFile: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Não foi possível abrir o arquivo selecionado.")
        input.use { stream ->
            FileOutputStream(destFile).use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val read = stream.read(buf)
                    if (read == -1) break
                    out.write(buf, 0, read)
                    digest.update(buf, 0, read)
                }
                out.flush()
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun resolveDisplayName(uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return cursor.getString(idx)
            }
        }
        return uri.lastPathSegment
    }
}

package com.example.inference

import com.example.core.model.CompatibilityStatus
import com.example.core.model.ModelFormat
import com.example.core.model.RuntimeBackend
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class ModelValidationResult(
    val format: ModelFormat,
    val recommendedRuntime: RuntimeBackend,
    val sizeBytes: Long,
    val estimatedRamMb: Long,
    val quantizationHint: String,
    val supportsVision: Boolean,
    val status: CompatibilityStatus,
    val detail: String
)

object ModelValidator {

    private const val GGUF_MAGIC = 0x46554747 // "GGUF" little-endian

    fun validateModelFile(
        file: File,
        mmprojFile: File?,
        safeRamBudgetMb: Long,
        supportsVisionFlag: Boolean = false
    ): ModelValidationResult {
        if (!file.exists() || !file.isFile || file.length() < 64L) {
            return ModelValidationResult(
                format = ModelFormat.UNKNOWN,
                recommendedRuntime = RuntimeBackend.LLAMA_CPP,
                sizeBytes = 0L,
                estimatedRamMb = 0L,
                quantizationHint = "N/A",
                supportsVision = false,
                status = CompatibilityStatus.CORRUPTED_OR_INVALID,
                detail = "Arquivo inexistente ou corrompido (< 64 bytes)."
            )
        }

        val sizeBytes = file.length()
        val ext = file.extension.lowercase()
        val header = ByteArray(16)
        runCatching {
            RandomAccessFile(file, "r").use { raf ->
                raf.readFully(header)
            }
        }.onFailure {
            return ModelValidationResult(
                format = ModelFormat.UNKNOWN,
                recommendedRuntime = RuntimeBackend.LLAMA_CPP,
                sizeBytes = sizeBytes,
                estimatedRamMb = 0L,
                quantizationHint = "N/A",
                supportsVision = false,
                status = CompatibilityStatus.CORRUPTED_OR_INVALID,
                detail = "Erro ao ler cabeçalho do arquivo: ${it.message}"
            )
        }

        val magic = ByteBuffer.wrap(header, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val isGguf = magic == GGUF_MAGIC || ext == "gguf"
        val isLiteRtLm = ext == "litertlm" || ext == "task" || String(header, 0, 8).contains("TFL", ignoreCase = true)

        val format = when {
            magic == GGUF_MAGIC -> ModelFormat.GGUF
            ext == "gguf" -> ModelFormat.GGUF
            isLiteRtLm -> ModelFormat.LITERT_LM
            else -> ModelFormat.UNKNOWN
        }

        if (format == ModelFormat.UNKNOWN) {
            return ModelValidationResult(
                format = ModelFormat.UNKNOWN,
                recommendedRuntime = RuntimeBackend.LLAMA_CPP,
                sizeBytes = sizeBytes,
                estimatedRamMb = 0L,
                quantizationHint = "Desconhecido",
                supportsVision = false,
                status = CompatibilityStatus.CORRUPTED_OR_INVALID,
                detail = "Formato não suportado. Use modelos .gguf (llama.cpp) ou .litertlm (LiteRT-LM)."
            )
        }

        if (format == ModelFormat.GGUF && magic != GGUF_MAGIC) {
            return ModelValidationResult(
                format = ModelFormat.GGUF,
                recommendedRuntime = RuntimeBackend.LLAMA_CPP,
                sizeBytes = sizeBytes,
                estimatedRamMb = 0L,
                quantizationHint = "GGUF inválido",
                supportsVision = false,
                status = CompatibilityStatus.CORRUPTED_OR_INVALID,
                detail = "Extensão .gguf sem assinatura mágica GGUF válida no cabeçalho."
            )
        }

        val ggufVersion = if (format == ModelFormat.GGUF) {
            ByteBuffer.wrap(header, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        } else 0

        val weightsMb = (sizeBytes / (1024L * 1024L)).coerceAtLeast(32L)
        val mmprojMb = if (mmprojFile != null && mmprojFile.exists()) {
            (mmprojFile.length() / (1024L * 1024L))
        } else 0L
        val estimatedRamMb = (weightsMb * 1.15).toLong() + mmprojMb + 384L

        val quantizationHint = extractQuantHint(file.name)
        val hasValidMmproj = mmprojFile != null && mmprojFile.exists() && mmprojFile.length() > 1024L
        val visionReady = when (format) {
            ModelFormat.GGUF -> hasValidMmproj || supportsVisionFlag && hasValidMmproj
            ModelFormat.LITERT_LM -> supportsVisionFlag || file.name.contains("vision", true) || file.name.contains("e2b", true) || file.name.contains("vlm", true)
            ModelFormat.UNKNOWN -> false
        }

        val recommendedRuntime = when (format) {
            ModelFormat.GGUF -> RuntimeBackend.LLAMA_CPP
            ModelFormat.LITERT_LM -> RuntimeBackend.LITERT_LM_CPU
            ModelFormat.UNKNOWN -> RuntimeBackend.LLAMA_CPP
        }

        val status = when {
            estimatedRamMb > safeRamBudgetMb -> CompatibilityStatus.INCOMPATIBLE_MEMORY
            format == ModelFormat.GGUF && supportsVisionFlag && !hasValidMmproj ->
                CompatibilityStatus.MISSING_VISION_PROJECTOR
            estimatedRamMb > (safeRamBudgetMb * 0.85).toLong() ->
                CompatibilityStatus.HIGH_MEMORY_WARNING
            else -> CompatibilityStatus.COMPATIBLE
        }

        val detail = buildString {
            when (format) {
                ModelFormat.GGUF -> append("GGUF v$ggufVersion ($quantizationHint)")
                ModelFormat.LITERT_LM -> append("LiteRT-LM ($quantizationHint)")
                ModelFormat.UNKNOWN -> append("Desconhecido")
            }
            append(" • ~${estimatedRamMb} MB RAM estimados")
            if (visionReady) append(" • Multimodal Visão ativo")
            if (status == CompatibilityStatus.INCOMPATIBLE_MEMORY) {
                append(" • Excede orçamento seguro (${safeRamBudgetMb} MB)")
            } else if (status == CompatibilityStatus.MISSING_VISION_PROJECTOR) {
                append(" • Falta arquivo mmproj*.gguf")
            }
        }

        return ModelValidationResult(
            format = format,
            recommendedRuntime = recommendedRuntime,
            sizeBytes = sizeBytes,
            estimatedRamMb = estimatedRamMb,
            quantizationHint = quantizationHint,
            supportsVision = visionReady,
            status = status,
            detail = detail
        )
    }

    private fun extractQuantHint(fileName: String): String {
        val upper = fileName.uppercase()
        val known = listOf("Q4_K_M", "Q4_K_S", "Q5_K_M", "Q6_K", "Q8_0", "Q4_0", "IQ4_XS", "INT4", "INT8", "FP16")
        return known.firstOrNull { upper.contains(it) } ?: "ARM64"
    }
}

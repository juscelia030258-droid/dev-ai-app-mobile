package com.example.core.model

import kotlinx.serialization.Serializable

enum class AgentRole(
    val id: String,
    val apiModelId: String,
    val displayName: String,
    val specialty: String,
) {
    CODING(
        id = "coding",
        apiModelId = "local-dev-coding",
        displayName = "Coding AI",
        specialty = "Geração, análise, refatoração e depuração de código"
    ),
    VISION(
        id = "vision",
        apiModelId = "local-dev-vision",
        displayName = "Vision AI",
        specialty = "Visão computacional multimodal para imagens e UI"
    ),
    REASONING(
        id = "reasoning",
        apiModelId = "local-dev-reasoning",
        displayName = "Reasoning AI",
        specialty = "Planejamento, raciocínio lógico e coordenação"
    );

    companion object {
        const val MULTIAGENT_MODEL_ID = "local-dev-multiagent"

        fun fromApiModelId(modelId: String?): AgentRole? {
            return when (modelId?.trim()?.lowercase()) {
                "local-dev-coding", "coding" -> CODING
                "local-dev-vision", "vision" -> VISION
                "local-dev-reasoning", "reasoning" -> REASONING
                else -> null
            }
        }
    }
}

enum class RuntimeBackend(val label: String) {
    LLAMA_CPP("llama.cpp (GGUF ARM64)"),
    LITERT_LM_CPU("LiteRT-LM (CPU)"),
    LITERT_LM_GPU("LiteRT-LM (GPU OpenCL)")
}

enum class ModelFormat(val extension: String) {
    GGUF("gguf"),
    LITERT_LM("litertlm"),
    UNKNOWN("unknown")
}

enum class CompatibilityStatus(val label: String) {
    COMPATIBLE("Compatível com ARM64"),
    HIGH_MEMORY_WARNING("Alto consumo de RAM"),
    INCOMPATIBLE_MEMORY("Excede limite de RAM"),
    CORRUPTED_OR_INVALID("Arquivo inválido ou ausente"),
    MISSING_VISION_PROJECTOR("Requer arquivo mmproj para visão")
}

enum class AgentRuntimeState {
    IDLE,
    LOADING_MODEL,
    RUNNING,
    UNLOADING,
    DISABLED,
    ERROR
}

@Serializable
data class InferenceParameters(
    val contextLength: Int = 2048,
    val maxTokens: Int = 1024,
    val temperature: Float = 0.3f,
    val topP: Float = 0.92f,
    val topK: Int = 40,
    val threads: Int = 4
)

@Serializable
data class DeviceHardwareProfile(
    val manufacturer: String,
    val modelName: String,
    val abi: String,
    val isArm64: Boolean,
    val cpuCores: Int,
    val androidVersionLabel: String,
    val sdkInt: Int,
    val totalRamMb: Long,
    val availableRamMb: Long,
    val modelSafeBudgetMb: Long,
    val systemMemoryPressureLabel: String,
    val processPssMb: Long,
    val freeStorageMb: Long,
    val totalStorageMb: Long,
    val thermalStatusLabel: String
)

@Serializable
data class RecommendedModelPreset(
    val id: String,
    val name: String,
    val recommendedRole: AgentRole,
    val format: ModelFormat,
    val downloadUrl: String,
    val mmprojDownloadUrl: String? = null,
    val estimatedSizeMb: Long,
    val estimatedRamMb: Long,
    val supportsVision: Boolean,
    val quantization: String,
    val notes: String
)

@Serializable
data class MultiAgentStep(
    val stepIndex: Int,
    val phase: String,
    val agentRole: String,
    val instruction: String,
    val outputSummary: String,
    val durationMs: Long,
    val passedReview: Boolean = true,
    val revisionNote: String? = null
)

@Serializable
data class AgentStructuredEnvelope(
    val taskId: String,
    val fromAgent: String,
    val toAgent: String,
    val objective: String,
    val contextSummary: String,
    val imagePath: String? = null,
    val constraints: List<String> = emptyList(),
    val status: String = "PENDING"
)

data class DownloadProgressState(
    val presetId: String,
    val fileName: String,
    val bytesRead: Long,
    val totalBytes: Long,
    val progressPercent: Int,
    val isDownloading: Boolean,
    val error: String? = null
)

data class DiagnosticCheckResult(
    val name: String,
    val passed: Boolean,
    val detail: String,
    val durationMs: Long
)

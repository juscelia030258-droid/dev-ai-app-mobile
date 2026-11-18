package com.example.inference

import com.example.core.model.InferenceParameters
import com.example.core.model.RuntimeBackend
import com.example.monitoring.data.LocalModelEntity
import kotlinx.coroutines.flow.Flow

data class ChatTurn(
    val role: String, // "system", "user", "assistant"
    val text: String,
    val imagePath: String? = null
)

sealed interface InferenceStreamEvent {
    data class Token(val text: String) : InferenceStreamEvent
    data class Status(val message: String) : InferenceStreamEvent
    data class Completed(val fullText: String, val durationMs: Long, val approxTokens: Int) : InferenceStreamEvent
    data class Failed(val errorMessage: String) : InferenceStreamEvent
}

interface LocalInferenceRuntime {
    val backend: RuntimeBackend
    val loadedModelId: String?

    suspend fun loadModel(model: LocalModelEntity, params: InferenceParameters): Result<Unit>
    suspend fun unloadModel()
    fun streamCompletion(
        systemPrompt: String,
        messages: List<ChatTurn>,
        params: InferenceParameters
    ): Flow<InferenceStreamEvent>
}

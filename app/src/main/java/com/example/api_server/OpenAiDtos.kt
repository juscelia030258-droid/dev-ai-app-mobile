package com.example.api_server

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class OpenAiModelsResponse(
    val `object`: String = "list",
    val data: List<OpenAiModelInfo>
)

@Serializable
data class OpenAiModelInfo(
    val id: String,
    val `object`: String = "model",
    val created: Long,
    @SerialName("owned_by") val ownedBy: String = "local-dev-ai",
    val description: String = "",
    @SerialName("assigned_local_model") val assignedLocalModel: String? = null,
    @SerialName("supports_vision") val supportsVision: Boolean = false
)

@Serializable
data class ChatCompletionRequest(
    val model: String = "local-dev-coding",
    val messages: List<ChatMessageDto> = emptyList(),
    val temperature: Float? = null,
    @SerialName("top_p") val topP: Float? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val stream: Boolean = false,
    val agent: String? = null
)

@Serializable
data class ChatMessageDto(
    val role: String,
    // Supports either plain string or OpenAI multimodal content array [{"type":"text","text":"..."},{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,..."}}]
    val content: JsonElement
)

@Serializable
data class ChatCompletionResponse(
    val id: String,
    val `object`: String = "chat.completion",
    val created: Long,
    val model: String,
    val choices: List<ChatCompletionChoice>,
    val usage: TokenUsageDto
)

@Serializable
data class ChatCompletionChoice(
    val index: Int = 0,
    val message: AssistantMessageDto,
    @SerialName("finish_reason") val finishReason: String = "stop"
)

@Serializable
data class AssistantMessageDto(
    val role: String = "assistant",
    val content: String
)

@Serializable
data class TokenUsageDto(
    @SerialName("prompt_tokens") val promptTokens: Int,
    @SerialName("completion_tokens") val completionTokens: Int,
    @SerialName("total_tokens") val totalTokens: Int
)

@Serializable
data class ChatCompletionChunkResponse(
    val id: String,
    val `object`: String = "chat.completion.chunk",
    val created: Long,
    val model: String,
    val choices: List<ChatChunkChoice>
)

@Serializable
data class ChatChunkChoice(
    val index: Int = 0,
    val delta: ChatChunkDelta,
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
data class ChatChunkDelta(
    val role: String? = null,
    val content: String? = null
)

@Serializable
data class OpenAiErrorEnvelope(
    val error: OpenAiErrorBody
)

@Serializable
data class OpenAiErrorBody(
    val message: String,
    val type: String,
    val param: String? = null,
    val code: String
)

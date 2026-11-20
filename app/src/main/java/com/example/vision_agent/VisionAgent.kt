package com.example.vision_agent

import com.example.core.model.AgentRole
import com.example.core.model.InferenceParameters
import com.example.core.model.RuntimeBackend
import com.example.inference.ChatTurn
import com.example.inference.InferenceExecutionResult
import com.example.inference.InferenceRuntimeManager
import com.example.inference.InferenceStreamEvent
import com.example.monitoring.data.AgentConfigDao
import com.example.monitoring.data.AgentConfigEntity
import com.example.monitoring.data.AgentHistoryDao
import com.example.monitoring.data.AgentHistoryEntity
import com.example.monitoring.data.LocalModelDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

class VisionAgent(
    private val runtimeManager: InferenceRuntimeManager,
    private val agentConfigDao: AgentConfigDao,
    private val localModelDao: LocalModelDao,
    private val historyDao: AgentHistoryDao
) {
    companion object {
        val DEFAULT_SYSTEM_PROMPT = """
            Você é a VISION AI do LOCAL DEV AI, especialista em interpretar imagens, capturas de tela, diagramas e interfaces.
            REGRAS DE RESPOSTA:
            - Analise a imagem uma única vez e entregue uma única resposta final.
            - Comece pela informação principal. Depois apresente somente detalhes que acrescentem algo novo.
            - Não repita palavras, frases, parágrafos ou a descrição completa da imagem.
            - Não narre continuamente o que está vendo nem reinicie a análise.
            - Se um texto ou objeto já foi descrito, não o descreva novamente.
            - Seja concisa por padrão: use poucos tópicos e evite introduções longas.
            - Se não conseguir identificar algo, diga que não está nítido; não invente.
            - Em interfaces, destaque apenas textos legíveis, elementos importantes, estado e problemas visíveis.
            - Para apoiar a Coding AI, transforme observações visuais em especificações objetivas.
            - Só analise imagens realmente fornecidas. Não diga que navegou ou viu uma captura que não recebeu.
        """.trimIndent()
    }

    fun streamExecute(
        messages: List<ChatTurn>,
        source: String,
        temperatureOverride: Float? = null,
        maxTokensOverride: Int? = null
    ): Flow<InferenceStreamEvent> = flow {
        val cfg = agentConfigDao.getByRole(AgentRole.VISION.id) ?: AgentConfigEntity(roleId = AgentRole.VISION.id)
        if (!cfg.enabled) {
            emit(InferenceStreamEvent.Failed("Vision AI está desativada nas configurações de agentes."))
            return@flow
        }
        val model = cfg.assignedModelId?.let { localModelDao.getById(it) }
        if (model == null) {
            emit(InferenceStreamEvent.Failed("Nenhum modelo multimodal associado à Vision AI. Selecione um modelo com mmproj ou visão LiteRT-LM."))
            return@flow
        }
        if (!model.supportsVision) {
            emit(
                InferenceStreamEvent.Failed(
                    "O modelo '${model.name}' é exclusivamente textual e não possui capacidade multimodal de visão (falta projetor mmproj ou grafo visual)."
                )
            )
            return@flow
        }

        val imagePath = messages.lastOrNull { it.imagePath != null }?.imagePath
        if (imagePath == null || !File(imagePath).exists()) {
            emit(
                InferenceStreamEvent.Failed(
                    "Vision AI requer uma imagem válida (arquivo local ou image_url base64 na API)."
                )
            )
            return@flow
        }

        val params = InferenceParameters(
            contextLength = cfg.contextLength,
            // Short, bounded visual answers for compact vision models such as SmolVLM 500M.
            maxTokens = (maxTokensOverride ?: cfg.maxTokens).coerceIn(64, 384),
            temperature = (temperatureOverride ?: cfg.temperature).coerceIn(0.1f, 0.35f),
            topP = cfg.topP,
            topK = cfg.topK,
            threads = cfg.threads
        )
        val backend = runCatching { RuntimeBackend.valueOf(cfg.runtimeBackend) }
            .getOrDefault(RuntimeBackend.LLAMA_CPP)
        val sysPrompt = cfg.systemPromptOverride?.takeIf { it.isNotBlank() } ?: DEFAULT_SYSTEM_PROMPT

        val startTime = System.currentTimeMillis()
        var lastError: String? = null
        var fullOutput = ""

        runtimeManager.streamInference(
            role = AgentRole.VISION,
            model = model,
            preferredBackend = backend,
            systemPrompt = sysPrompt,
            messages = messages,
            params = params
        ).collect { ev ->
            when (ev) {
                is InferenceStreamEvent.Completed -> fullOutput = ev.fullText
                is InferenceStreamEvent.Failed -> lastError = ev.errorMessage
                else -> Unit
            }
            emit(ev)
        }

        historyDao.insert(
            AgentHistoryEntity(
                roleId = AgentRole.VISION.id,
                source = source,
                modelIdUsed = model.name,
                promptPreview = messages.lastOrNull()?.text.orEmpty().take(180),
                responsePreview = (fullOutput.ifBlank { lastError.orEmpty() }).take(280),
                hasImage = true,
                durationMs = System.currentTimeMillis() - startTime,
                success = lastError == null,
                errorMessage = lastError
            )
        )
    }

    suspend fun executeBlocking(
        messages: List<ChatTurn>,
        source: String
    ): Result<InferenceExecutionResult> {
        var completed: InferenceExecutionResult? = null
        var errorMsg: String? = null
        streamExecute(messages, source).collect { ev ->
            when (ev) {
                is InferenceStreamEvent.Completed -> {
                    val modelName = agentConfigDao.getByRole(AgentRole.VISION.id)?.assignedModelId
                        ?.let { localModelDao.getById(it)?.name } ?: "Local Vision Model"
                    completed = InferenceExecutionResult(
                        text = ev.fullText,
                        durationMs = ev.durationMs,
                        approxTokens = ev.approxTokens,
                        modelName = modelName,
                        runtimeBackend = agentConfigDao.getByRole(AgentRole.VISION.id)?.runtimeBackend ?: "LLAMA_CPP"
                    )
                }
                is InferenceStreamEvent.Failed -> errorMsg = ev.errorMessage
                else -> Unit
            }
        }
        return if (completed != null) Result.success(completed!!)
        else Result.failure(IllegalStateException(errorMsg ?: "Vision AI não retornou resposta."))
    }
}

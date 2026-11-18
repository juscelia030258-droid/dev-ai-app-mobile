package com.example.coding_agent

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

class CodingAgent(
    private val runtimeManager: InferenceRuntimeManager,
    private val agentConfigDao: AgentConfigDao,
    private val localModelDao: LocalModelDao,
    private val historyDao: AgentHistoryDao
) {
    companion object {
        val DEFAULT_SYSTEM_PROMPT = """
            Você é a CODING AI do LOCAL DEV AI executando localmente no smartphone.
            Sua especialidade exclusiva é engenharia de software, programação, análise de código, correção de bugs e revisão técnica.
            Regras obrigatórias:
            - Produza código limpo, completo, compilável e bem estruturado.
            - Identifique bugs, condições de corrida, falhas de segurança e edge cases com precisão.
            - Não invente APIs inexistentes; se faltar contexto do projeto, declare explicitamente o que precisa ser verificado.
            - Quando receber uma instrução de revisão da Reasoning AI, corrija pontualmente os problemas apontados.
        """.trimIndent()
    }

    fun streamExecute(
        messages: List<ChatTurn>,
        source: String,
        temperatureOverride: Float? = null,
        maxTokensOverride: Int? = null
    ): Flow<InferenceStreamEvent> = flow {
        val cfg = agentConfigDao.getByRole(AgentRole.CODING.id) ?: AgentConfigEntity(roleId = AgentRole.CODING.id)
        if (!cfg.enabled) {
            emit(InferenceStreamEvent.Failed("Coding AI está desativada nas configurações de agentes."))
            return@flow
        }
        val modelId = cfg.assignedModelId
        val model = modelId?.let { localModelDao.getById(it) }
        if (model == null) {
            emit(InferenceStreamEvent.Failed("Nenhum modelo local associado à Coding AI. Importe ou baixe um modelo GGUF/LiteRT-LM na aba Modelos."))
            return@flow
        }

        val params = InferenceParameters(
            contextLength = cfg.contextLength,
            maxTokens = maxTokensOverride ?: cfg.maxTokens,
            temperature = temperatureOverride ?: cfg.temperature,
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
            role = AgentRole.CODING,
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
                roleId = AgentRole.CODING.id,
                source = source,
                modelIdUsed = model.name,
                promptPreview = messages.lastOrNull()?.text.orEmpty().take(180),
                responsePreview = (fullOutput.ifBlank { lastError.orEmpty() }).take(280),
                hasImage = false,
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
                    val modelName = agentConfigDao.getByRole(AgentRole.CODING.id)?.assignedModelId
                        ?.let { localModelDao.getById(it)?.name } ?: "Local Coding Model"
                    completed = InferenceExecutionResult(
                        text = ev.fullText,
                        durationMs = ev.durationMs,
                        approxTokens = ev.approxTokens,
                        modelName = modelName,
                        runtimeBackend = agentConfigDao.getByRole(AgentRole.CODING.id)?.runtimeBackend ?: "LLAMA_CPP"
                    )
                }
                is InferenceStreamEvent.Failed -> errorMsg = ev.errorMessage
                else -> Unit
            }
        }
        return if (completed != null) Result.success(completed!!)
        else Result.failure(IllegalStateException(errorMsg ?: "Coding AI não retornou resposta."))
    }
}

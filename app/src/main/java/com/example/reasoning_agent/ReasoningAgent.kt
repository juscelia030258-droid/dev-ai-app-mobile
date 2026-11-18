package com.example.reasoning_agent

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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class BrowserRequest(
    val query: String,
    val objective: String = ""
)

@Serializable
data class OrchestrationPlan(
    val goalSummary: String,
    val useVision: Boolean = false,
    val useCoding: Boolean = false,
    val visionInstruction: String = "",
    val codingInstruction: String = "",
    val acceptanceCriteria: List<String> = emptyList(),
    val browserRequests: List<BrowserRequest> = emptyList()
)

@Serializable
data class EvaluationVerdict(
    val approved: Boolean,
    val contradictionsOrGaps: String = "",
    val revisionInstruction: String = ""
)

class ReasoningAgent(
    private val runtimeManager: InferenceRuntimeManager,
    private val agentConfigDao: AgentConfigDao,
    private val localModelDao: LocalModelDao,
    private val historyDao: AgentHistoryDao
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    companion object {
        val DEFAULT_SYSTEM_PROMPT = """
            Você é a REASONING AI, a inteligência central de raciocínio, planejamento e coordenação do LOCAL DEV AI.
            Suas responsabilidades:
            - Interpretar solicitações complexas e planejar etapas objetivas.
            - Selecionar quais agentes especialistas (Vision AI, Coding AI) devem atuar.
            - Avaliar criticamente resultados recebidos, detectar contradições e exigir revisões antes de aprovar.
            - Consolidar a resposta final coerente e verificada.
            - Você também dispõe do Private Browser interno do LOCAL DEV AI. Ele pesquisa na web pública e lê o texto de páginas, sem depender do navegador do usuário.
            - Quando a tarefa exigir informações atuais, pesquisa externa ou consulta a páginas, solicite até 3 browserRequests com consultas curtas e específicas.
            - O navegador é somente leitura nesta versão: não faz login, não envia formulários, não compra, não publica e não executa ações em contas.
            - Não invente resultados do navegador. Use somente o conteúdo retornado pelo módulo.
        """.trimIndent()
    }

    fun streamExecute(
        messages: List<ChatTurn>,
        source: String,
        temperatureOverride: Float? = null,
        maxTokensOverride: Int? = null
    ): Flow<InferenceStreamEvent> = flow {
        val cfg = agentConfigDao.getByRole(AgentRole.REASONING.id) ?: AgentConfigEntity(roleId = AgentRole.REASONING.id)
        if (!cfg.enabled) {
            emit(InferenceStreamEvent.Failed("Reasoning AI está desativada nas configurações de agentes."))
            return@flow
        }
        val model = cfg.assignedModelId?.let { localModelDao.getById(it) }
        if (model == null) {
            emit(InferenceStreamEvent.Failed("Nenhum modelo associado à Reasoning AI. Associe um modelo local na aba Modelos."))
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
            role = AgentRole.REASONING,
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
                roleId = AgentRole.REASONING.id,
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
        source: String,
        temperatureOverride: Float? = null,
        maxTokensOverride: Int? = null
    ): Result<InferenceExecutionResult> {
        var completed: InferenceExecutionResult? = null
        var errorMsg: String? = null
        streamExecute(messages, source, temperatureOverride, maxTokensOverride).collect { ev ->
            when (ev) {
                is InferenceStreamEvent.Completed -> {
                    val modelName = agentConfigDao.getByRole(AgentRole.REASONING.id)?.assignedModelId
                        ?.let { localModelDao.getById(it)?.name } ?: "Local Reasoning Model"
                    completed = InferenceExecutionResult(
                        text = ev.fullText,
                        durationMs = ev.durationMs,
                        approxTokens = ev.approxTokens,
                        modelName = modelName,
                        runtimeBackend = agentConfigDao.getByRole(AgentRole.REASONING.id)?.runtimeBackend ?: "LLAMA_CPP"
                    )
                }
                is InferenceStreamEvent.Failed -> errorMsg = ev.errorMessage
                else -> Unit
            }
        }
        return if (completed != null) Result.success(completed!!)
        else Result.failure(IllegalStateException(errorMsg ?: "Reasoning AI não retornou resposta."))
    }

    suspend fun createPlan(
        userRequest: String,
        hasImage: Boolean,
        codingEnabled: Boolean,
        visionEnabled: Boolean,
        source: String
    ): Result<OrchestrationPlan> {
        val plannerPrompt = """
            Analise a solicitação do usuário e responda APENAS em JSON válido seguindo exatamente este esquema:
            {
              "goalSummary": "resumo curto do objetivo",
              "useVision": true/false,
              "useCoding": true/false,
              "visionInstruction": "instrução objetiva para Vision AI se useVision=true",
              "codingInstruction": "instrução técnica objetiva para Coding AI se useCoding=true",
              "acceptanceCriteria": ["critério 1", "critério 2"],
              "browserRequests": [{"query": "consulta para pesquisar na web", "objective": "o que precisa descobrir"}]
            }
            Contexto disponível:
            - Imagem anexada: $hasImage
            - Vision AI habilitada: $visionEnabled
            - Coding AI habilitada: $codingEnabled
            - Private Browser disponível: sim
            - Use browserRequests vazio quando a web não for necessária; não pesquise só por hábito.

            Solicitação do usuário:
            $userRequest
        """.trimIndent()

        val result = executeBlocking(
            messages = listOf(ChatTurn("user", plannerPrompt)),
            source = source,
            temperatureOverride = 0.1f,
            maxTokensOverride = 512
        )

        return result.map { exec ->
            parsePlanSafely(exec.text, userRequest, hasImage, codingEnabled, visionEnabled)
        }
    }

    suspend fun evaluateSpecialistOutput(
        originalRequest: String,
        plan: OrchestrationPlan,
        agentRole: AgentRole,
        specialistOutput: String,
        source: String
    ): Result<EvaluationVerdict> {
        // Basic structural pre-checks before LLM evaluation
        if (specialistOutput.isBlank()) {
            return Result.success(
                EvaluationVerdict(
                    approved = false,
                    contradictionsOrGaps = "A resposta do agente ${agentRole.displayName} veio vazia.",
                    revisionInstruction = "Produza uma resposta completa atendendo ao objetivo: ${plan.goalSummary}"
                )
            )
        }

        val evalPrompt = """
            Você é o avaliador crítico (Reasoning AI). Nunca aprove automaticamente uma saída sem verificar coerência.
            Compare a saída produzida pelo agente ${agentRole.displayName} com a solicitação original e critérios de aceitação.
            Responda APENAS em JSON válido:
            {
              "approved": true/false,
              "contradictionsOrGaps": "contradições, bugs, partes incompletas ou 'Nenhuma'",
              "revisionInstruction": "o que o agente deve corrigir se approved=false"
            }

            Solicitação original: $originalRequest
            Critérios: ${plan.acceptanceCriteria.joinToString("; ")}
            Saída do agente (${agentRole.displayName}):
            ${specialistOutput.take(3500)}
        """.trimIndent()

        val result = executeBlocking(
            messages = listOf(ChatTurn("user", evalPrompt)),
            source = source,
            temperatureOverride = 0.1f,
            maxTokensOverride = 384
        )

        return result.map { exec ->
            parseVerdictSafely(exec.text, specialistOutput)
        }
    }

    suspend fun synthesizeFinalResponse(
        originalRequest: String,
        plan: OrchestrationPlan,
        visionOutput: String?,
        codingOutput: String?,
        reviewNotes: List<String>,
        source: String
    ): Result<InferenceExecutionResult> {
        val synthesisPrompt = buildString {
            appendLine("Consolide a resposta final para o usuário com base nos resultados verificados dos agentes.")
            appendLine("Solicitação original: $originalRequest")
            appendLine("Objetivo planejado: ${plan.goalSummary}")
            if (!visionOutput.isNullOrBlank()) {
                appendLine("\n--- Resultado Vision AI ---")
                appendLine(visionOutput)
            }
            if (!codingOutput.isNullOrBlank()) {
                appendLine("\n--- Resultado Coding AI ---")
                appendLine(codingOutput)
            }
            if (reviewNotes.isNotEmpty()) {
                appendLine("\n--- Notas de verificação ---")
                reviewNotes.forEach { appendLine("- $it") }
            }
            appendLine("\nEntregue uma resposta clara, fiel ao código/análise verificado e sem contradições.")
        }

        return executeBlocking(
            messages = listOf(ChatTurn("user", synthesisPrompt)),
            source = source,
            temperatureOverride = 0.2f
        )
    }

    internal fun parsePlanSafely(
        rawText: String,
        userRequest: String,
        hasImage: Boolean,
        codingEnabled: Boolean,
        visionEnabled: Boolean
    ): OrchestrationPlan {
        val jsonBlock = extractJsonObject(rawText)
        if (jsonBlock != null) {
            val parsed = runCatching { json.decodeFromString<OrchestrationPlan>(jsonBlock) }.getOrNull()
            if (parsed != null) {
                return parsed.copy(
                    useVision = parsed.useVision && hasImage && visionEnabled,
                    useCoding = parsed.useCoding && codingEnabled
                )
            }
        }
        // Deterministic structural fallback if small local LLM produced non-JSON prose
        val lower = userRequest.lowercase()
        val looksLikeCode = listOf("code", "código", "kotlin", "python", "função", "bug", "erro", "ui", "compose", "api", "html", "sql", "class", "refactor")
            .any { lower.contains(it) }
        return OrchestrationPlan(
            goalSummary = userRequest.take(160),
            useVision = hasImage && visionEnabled,
            useCoding = codingEnabled && (looksLikeCode || !hasImage),
            visionInstruction = if (hasImage) "Analise a imagem considerando: $userRequest" else "",
            codingInstruction = if (looksLikeCode || !hasImage) userRequest else "",
            acceptanceCriteria = listOf("Coerência com a solicitação original", "Ausência de contradições")
        )
    }

    internal fun parseVerdictSafely(rawText: String, specialistOutput: String): EvaluationVerdict {
        val jsonBlock = extractJsonObject(rawText)
        if (jsonBlock != null) {
            val parsed = runCatching { json.decodeFromString<EvaluationVerdict>(jsonBlock) }.getOrNull()
            if (parsed != null) return parsed
        }
        // Fail closed: malformed evaluator output must never be treated as approval.
        return EvaluationVerdict(
            approved = false,
            contradictionsOrGaps = "A Reasoning AI não retornou um veredito JSON válido; a saída não foi aprovada.",
            revisionInstruction = "Reavalie a saída e retorne um JSON válido com approved, contradictionsOrGaps e revisionInstruction."
        )
    }

    private fun extractJsonObject(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1)
        }
        return null
    }
}

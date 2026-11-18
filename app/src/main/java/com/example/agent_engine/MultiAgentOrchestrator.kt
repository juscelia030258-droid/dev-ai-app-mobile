package com.example.agent_engine

import com.example.coding_agent.CodingAgent
import com.example.core.model.AgentRole
import com.example.core.model.AgentStructuredEnvelope
import com.example.core.model.MultiAgentStep
import com.example.inference.ChatTurn
import com.example.monitoring.data.AgentConfigDao
import com.example.monitoring.data.LocalModelDao
import com.example.monitoring.data.MultiAgentRunDao
import com.example.monitoring.data.MultiAgentRunEntity
import com.example.reasoning_agent.OrchestrationPlan
import com.example.reasoning_agent.ReasoningAgent
import com.example.settings.SettingsRepository
import com.example.vision_agent.VisionAgent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

data class MultiAgentLiveState(
    val isRunning: Boolean = false,
    val currentTaskId: String? = null,
    val originalRequest: String = "",
    val currentPhase: String = "Aguardando tarefa",
    val activeAgent: AgentRole? = null,
    val plan: OrchestrationPlan? = null,
    val steps: List<MultiAgentStep> = emptyList(),
    val revisionCount: Int = 0,
    val finalOutput: String = "",
    val error: String? = null
)

class MultiAgentOrchestrator(
    private val reasoningAgent: ReasoningAgent,
    private val codingAgent: CodingAgent,
    private val visionAgent: VisionAgent,
    private val agentConfigDao: AgentConfigDao,
    private val localModelDao: LocalModelDao,
    private val multiAgentRunDao: MultiAgentRunDao,
    private val settingsRepository: SettingsRepository
) {
    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true }
    private val orchestrationMutex = Mutex()
    @Volatile
    private var activeExecutionJob: Job? = null

    private val _liveState = MutableStateFlow(MultiAgentLiveState())
    val liveState: StateFlow<MultiAgentLiveState> = _liveState.asStateFlow()

    val recentRunsFlow: Flow<List<MultiAgentRunEntity>> = multiAgentRunDao.observeRecent()

    fun cancelActiveTask() {
        activeExecutionJob?.cancel()
    }

    suspend fun executeMultiAgentTask(
        userRequest: String,
        imagePath: String? = null,
        source: String = "UI"
    ): Result<MultiAgentRunEntity> = orchestrationMutex.withLock {
        val taskId = "task_${UUID.randomUUID().toString().take(8)}"
        val startedAt = System.currentTimeMillis()
        val settings = settingsRepository.settingsFlow.first()
        val persistentContext = buildPersistentContext()
        val enrichedRequest = if (persistentContext.isBlank()) userRequest else """
            CONTEXTO PERSISTENTE DO PROJETO (tarefas anteriores concluídas):
            $persistentContext

            NOVA TAREFA DO USUÁRIO:
            $userRequest

            Use o contexto anterior somente quando for relevante. Preserve decisões e interfaces já estabelecidas,
            mas priorize a nova solicitação quando houver conflito. Não trate tarefas antigas como instruções novas.
        """.trimIndent()
        val maxRevisions = settings.maxMultiAgentRevisions.coerceIn(0, 4)
        val timeoutMs = settings.multiAgentTimeoutSec.coerceIn(30, 600) * 1000L

        val steps = mutableListOf<MultiAgentStep>()
        var revisionCount = 0

        _liveState.value = MultiAgentLiveState(
            isRunning = true,
            currentTaskId = taskId,
            originalRequest = userRequest,
            currentPhase = "Reasoning AI analisando e planejando",
            activeAgent = AgentRole.REASONING
        )

        val codingCfg = agentConfigDao.getByRole(AgentRole.CODING.id)
        val visionCfg = agentConfigDao.getByRole(AgentRole.VISION.id)
        val codingReady = codingCfg?.enabled != false && codingCfg?.assignedModelId?.let { localModelDao.getById(it) } != null
        val visionReady = visionCfg?.enabled != false && visionCfg?.assignedModelId?.let {
            localModelDao.getById(it)?.supportsVision == true
        } == true

        return@withLock try {
            val completedEntity = coroutineScope {
                activeExecutionJob = this.coroutineContext[Job]
                withTimeout(timeoutMs) {
                    // Phase 1: Reasoning AI interprets & plans
                    val planStart = System.currentTimeMillis()
                    val planResult = reasoningAgent.createPlan(
                        userRequest = enrichedRequest,
                        hasImage = imagePath != null,
                        codingEnabled = codingReady,
                        visionEnabled = visionReady,
                        source = source
                    )
                    if (planResult.isFailure) {
                        throw planResult.exceptionOrNull()
                            ?: IllegalStateException("Reasoning AI falhou ao criar o plano.")
                    }
                    val plan = planResult.getOrThrow()
                    steps += MultiAgentStep(
                        stepIndex = steps.size + 1,
                        phase = "Planejamento",
                        agentRole = AgentRole.REASONING.displayName,
                        instruction = userRequest.take(200),
                        outputSummary = "Objetivo: ${plan.goalSummary} | Vision=${plan.useVision} | Coding=${plan.useCoding}",
                        durationMs = System.currentTimeMillis() - planStart,
                        passedReview = true
                    )
                    _liveState.value = _liveState.value.copy(plan = plan, steps = steps.toList())
                    persistProgress(taskId, userRequest, source, "RUNNING", plan, steps, revisionCount, "")

                    var visionOutput: String? = null
                    var codingOutput: String? = null
                    val reviewNotes = mutableListOf<String>()

                    // Phase 2: Vision AI (if image + needed) — sequential model swap + critic verification
                    if (plan.useVision && imagePath != null) {
                        var currentVisionInstruction = plan.visionInstruction.ifBlank { userRequest }
                        var visionAttempts = 0
                        var visionApproved = false
                        val maxVisionRevisions = maxRevisions.coerceAtMost(1)

                        while (!visionApproved && visionAttempts <= maxVisionRevisions) {
                            visionAttempts += 1
                            val envelope = AgentStructuredEnvelope(
                                taskId = taskId,
                                fromAgent = AgentRole.REASONING.id,
                                toAgent = AgentRole.VISION.id,
                                objective = currentVisionInstruction,
                                contextSummary = plan.goalSummary,
                                imagePath = imagePath,
                                constraints = plan.acceptanceCriteria,
                                status = if (visionAttempts == 1) "DISPATCHED" else "REVISION_$visionAttempts"
                            )
                            _liveState.value = _liveState.value.copy(
                                currentPhase = if (visionAttempts == 1) "Vision AI analisando imagem" else "Vision AI revisando imagem",
                                activeAgent = AgentRole.VISION
                            )
                            val vStart = System.currentTimeMillis()
                            val vRes = visionAgent.executeBlocking(
                                messages = listOf(
                                    ChatTurn(
                                        role = "user",
                                        text = "Tarefa estruturada (${envelope.taskId}): ${envelope.objective}\nCritérios: ${envelope.constraints.joinToString("; ")}",
                                        imagePath = imagePath
                                    )
                                ),
                                source = source
                            )
                            if (vRes.isFailure) {
                                val err = vRes.exceptionOrNull()?.message ?: "Falha na Vision AI"
                                steps += MultiAgentStep(
                                    stepIndex = steps.size + 1,
                                    phase = "Análise Visual",
                                    agentRole = AgentRole.VISION.displayName,
                                    instruction = envelope.objective,
                                    outputSummary = "Erro: $err",
                                    durationMs = System.currentTimeMillis() - vStart,
                                    passedReview = false,
                                    revisionNote = err
                                )
                                reviewNotes += "Vision AI reportou erro: $err"
                                break
                            }

                            val candidateVision = vRes.getOrThrow().text
                            visionOutput = candidateVision
                            steps += MultiAgentStep(
                                stepIndex = steps.size + 1,
                                phase = if (visionAttempts == 1) "Análise Visual" else "Revisão Visual #$visionAttempts",
                                agentRole = AgentRole.VISION.displayName,
                                instruction = envelope.objective,
                                outputSummary = candidateVision.take(360),
                                durationMs = System.currentTimeMillis() - vStart,
                                passedReview = true
                            )
                            _liveState.value = _liveState.value.copy(steps = steps.toList())

                            _liveState.value = _liveState.value.copy(
                                currentPhase = "Reasoning AI avaliando análise visual",
                                activeAgent = AgentRole.REASONING
                            )
                            val evStart = System.currentTimeMillis()
                            val vVerdict = reasoningAgent.evaluateSpecialistOutput(
                                originalRequest = enrichedRequest,
                                plan = plan,
                                agentRole = AgentRole.VISION,
                                specialistOutput = candidateVision,
                                source = source
                            ).getOrNull()

                            if (vVerdict == null) {
                                throw IllegalStateException("Reasoning AI não conseguiu avaliar a saída da Vision AI; a tarefa foi interrompida sem aprovação.")
                            }
                            if (vVerdict.approved) {
                                visionApproved = true
                                val note = vVerdict.contradictionsOrGaps.takeIf { it.isNotBlank() } ?: "Análise visual aprovada"
                                reviewNotes += "Vision AI avaliada: $note"
                                steps += MultiAgentStep(
                                    stepIndex = steps.size + 1,
                                    phase = "Avaliação Crítica (Visão)",
                                    agentRole = AgentRole.REASONING.displayName,
                                    instruction = "Validar descrição visual extraída",
                                    outputSummary = note,
                                    durationMs = System.currentTimeMillis() - evStart,
                                    passedReview = true
                                )
                            } else {
                                revisionCount += 1
                                reviewNotes += "Revisão visual solicitada (${vVerdict.contradictionsOrGaps})"
                                steps += MultiAgentStep(
                                    stepIndex = steps.size + 1,
                                    phase = "Avaliação Crítica (Visão)",
                                    agentRole = AgentRole.REASONING.displayName,
                                    instruction = "Validar descrição visual extraída",
                                    outputSummary = "Lacunas: ${vVerdict.contradictionsOrGaps}",
                                    durationMs = System.currentTimeMillis() - evStart,
                                    passedReview = false,
                                    revisionNote = vVerdict.revisionInstruction
                                )
                                if (visionAttempts > maxVisionRevisions) {
                                    throw IllegalStateException("Vision AI permaneceu reprovada após $maxVisionRevisions revisão(ões): ${vVerdict.contradictionsOrGaps}")
                                }
                                currentVisionInstruction = "${plan.visionInstruction.ifBlank { userRequest }}\nCorreção exigida: ${vVerdict.revisionInstruction}"
                            }
                            _liveState.value = _liveState.value.copy(steps = steps.toList(), revisionCount = revisionCount)
                        }
                    }

                    // Phase 3: Coding AI + Reasoning Critic Revision Loop (bounded by maxRevisions)
                    if (plan.useCoding) {
                        var currentCodingInstruction = buildString {
                            append(plan.codingInstruction.ifBlank { enrichedRequest })
                            append("\n\nContexto persistente do projeto:\n")
                            append(persistentContext.ifBlank { "Ainda não há tarefas anteriores concluídas." })
                            if (!visionOutput.isNullOrBlank()) {
                                append("\n\nContexto visual extraído pela Vision AI:\n")
                                append(visionOutput)
                            }
                        }

                        var approved = false
                        var attempt = 0
                        while (!approved && attempt <= maxRevisions) {
                            attempt += 1
                            val envelope = AgentStructuredEnvelope(
                                taskId = taskId,
                                fromAgent = AgentRole.REASONING.id,
                                toAgent = AgentRole.CODING.id,
                                objective = currentCodingInstruction,
                                contextSummary = plan.goalSummary,
                                constraints = plan.acceptanceCriteria,
                                status = if (attempt == 1) "DISPATCHED" else "REVISION_$attempt"
                            )
                            _liveState.value = _liveState.value.copy(
                                currentPhase = if (attempt == 1) "Coding AI implementando" else "Coding AI em revisão #$attempt",
                                activeAgent = AgentRole.CODING
                            )
                            val cStart = System.currentTimeMillis()
                            val cRes = codingAgent.executeBlocking(
                                messages = listOf(ChatTurn("user", envelope.objective)),
                                source = source
                            )
                            if (cRes.isFailure) {
                                throw cRes.exceptionOrNull()
                                    ?: IllegalStateException("Coding AI falhou na execução.")
                            }
                            val candidateCoding = cRes.getOrThrow().text
                            codingOutput = candidateCoding
                            steps += MultiAgentStep(
                                stepIndex = steps.size + 1,
                                phase = if (attempt == 1) "Desenvolvimento" else "Revisão Coding #$attempt",
                                agentRole = AgentRole.CODING.displayName,
                                instruction = envelope.objective.take(220),
                                outputSummary = candidateCoding.take(360),
                                durationMs = System.currentTimeMillis() - cStart,
                                passedReview = true
                            )
                            _liveState.value = _liveState.value.copy(steps = steps.toList())

                            // Critical Evaluation by Reasoning AI (never blindly accepts)
                            _liveState.value = _liveState.value.copy(
                                currentPhase = "Reasoning AI avaliando coerência",
                                activeAgent = AgentRole.REASONING
                            )
                            val eStart = System.currentTimeMillis()
                            val verdictRes = reasoningAgent.evaluateSpecialistOutput(
                                originalRequest = userRequest,
                                plan = plan,
                                agentRole = AgentRole.CODING,
                                specialistOutput = candidateCoding,
                                source = source
                            )
                            val verdict = verdictRes.getOrNull()
                                ?: throw IllegalStateException("Reasoning AI não conseguiu avaliar a saída da Coding AI; a tarefa foi interrompida sem aprovação.")
                            if (verdict.approved) {
                                approved = true
                                val note = verdict.contradictionsOrGaps.takeIf { it.isNotBlank() } ?: "Aprovado"
                                reviewNotes += "Coding AI avaliada: $note"
                                steps += MultiAgentStep(
                                    stepIndex = steps.size + 1,
                                    phase = "Avaliação Crítica",
                                    agentRole = AgentRole.REASONING.displayName,
                                    instruction = "Verificar coerência da Coding AI",
                                    outputSummary = note,
                                    durationMs = System.currentTimeMillis() - eStart,
                                    passedReview = true
                                )
                            } else {
                                revisionCount += 1
                                reviewNotes += "Revisão solicitada (${verdict.contradictionsOrGaps})"
                                steps += MultiAgentStep(
                                    stepIndex = steps.size + 1,
                                    phase = "Avaliação Crítica (Revisão)",
                                    agentRole = AgentRole.REASONING.displayName,
                                    instruction = "Verificar coerência da Coding AI",
                                    outputSummary = "Lacunas: ${verdict.contradictionsOrGaps}",
                                    durationMs = System.currentTimeMillis() - eStart,
                                    passedReview = false,
                                    revisionNote = verdict.revisionInstruction
                                )
                                if (attempt > maxRevisions) {
                                    throw IllegalStateException("Coding AI permaneceu reprovada após $maxRevisions revisão(ões): ${verdict.contradictionsOrGaps}")
                                }
                                currentCodingInstruction = """
                                    Revise a sua resposta anterior com base na auditoria da Reasoning AI.
                                    Problemas apontados: ${verdict.contradictionsOrGaps}
                                    Instrução de correção: ${verdict.revisionInstruction}
                                    Solicitação original: $enrichedRequest
                                    Resposta anterior:
                                    ${candidateCoding.take(2500)}
                                """.trimIndent()
                            }
                            _liveState.value = _liveState.value.copy(
                                steps = steps.toList(),
                                revisionCount = revisionCount
                            )
                        }
                        if (!approved) {
                            throw IllegalStateException("Coding AI não recebeu aprovação da Reasoning AI.")
                        }
                    }

                    // Phase 4: Consolidate Final Answer
                    _liveState.value = _liveState.value.copy(
                        currentPhase = "Reasoning AI consolidando resposta final",
                        activeAgent = AgentRole.REASONING
                    )
                    val synStart = System.currentTimeMillis()
                    val finalResult = reasoningAgent.synthesizeFinalResponse(
                        originalRequest = enrichedRequest,
                        plan = plan,
                        visionOutput = visionOutput,
                        codingOutput = codingOutput,
                        reviewNotes = reviewNotes,
                        source = source
                    )
                    val finalText = finalResult.map { it.text }.getOrElse {
                        codingOutput ?: visionOutput ?: throw it
                    }

                    steps += MultiAgentStep(
                        stepIndex = steps.size + 1,
                        phase = "Consolidação Final",
                        agentRole = AgentRole.REASONING.displayName,
                        instruction = "Consolidar resultados verificados",
                        outputSummary = finalText.take(360),
                        durationMs = System.currentTimeMillis() - synStart,
                        passedReview = true
                    )

                    val entity = MultiAgentRunEntity(
                        taskId = taskId,
                        originalRequest = userRequest,
                        source = source,
                        status = "COMPLETED",
                        planJson = json.encodeToString(plan),
                        stepsJson = json.encodeToString(steps),
                        revisionCount = revisionCount,
                        finalResponse = finalText,
                        totalDurationMs = System.currentTimeMillis() - startedAt
                    )
                    multiAgentRunDao.upsert(entity)
                    _liveState.value = _liveState.value.copy(
                        isRunning = false,
                        currentPhase = "Concluído",
                        activeAgent = null,
                        steps = steps.toList(),
                        revisionCount = revisionCount,
                        finalOutput = finalText,
                        error = null
                    )
                    entity
                }
            }
            Result.success(completedEntity)
        } catch (ce: CancellationException) {
            val cancelledEntity = MultiAgentRunEntity(
                taskId = taskId,
                originalRequest = userRequest,
                source = source,
                status = "CANCELLED",
                planJson = _liveState.value.plan?.let { json.encodeToString(it) } ?: "{}",
                stepsJson = json.encodeToString(steps),
                revisionCount = revisionCount,
                finalResponse = "",
                errorMessage = "Execução cancelada pelo usuário ou limite de tempo.",
                totalDurationMs = System.currentTimeMillis() - startedAt
            )
            multiAgentRunDao.upsert(cancelledEntity)
            _liveState.value = _liveState.value.copy(
                isRunning = false,
                currentPhase = "Cancelado",
                activeAgent = null,
                steps = steps.toList(),
                error = cancelledEntity.errorMessage
            )
            Result.failure(ce)
        } catch (t: Throwable) {
            val errMsg = t.message ?: "Falha na orquestração multiagente"
            val failedEntity = MultiAgentRunEntity(
                taskId = taskId,
                originalRequest = userRequest,
                source = source,
                status = "FAILED",
                planJson = _liveState.value.plan?.let { json.encodeToString(it) } ?: "{}",
                stepsJson = json.encodeToString(steps),
                revisionCount = revisionCount,
                finalResponse = "",
                errorMessage = errMsg,
                totalDurationMs = System.currentTimeMillis() - startedAt
            )
            multiAgentRunDao.upsert(failedEntity)
            _liveState.value = _liveState.value.copy(
                isRunning = false,
                currentPhase = "Falha",
                activeAgent = null,
                steps = steps.toList(),
                error = errMsg
            )
            Result.failure(t)
        } finally {
            activeExecutionJob = null
        }
    }

    /** Builds a bounded, durable project memory from the latest successful Multi runs. */
    private suspend fun buildPersistentContext(): String {
        val previousRuns = multiAgentRunDao.getRecentCompleted()
        if (previousRuns.isEmpty()) return ""

        return buildString {
            previousRuns.take(5).forEachIndexed { index, run ->
                if (isNotEmpty()) append("\n\n--- Tarefa anterior ${index + 1} ---\n")
                else append("--- Tarefa anterior ${index + 1} ---\n")
                append("Pedido: ").append(run.originalRequest.take(900)).append("\n")
                if (run.planJson.isNotBlank() && run.planJson != "{}") {
                    append("Plano: ").append(run.planJson.take(1200)).append("\n")
                }
                if (run.finalResponse.isNotBlank()) {
                    append("Resultado: ").append(run.finalResponse.take(1500)).append("\n")
                }
                append("Estado: ").append(run.status)
            }
        }.take(6000)
    }

    private suspend fun persistProgress(
        taskId: String,
        originalRequest: String,
        source: String,
        status: String,
        plan: OrchestrationPlan,
        steps: List<MultiAgentStep>,
        revisionCount: Int,
        finalResponse: String
    ) {
        multiAgentRunDao.upsert(
            MultiAgentRunEntity(
                taskId = taskId,
                originalRequest = originalRequest,
                source = source,
                status = status,
                planJson = json.encodeToString(plan),
                stepsJson = json.encodeToString(steps),
                revisionCount = revisionCount,
                finalResponse = finalResponse
            )
        )
    }
}

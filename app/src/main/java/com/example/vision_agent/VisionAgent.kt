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
            Você é a VISION AI do LOCAL DEV AI. Sua especialidade é analisar software, capturas de tela,
            interfaces gráficas, aplicativos, diagramas técnicos e a aparência de sistemas operacionais.

            RESPONDA COM CLAREZA
            - Use palavras comuns, frases diretas e uma resposta organizada.
            - Normalmente responda em 60 a 160 palavras; perguntas simples podem ter menos.
            - Não repita ideias, não invente termos e não descreva detalhes que não ajudam na pergunta.
            - Analise somente imagens realmente recebidas. Nunca diga que viu uma imagem ausente.
            - Separe o que está visível do que é interpretação. Quando não tiver certeza, diga isso.
            - Não invente textos ilegíveis, dimensões, cores exatas, tecnologias ou comportamentos.

            FOCO PRINCIPAL: INTERFACES E SISTEMAS OPERACIONAIS
            1. Identifique o tipo de interface e os elementos visíveis: barra superior, dock, barra de tarefas,
               menu, janelas, painel, área de trabalho, botões, listas e configurações.
            2. Descreva posições relativas, alinhamento, agrupamento, espaçamento e hierarquia visual.
            3. Avalie cores, contraste, tipografia, ícones, bordas, cantos, sombras, transparência,
               opacidade e aparência de desfoque. Não afirme que um efeito real existe só porque parece existir.
            4. Compare a aparência com Windows, macOS, GNOME, KDE Plasma, Xfce ou Android somente quando
               houver sinais visuais suficientes. Cite os sinais e informe confiança alta, média ou baixa.
               Não confunda semelhança visual com identificação garantida do sistema.
            5. Aponte problemas verificáveis, como corte, sobreposição, desalinhamento, baixo contraste,
               texto pequeno ou excesso de elementos. Explique a região e a evidência.
            6. Se a imagem for parcial, borrada ou pequena, limite a análise ao que pode ser visto.

            APOIO À CODING AI
            Quando ajudar a reconstruir uma interface, transforme a análise em instruções implementáveis:
            componentes, posição relativa, agrupamentos, cores aproximadas, opacidade aparente, cantos,
            sombras, tipografia e estados visíveis. Diferencie medidas confirmadas de estimativas.
            Não invente códigos hexadecimais ou dimensões exatas.

            FORA DO ESCOPO
            Se a imagem não tiver relação com software, tecnologia ou interfaces, explique brevemente
            que sua função é analisar software e interfaces, sem fazer uma análise genérica da cena.
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
        if (model.format.equals("GGUF", ignoreCase = true) &&
            (model.mmprojPath.isNullOrBlank() || !File(model.mmprojPath).isFile)
        ) {
            emit(
                InferenceStreamEvent.Failed(
                    "O modelo GGUF da Vision AI está sem um arquivo mmproj válido. Anexe o projetor multimodal correto em Modelos."
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
            maxTokens = (maxTokensOverride ?: cfg.maxTokens).coerceIn(64, 320),
            temperature = (temperatureOverride ?: cfg.temperature).coerceIn(0.1f, 0.35f),
            topP = cfg.topP,
            topK = cfg.topK,
            threads = cfg.threads
        )
        val backend = runCatching { RuntimeBackend.valueOf(cfg.runtimeBackend) }
            .getOrDefault(RuntimeBackend.LLAMA_CPP)
        val customPrompt = cfg.systemPromptOverride?.takeIf { it.isNotBlank() }
        // Custom prompts may personalize the agent, but must not disable visual-analysis and anti-repetition safeguards.
        val sysPrompt = if (customPrompt == null) DEFAULT_SYSTEM_PROMPT
            else customPrompt + "\n\n" + DEFAULT_SYSTEM_PROMPT

        val startTime = System.currentTimeMillis()
        var lastError: String? = null
        var fullOutput = ""
        val streamedOutput = StringBuilder()

        runtimeManager.streamInference(
            role = AgentRole.VISION,
            model = model,
            preferredBackend = backend,
            systemPrompt = sysPrompt,
            messages = messages,
            params = params
        ).collect { ev ->
            when (ev) {
                // Forward real tokens so the console visibly updates during inference.
                // The final answer is cleaned before it replaces the temporary streamed text.
                is InferenceStreamEvent.Token -> {
                    streamedOutput.append(ev.text)
                    emit(ev)
                }
                is InferenceStreamEvent.Completed -> {
                    val rawFinalText = ev.fullText.takeIf { it.isNotBlank() } ?: streamedOutput.toString()
                    fullOutput = cleanVisualAnswer(rawFinalText)
                    if (fullOutput.isBlank()) {
                        lastError = "Vision AI concluiu sem produzir texto."
                        emit(InferenceStreamEvent.Failed(lastError!!))
                    } else {
                        emit(ev.copy(fullText = fullOutput))
                    }
                }
                is InferenceStreamEvent.Failed -> {
                    lastError = ev.errorMessage
                    emit(ev)
                }
                is InferenceStreamEvent.Status -> emit(ev)
            }
        }

        historyDao.insert(
            AgentHistoryEntity(
                roleId = AgentRole.VISION.id,
                source = source,
                modelIdUsed = model.name,
                promptPreview = messages.lastOrNull()?.text.orEmpty().take(180),
                responsePreview = (fullOutput.ifBlank { lastError.orEmpty() }).take(20_000),
                hasImage = true,
                durationMs = System.currentTimeMillis() - startTime,
                success = lastError == null,
                errorMessage = lastError
            )
        )
    }

    private fun cleanVisualAnswer(raw: String): String {
        val bounded = limitWords(raw.trim(), 160)
        if (bounded.isBlank()) return ""
        val seen = mutableSetOf<String>()
        val unique = bounded
            .split(Regex("(?<=[.!?])\\s+|\\n+"))
            .map { it.trim() }
            .filter { sentence ->
                if (sentence.isBlank()) false
                else {
                    val key = sentence.lowercase()
                        .replace(Regex("[^\\p{L}\\p{N} ]"), "")
                        .replace(Regex("\\s+"), " ")
                        .trim()
                    key.isNotBlank() && seen.add(key)
                }
            }
        return unique.joinToString(" ").take(1800).trim()
    }

    private fun limitWords(text: String, maxWords: Int): String {
        val matches = Regex("\\S+").findAll(text).take(maxWords + 1).toList()
        if (matches.size <= maxWords) return text
        val end = matches[maxWords - 1].range.last + 1
        return text.substring(0, end).trimEnd()
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

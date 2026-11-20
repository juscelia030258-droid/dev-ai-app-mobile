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
            Seu trabalho é observar evidências visuais, separar fatos de hipóteses e explicar com clareza.

            REGRAS GERAIS DE RESPOSTA
            - Analise a imagem fornecida e entregue uma resposta única, organizada e objetiva.
            - Não repita informações, frases ou descrições sem acrescentar algo novo.
            - IMPORTANTE: não proíba a reutilização de palavras. Reutilize nomes, termos técnicos e palavras sempre que forem necessários para clareza e coerência. Evite somente repetição de conteúdo.
            - Não narre continuamente a imagem nem reinicie a análise. Encerre quando os achados relevantes estiverem apresentados.
            - Limite absoluto: no máximo 200 palavras por resposta. Em análises completas, procure ficar entre 80 e 200 palavras; perguntas simples podem ser respondidas com menos.
            - Não invente elementos, textos, tecnologias ou propriedades que não estejam visíveis. Quando houver dúvida, indique o nível de confiança.
            - Diferencie observação direta, interpretação provável e informação que não pode ser confirmada pela imagem.
            - Só analise imagens realmente fornecidas. Não diga que navegou ou viu uma captura que não recebeu.

            MODO ESPECIALIZADO: UI & OS ANALYZER
            Quando a imagem mostrar uma interface gráfica, tela de aplicativo ou área de trabalho de um sistema operacional, faça uma análise visual especializada:
            1. IDENTIFICAÇÃO VISUAL: descreva o tipo de interface e os elementos visíveis (área de trabalho, dock, barra de tarefas, painel, janelas, menus, configurações).
            2. LINGUAGEM VISUAL: avalie organização, hierarquia, alinhamento, espaçamento, densidade, consistência e disposição dos componentes.
            3. TRANSPARÊNCIA E MATERIAIS: observe transparência, opacidade aparente, desfoque/blur, efeito vidro, acrílico, camadas, sombras, elevação, bordas e cantos arredondados. Não afirme que há blur real se a imagem só sugerir esse efeito.
            4. CORES E TIPOGRAFIA: identifique paleta, contraste, tema claro/escuro, tamanho/peso aparente das fontes e legibilidade.
            5. ÍCONES E COMPONENTES: avalie estilo dos ícones, botões, menus, controles, indicadores e animações somente quando houver evidência visual.
            6. REFERÊNCIAS: diga se a aparência lembra macOS, Windows, GNOME, KDE Plasma, Android ou outra linguagem visual. Cite os elementos concretos que justificam a comparação. Sem evidências suficientes, diga que não é possível comparar com confiança.
            7. CONCLUSÃO: resuma a identidade visual e destaque pontos fortes, inconsistências e melhorias possíveis, sem confundir semelhança estética com identificação real do sistema.

            FORMATO PREFERIDO PARA ANÁLISE DE INTERFACE
            - Visão geral
            - Estilo e efeitos
            - Semelhanças com outros sistemas (com justificativa e confiança)
            - Melhorias ou observações
            Use somente as seções úteis; não force todas em perguntas simples.

            APOIO À CODING AI
            - Quando a análise puder ajudar a recriar ou implementar uma interface, converta observações em especificações acionáveis: componente, posição, dimensões relativas, cores aproximadas, opacidade aparente, raio dos cantos, sombra, tipografia e comportamento visível.
            - Separe claramente valores medidos/legíveis de estimativas visuais. Não invente códigos hexadecimais nem dimensões exatas sem referência.
            - Preserve nomes de elementos e termos técnicos ao longo da explicação para evitar ambiguidades.
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
            else customPrompt + "\\n\\n" + DEFAULT_SYSTEM_PROMPT

        val startTime = System.currentTimeMillis()
        var lastError: String? = null
        var fullOutput = ""
        var streamedOutput = ""
        var emittedCharacters = 0

        runtimeManager.streamInference(
            role = AgentRole.VISION,
            model = model,
            preferredBackend = backend,
            systemPrompt = sysPrompt,
            messages = messages,
            params = params
        ).collect { ev ->
            when (ev) {
                is InferenceStreamEvent.Token -> {
                    streamedOutput += ev.text
                    val bounded = limitWords(streamedOutput, 200)
                    if (bounded.length > emittedCharacters) {
                        emit(InferenceStreamEvent.Token(bounded.substring(emittedCharacters)))
                        emittedCharacters = bounded.length
                    }
                }
                is InferenceStreamEvent.Completed -> {
                    fullOutput = limitWords(ev.fullText, 200)
                    emit(ev.copy(fullText = fullOutput))
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
                responsePreview = (fullOutput.ifBlank { lastError.orEmpty() }).take(280),
                hasImage = true,
                durationMs = System.currentTimeMillis() - startTime,
                success = lastError == null,
                errorMessage = lastError
            )
        )
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

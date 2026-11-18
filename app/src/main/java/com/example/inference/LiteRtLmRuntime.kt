package com.example.inference

import android.content.Context
import com.example.core.model.InferenceParameters
import com.example.core.model.RuntimeBackend
import com.example.monitoring.data.LocalModelEntity
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

class LiteRtLmRuntime(
    private val context: Context,
    override val backend: RuntimeBackend
) : LocalInferenceRuntime {

    private val singleThreadExecutor = Executors.newSingleThreadExecutor()
    private val runtimeDispatcher = singleThreadExecutor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + runtimeDispatcher)

    private var engine: Engine? = null
    private var currentModel: LocalModelEntity? = null
    private var loadedContextLength: Int = 0

    override val loadedModelId: String?
        get() = currentModel?.id

    override suspend fun loadModel(
        model: LocalModelEntity,
        params: InferenceParameters
    ): Result<Unit> = withContext(runtimeDispatcher) {
        runCatching {
            val file = File(model.filePath)
            require(file.exists() && file.length() > 64L) {
                "Arquivo .litertlm não encontrado em ${model.filePath}"
            }
            val targetCtx = params.contextLength.coerceIn(512, 8192)

            if (
                currentModel?.id == model.id &&
                currentModel?.supportsVision == model.supportsVision &&
                loadedContextLength == targetCtx &&
                engine != null
            ) {
                return@runCatching
            }

            unloadInternal()

            val primaryBackend = when (backend) {
                RuntimeBackend.LITERT_LM_GPU -> Backend.GPU()
                else -> Backend.CPU(threadCount = params.threads.coerceIn(1, 6))
            }

            val createdEngine = try {
                val config = EngineConfig(
                    modelPath = file.absolutePath,
                    backend = primaryBackend,
                    visionBackend = if (model.supportsVision) Backend.GPU() else null,
                    maxNumTokens = targetCtx,
                    maxNumImages = if (model.supportsVision) 2 else null,
                    cacheDir = context.cacheDir.absolutePath
                )
                Engine(config).also { it.initialize() }
            } catch (visionGpuFailure: Throwable) {
                if (!model.supportsVision) throw visionGpuFailure
                // Fallback vision backend to CPU if GPU OpenCL is unsupported on device
                val cpuFallbackConfig = EngineConfig(
                    modelPath = file.absolutePath,
                    backend = primaryBackend,
                    visionBackend = Backend.CPU(threadCount = params.threads.coerceIn(1, 6)),
                    maxNumTokens = targetCtx,
                    maxNumImages = 2,
                    cacheDir = context.cacheDir.absolutePath
                )
                Engine(cpuFallbackConfig).also { it.initialize() }
            }

            // Store engine only after initialize() returns without throwing
            engine = createdEngine
            currentModel = model
            loadedContextLength = targetCtx
        }
    }

    override suspend fun unloadModel() {
        withContext(runtimeDispatcher) {
            unloadInternal()
        }
    }

    private fun unloadInternal() {
        val existing = engine
        engine = null
        currentModel = null
        loadedContextLength = 0
        if (existing != null) {
            runCatching { existing.close() }
        }
    }

    override fun streamCompletion(
        systemPrompt: String,
        messages: List<ChatTurn>,
        params: InferenceParameters
    ): Flow<InferenceStreamEvent> = callbackFlow {
        val loadedEngine = engine
        val model = currentModel
        if (loadedEngine == null || model == null) {
            trySend(InferenceStreamEvent.Failed("Nenhum modelo LiteRT-LM carregado."))
            close()
            return@callbackFlow
        }

        var conversation: Conversation? = null
        val job = scope.launch {
            val startedAt = System.currentTimeMillis()
            val accumulated = StringBuilder()
            val maxTokensLimit = params.maxTokens.coerceIn(64, 4096)
            var chunks = 0

            try {
                val convConfig = ConversationConfig(
                    systemInstruction = Contents.of(systemPrompt.ifBlank { "You are a helpful local assistant." }),
                    samplerConfig = SamplerConfig(
                        topK = params.topK.coerceIn(1, 100),
                        topP = params.topP.toDouble().coerceIn(0.05, 1.0),
                        temperature = params.temperature.toDouble().coerceIn(0.0, 2.0),
                        seed = 42
                    )
                )
                val conv = loadedEngine.createConversation(convConfig)
                conversation = conv

                val contentsList = mutableListOf<Content>()
                val lastImagePath = messages.lastOrNull { it.imagePath != null }?.imagePath
                if (lastImagePath != null) {
                    if (!model.supportsVision) {
                        trySend(InferenceStreamEvent.Failed("O modelo ${model.name} não suporta entrada visual multimodal."))
                        close()
                        return@launch
                    }
                    contentsList += Content.ImageFile(lastImagePath)
                }

                val historyText = buildString {
                    messages.forEachIndexed { idx, turn ->
                        if (messages.size > 1 && idx < messages.lastIndex) {
                            append(turn.role.uppercase()).append(": ").append(turn.text).append("\n\n")
                        } else {
                            append(turn.text)
                        }
                    }
                }
                contentsList += Content.Text(historyText)

                trySend(InferenceStreamEvent.Status("Inferência LiteRT-LM iniciada"))
                conv.sendMessageAsync(Contents.of(contentsList)).collect { msg ->
                    val piece = extractMessageText(msg)
                    if (piece.isNotEmpty()) {
                        chunks += 1
                        accumulated.append(piece)
                        trySend(InferenceStreamEvent.Token(piece))
                        if (chunks >= maxTokensLimit) {
                            runCatching { conv.cancelProcess() }
                        }
                    }
                }
                val dur = System.currentTimeMillis() - startedAt
                trySend(
                    InferenceStreamEvent.Completed(
                        fullText = accumulated.toString().trim(),
                        durationMs = dur,
                        approxTokens = chunks.coerceAtLeast(1)
                    )
                )
                close()
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                trySend(InferenceStreamEvent.Failed(t.message ?: "Erro na inferência LiteRT-LM"))
                close()
            } finally {
                val toClose = conversation
                conversation = null
                if (toClose != null) {
                    runCatching { toClose.close() }
                }
            }
        }

        awaitClose {
            runCatching { conversation?.cancelProcess() }
            job.cancel()
        }
    }

    private fun extractMessageText(message: Message): String {
        val textParts = message.contents.contents
            .filterIsInstance<Content.Text>()
            .joinToString("") { it.text }
        return textParts.ifEmpty { message.toString() }
    }
}

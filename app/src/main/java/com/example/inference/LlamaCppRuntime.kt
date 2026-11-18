package com.example.inference

import android.content.Context
import com.example.core.model.InferenceParameters
import com.example.core.model.RuntimeBackend
import com.example.monitoring.data.LocalModelEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.nehuatl.llamacpp.LlamaHelper
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class LlamaCppRuntime(
    private val context: Context
) : LocalInferenceRuntime {

    override val backend: RuntimeBackend = RuntimeBackend.LLAMA_CPP

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    // Each helper gets an isolated event stream. A shared stream can leak stale
    // Loaded/Error/Done events across model swaps and falsely complete a load.
    private var currentEventFlow: MutableSharedFlow<LlamaHelper.LLMEvent>? = null
    private var llamaHelper: LlamaHelper? = null
    private var currentModel: LocalModelEntity? = null
    private var loadedContextLength: Int = 0

    override val loadedModelId: String?
        get() = currentModel?.id

    override suspend fun loadModel(
        model: LocalModelEntity,
        params: InferenceParameters
    ): Result<Unit> = runCatching {
        val modelFile = File(model.filePath)
        require(modelFile.exists() && modelFile.length() > 64L) {
            "Arquivo GGUF não encontrado em ${model.filePath}"
        }
        val mmprojFile = model.mmprojPath?.let { File(it) }?.takeIf { it.exists() && it.length() > 64L }
        val targetCtx = params.contextLength.coerceIn(512, 8192)

        if (
            currentModel?.id == model.id &&
            currentModel?.mmprojPath == model.mmprojPath &&
            loadedContextLength == targetCtx &&
            llamaHelper != null
        ) {
            return@runCatching
        }

        unloadModel()

        val modelEvents = MutableSharedFlow<LlamaHelper.LLMEvent>(
            replay = 0,
            extraBufferCapacity = 256,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
        val helper = LlamaHelper(
            contentResolver = context.contentResolver,
            scope = scope,
            sharedFlow = modelEvents
        )
        val loadedSignal = CompletableDeferred<Unit>()

        // Subscribe before load. Only this helper's own Loaded/Error events may
        // complete this load operation; progress callbacks are not proof of readiness.
        val observerJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            modelEvents.collect { ev ->
                when (ev) {
                    is LlamaHelper.LLMEvent.Loaded -> {
                        if (!loadedSignal.isCompleted) loadedSignal.complete(Unit)
                    }
                    is LlamaHelper.LLMEvent.Error -> {
                        if (!loadedSignal.isCompleted) {
                            loadedSignal.completeExceptionally(IllegalStateException(ev.message))
                        }
                    }
                    else -> Unit
                }
            }
        }

        try {
            helper.load(
                path = modelFile.absolutePath,
                contextLength = targetCtx,
                mmprojPath = mmprojFile?.absolutePath
            ) { _ ->
                // The library callback is progress-only; wait for LLMEvent.Loaded.
            }
            withTimeout(90_000L) {
                loadedSignal.await()
            }
            llamaHelper = helper
            currentEventFlow = modelEvents
            currentModel = model
            loadedContextLength = targetCtx
        } catch (t: Throwable) {
            runCatching { helper.release() }
            throw t
        } finally {
            observerJob.cancel()
        }
    }

    override suspend fun unloadModel() {
        val helper = llamaHelper
        llamaHelper = null
        currentEventFlow = null
        currentModel = null
        loadedContextLength = 0
        if (helper != null) {
            runCatching { helper.stopPrediction() }
            runCatching { helper.release() }
        }
    }

    override fun streamCompletion(
        systemPrompt: String,
        messages: List<ChatTurn>,
        params: InferenceParameters
    ): Flow<InferenceStreamEvent> = callbackFlow {
        val helper = llamaHelper
        val model = currentModel
        val modelEvents = currentEventFlow
        if (helper == null || model == null || modelEvents == null) {
            trySend(InferenceStreamEvent.Failed("Nenhum modelo GGUF carregado no runtime llama.cpp."))
            close()
            return@callbackFlow
        }

        val imagePath = messages.lastOrNull { it.imagePath != null }?.imagePath
        if (imagePath != null && !model.supportsVision) {
            trySend(InferenceStreamEvent.Failed("O modelo ${model.name} não possui projetor mmproj multimodal para visão."))
            close()
            return@callbackFlow
        }

        val formattedPrompt = PromptFormatter.formatChatMlPrompt(systemPrompt, messages)
        val maxTokensLimit = params.maxTokens.coerceIn(64, 4096)
        val startedAt = System.currentTimeMillis()
        var tokenCount = 0
        val accumulated = StringBuilder()
        val finished = AtomicBoolean(false)

        // UNDISPATCHED guarantees subscription is active before helper.predict() fires any event
        val collectorJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            modelEvents.collect { ev ->
                if (finished.get()) return@collect
                when (ev) {
                    is LlamaHelper.LLMEvent.Started -> {
                        trySend(InferenceStreamEvent.Status("Inferência GGUF iniciada"))
                    }
                    is LlamaHelper.LLMEvent.Ongoing -> {
                        tokenCount = ev.tokenCount.coerceAtLeast(tokenCount + 1)
                        accumulated.append(ev.word)
                        trySend(InferenceStreamEvent.Token(ev.word))
                        if (tokenCount >= maxTokensLimit && finished.compareAndSet(false, true)) {
                            runCatching { helper.stopPrediction() }
                            val dur = System.currentTimeMillis() - startedAt
                            trySend(
                                InferenceStreamEvent.Completed(
                                    fullText = accumulated.toString().trim(),
                                    durationMs = dur,
                                    approxTokens = tokenCount
                                )
                            )
                            close()
                        }
                    }
                    is LlamaHelper.LLMEvent.Done -> {
                        if (finished.compareAndSet(false, true)) {
                            val finalText = ev.fullText.ifBlank { accumulated.toString() }
                            val dur = if (ev.duration > 0L) ev.duration else (System.currentTimeMillis() - startedAt)
                            trySend(
                                InferenceStreamEvent.Completed(
                                    fullText = finalText.trim(),
                                    durationMs = dur,
                                    approxTokens = ev.tokenCount.coerceAtLeast(tokenCount)
                                )
                            )
                            close()
                        }
                    }
                    is LlamaHelper.LLMEvent.Error -> {
                        if (finished.compareAndSet(false, true)) {
                            trySend(InferenceStreamEvent.Failed(ev.message))
                            close()
                        }
                    }
                    else -> Unit
                }
            }
        }

        try {
            helper.predict(
                prompt = formattedPrompt,
                imagePath = imagePath
            )
        } catch (t: Throwable) {
            if (finished.compareAndSet(false, true)) {
                trySend(InferenceStreamEvent.Failed(t.message ?: "Erro na execução llama.cpp"))
                close()
            }
        }

        awaitClose {
            if (finished.compareAndSet(false, true)) {
                runCatching { helper.stopPrediction() }
            }
            collectorJob.cancel()
        }
    }
}

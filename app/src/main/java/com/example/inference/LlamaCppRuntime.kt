package com.example.inference

import android.content.Context
import android.net.Uri
import com.example.core.model.InferenceParameters
import com.example.core.model.RuntimeBackend
import com.example.monitoring.data.LocalModelEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.nehuatl.llamacpp.LlamaAndroid
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

// Inspect only a bounded suffix: rescanning the full generated answer for every
// token makes streaming inference quadratic as the response grows.
private fun repeatedTailStart(text: CharSequence): Int {
    val windowStart = (text.length - 1024).coerceAtLeast(0)
    val tail = text.subSequence(windowStart, text.length)
    val matches = Regex("[\\p{L}\\p{N}']+").findAll(tail).toList()
    if (matches.size < 5) return -1

    for (size in 8 downTo 2) {
        val repetitions = 3
        if (matches.size < size * repetitions) continue
        val first = matches.size - size * repetitions
        var same = true
        for (repeat in 1 until repetitions) {
            for (offset in 0 until size) {
                if (!matches[first + offset].value.equals(
                        matches[first + repeat * size + offset].value,
                        ignoreCase = true
                    )) {
                    same = false
                    break
                }
            }
            if (!same) break
        }
        if (same) return windowStart + matches[first + size].range.first
    }

    val last = matches.takeLast(5)
    if (last.all { it.value.equals(last.first().value, ignoreCase = true) }) {
        return windowStart + matches[matches.size - 4].range.first
    }
    return -1
}

private class ActiveLlamaGeneration(
    val events: SendChannel<InferenceStreamEvent>
) {
    val text = StringBuilder()
    val tokenCount = AtomicInteger(0)
    val stopRequestedForRepetition = AtomicBoolean(false)
    val finished = AtomicBoolean(false)
}

class LlamaCppRuntime(
    private val context: Context
) : LocalInferenceRuntime {

    override val backend: RuntimeBackend = RuntimeBackend.LLAMA_CPP

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    // LlamaAndroid exposes llama.cpp's native sampling arguments. LlamaHelper 0.4.0
    // hard-coded those arguments, so this runtime uses the lower-level public wrapper
    // to make the agent's saved temperature/top-p/top-k/thread settings effective.
    private val llama by lazy { LlamaAndroid(context.contentResolver) }
    private val activeGeneration = AtomicReference<ActiveLlamaGeneration?>(null)

    private var currentContextId: Int? = null
    private var currentModel: LocalModelEntity? = null
    private var loadedContextLength: Int = 0
    private var loadedThreadCount: Int = 0

    override val loadedModelId: String?
        get() = currentModel?.id

    override suspend fun loadModel(
        model: LocalModelEntity,
        params: InferenceParameters
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val modelFile = File(model.filePath)
            require(modelFile.exists() && modelFile.length() > 64L) {
                "Arquivo GGUF não encontrado em ${model.filePath}"
            }
            val mmprojFile = model.mmprojPath?.let { File(it) }?.takeIf { it.exists() && it.length() > 64L }
            val targetCtx = params.contextLength.coerceIn(512, 8192)
            val targetThreads = params.threads.coerceIn(1, 6)

            if (
                currentModel?.id == model.id &&
                currentModel?.mmprojPath == model.mmprojPath &&
                loadedContextLength == targetCtx &&
                loadedThreadCount == targetThreads &&
                currentContextId != null
            ) {
                return@withContext Result.success(Unit)
            }

            unloadModelInternal()

            val modelUri = Uri.fromFile(modelFile)
            val modelFd = context.contentResolver.openFileDescriptor(modelUri, "r")?.use { it.detachFd() }
                ?: throw IllegalArgumentException("Não foi possível abrir o arquivo GGUF: ${modelFile.name}")

            val engineConfig = mutableMapOf<String, Any>(
                "model" to modelUri.toString(),
                "model_fd" to modelFd,
                "use_mmap" to false,
                "use_mlock" to false,
                "n_ctx" to targetCtx,
                "embedding" to false,
                "n_batch" to 512,
                "n_threads" to targetThreads,
                "n_gpu_layers" to 0,
                "vocab_only" to false,
                "lora" to "",
                "lora_scaled" to 1.0,
                "rope_freq_base" to 0.0,
                "rope_freq_scale" to 0.0
            )

            mmprojFile?.let { file ->
                val mmprojUri = Uri.fromFile(file)
                val mmprojFd = context.contentResolver.openFileDescriptor(mmprojUri, "r")?.use { it.detachFd() }
                    ?: throw IllegalArgumentException("Não foi possível abrir o projetor multimodal: ${file.name}")
                engineConfig["mmproj_fd"] = mmprojFd
            }

            val engineResult = llama.startEngine(engineConfig) { token ->
                val generation = activeGeneration.get() ?: return@startEngine
                if (generation.finished.get() || generation.stopRequestedForRepetition.get()) {
                    return@startEngine
                }

                val loopStart = synchronized(generation.text) {
                    generation.text.append(token)
                    repeatedTailStart(generation.text)
                }
                generation.tokenCount.incrementAndGet()

                if (loopStart >= 0 && generation.stopRequestedForRepetition.compareAndSet(false, true)) {
                    synchronized(generation.text) {
                        if (loopStart in 0..generation.text.length) {
                            generation.text.setLength(loopStart)
                        }
                    }
                    generation.events.trySend(
                        InferenceStreamEvent.Status("Geração interrompida após detectar repetição excessiva.")
                    )
                    val contextId = currentContextId
                    if (contextId != null) {
                        scope.launch { runCatching { llama.stopCompletion(contextId) } }
                    }
                } else if (!generation.stopRequestedForRepetition.get()) {
                    generation.events.trySend(InferenceStreamEvent.Token(token))
                }
            }

            val contextId = (engineResult?.get("contextId") as? Number)?.toInt()
                ?: throw IllegalStateException("llama.cpp não conseguiu inicializar o contexto do modelo.")

            currentContextId = contextId
            currentModel = model
            loadedContextLength = targetCtx
            loadedThreadCount = targetThreads
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            unloadModelInternal()
            Result.failure(error)
        }
    }

    override suspend fun unloadModel() {
        withContext(Dispatchers.IO) {
            unloadModelInternal()
        }
    }

    private fun unloadModelInternal() {
        val contextId = currentContextId
        currentContextId = null
        currentModel = null
        loadedContextLength = 0
        loadedThreadCount = 0
        activeGeneration.getAndSet(null)?.finished?.set(true)
        if (contextId != null) {
            runCatching { llama.releaseContext(contextId) }
        }
    }

    override fun streamCompletion(
        systemPrompt: String,
        messages: List<ChatTurn>,
        params: InferenceParameters
    ): Flow<InferenceStreamEvent> = callbackFlow {
        val contextId = currentContextId
        val model = currentModel
        if (contextId == null || model == null) {
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

        val generation = ActiveLlamaGeneration(this)
        if (!activeGeneration.compareAndSet(null, generation)) {
            trySend(InferenceStreamEvent.Failed("O runtime llama.cpp já está processando outra geração."))
            close()
            return@callbackFlow
        }

        val startedAt = System.currentTimeMillis()
        var generationJob: kotlinx.coroutines.Job? = null
        try {
            val formattedPrompt = PromptFormatter.formatChatMlPrompt(systemPrompt, messages)
            val imageFd = withContext(Dispatchers.IO) {
                imagePath?.let { raw ->
                    val parsed = Uri.parse(raw)
                    val imageUri = if (parsed.scheme.isNullOrBlank()) Uri.fromFile(File(raw)) else parsed
                    context.contentResolver.openFileDescriptor(imageUri, "r")?.use { it.detachFd() }
                        ?: throw IllegalArgumentException("Não foi possível abrir a imagem enviada para a Vision AI.")
                }
            }

            val completionParams = mutableMapOf<String, Any>(
                "prompt" to formattedPrompt,
                "emit_partial_completion" to true,
                "temperature" to params.temperature.toDouble().coerceIn(0.0, 2.0),
                "top_p" to params.topP.toDouble().coerceIn(0.05, 1.0),
                "top_k" to params.topK.coerceIn(1, 100),
                "n_predict" to params.maxTokens.coerceIn(64, 4096),
                "n_threads" to params.threads.coerceIn(1, 6),
                "penalty_last_n" to 64,
                "penalty_repeat" to 1.05,
                "penalty_freq" to 0.0,
                "penalty_present" to 0.0,
                "seed" to -1
            )
            if (imageFd != null) completionParams["image_fds"] = listOf(imageFd)

            trySend(InferenceStreamEvent.Status("Inferência GGUF iniciada"))
            generationJob = scope.launch {
                try {
                    val result = llama.launchCompletion(contextId, completionParams)
                    val generatedText = synchronized(generation.text) { generation.text.toString().trim() }
                    if (result == null && !generation.stopRequestedForRepetition.get()) {
                        if (generation.finished.compareAndSet(false, true)) {
                            trySend(InferenceStreamEvent.Failed("llama.cpp encerrou a geração sem retornar um resultado válido."))
                        }
                    } else if (generation.finished.compareAndSet(false, true)) {
                        trySend(
                            InferenceStreamEvent.Completed(
                                fullText = generatedText,
                                durationMs = System.currentTimeMillis() - startedAt,
                                approxTokens = generation.tokenCount.get()
                            )
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    if (generation.finished.compareAndSet(false, true)) {
                        trySend(InferenceStreamEvent.Failed(error.message ?: "Erro durante a inferência llama.cpp."))
                    }
                } finally {
                    activeGeneration.compareAndSet(generation, null)
                    close()
                }
            }
        } catch (cancelled: CancellationException) {
            generation.finished.set(true)
            activeGeneration.compareAndSet(generation, null)
            throw cancelled
        } catch (error: Throwable) {
            if (generation.finished.compareAndSet(false, true)) {
                trySend(InferenceStreamEvent.Failed(error.message ?: "Erro ao preparar a inferência llama.cpp."))
            }
            activeGeneration.compareAndSet(generation, null)
            close()
        }

        awaitClose {
            if (generation.finished.compareAndSet(false, true)) {
                activeGeneration.compareAndSet(generation, null)
                generationJob?.cancel()
                scope.launch { runCatching { llama.stopCompletion(contextId) } }
            }
        }
    }
}

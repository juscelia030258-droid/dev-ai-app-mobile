package com.example.api_server

import android.content.Context
import android.util.Base64
import com.example.agent_engine.MultiAgentOrchestrator
import com.example.coding_agent.CodingAgent
import com.example.core.model.AgentRole
import com.example.device_manager.DeviceAccessRepository
import com.example.inference.ChatTurn
import com.example.inference.InferenceStreamEvent
import com.example.monitoring.data.AgentConfigDao
import com.example.monitoring.data.ApiRequestLogDao
import com.example.monitoring.data.ApiRequestLogEntity
import com.example.monitoring.data.LocalModelDao
import com.example.reasoning_agent.ReasoningAgent
import com.example.security.ApiKeyManager
import com.example.settings.SettingsRepository
import com.example.vision_agent.VisionAgent
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.readAvailable
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.options
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class ApiServerStatus(
    val isRunning: Boolean = false,
    val port: Int = 8080,
    val activeConnections: Int = 0,
    val totalRequestsHandled: Long = 0,
    val startedAt: Long? = null,
    val lastError: String? = null
)

class OpenAiApiServer(
    private val context: Context,
    private val apiKeyManager: ApiKeyManager,
    private val deviceAccessRepository: DeviceAccessRepository,
    private val codingAgent: CodingAgent,
    private val visionAgent: VisionAgent,
    private val reasoningAgent: ReasoningAgent,
    private val multiAgentOrchestrator: MultiAgentOrchestrator,
    private val agentConfigDao: AgentConfigDao,
    private val localModelDao: LocalModelDao,
    private val requestLogDao: ApiRequestLogDao,
    private val settingsRepository: SettingsRepository
) {
    private val maxRequestBodyBytes = 15_000_000
    private val streamEventBufferCapacity = 64

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var server: EmbeddedServer<*, *>? = null
    private val activeConnectionsCounter = AtomicInteger(0)
    private val totalRequestsCounter = AtomicInteger(0)
    private val rateWindowMap = ConcurrentHashMap<String, MutableList<Long>>()
    private val chatRequestSemaphore = Semaphore(permits = 2)

    private val _status = MutableStateFlow(ApiServerStatus())
    val status: StateFlow<ApiServerStatus> = _status.asStateFlow()

    suspend fun start(portOverride: Int? = null): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            if (server != null && _status.value.isRunning) {
                return@runCatching _status.value.port
            }
            val settings = settingsRepository.settingsFlow.first()
            val targetPort = (portOverride ?: settings.serverPort).coerceIn(1024, 65535)

            val ktorServer = serverScope.embeddedServer(CIO, port = targetPort, host = "0.0.0.0") {
                routing {
                    options("/{...}") {
                        applyCorsHeaders(call)
                        call.respond(HttpStatusCode.NoContent)
                    }

                    get("/") {
                        applyCorsHeaders(call)
                        val st = _status.value
                        call.respondText(
                            text = """{"service":"local-dev-ai","status":"ok","port":${st.port},"endpoints":["/health","/v1/models","/v1/chat/completions"]}""",
                            contentType = ContentType.Application.Json,
                            status = HttpStatusCode.OK
                        )
                    }

                    get("/health") {
                        applyCorsHeaders(call)
                        val st = _status.value
                        call.respondText(
                            text = """{"status":"ok","service":"local-dev-ai","port":${st.port},"active_connections":${st.activeConnections}}""",
                            contentType = ContentType.Application.Json,
                            status = HttpStatusCode.OK
                        )
                    }

                    get("/v1/models") {
                        applyCorsHeaders(call)
                        handleGetModels(call)
                    }

                    get("/v1/models/{id}") {
                        applyCorsHeaders(call)
                        handleGetSingleModel(call, call.parameters["id"].orEmpty())
                    }

                    post("/v1/chat/completions") {
                        applyCorsHeaders(call)
                        handleChatCompletions(call)
                    }
                }
            }
            ktorServer.startSuspend(wait = false)
            server = ktorServer
            _status.value = ApiServerStatus(
                isRunning = true,
                port = targetPort,
                activeConnections = 0,
                totalRequestsHandled = totalRequestsCounter.get().toLong(),
                startedAt = System.currentTimeMillis(),
                lastError = null
            )
            targetPort
        }.onFailure { err ->
            _status.value = _status.value.copy(
                isRunning = false,
                lastError = err.message ?: "Falha ao iniciar servidor na porta solicitada"
            )
        }
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        val current = server
        server = null
        if (current != null) {
            runCatching { current.stopSuspend(gracePeriodMillis = 400, timeoutMillis = 1500) }
        }
        _status.value = _status.value.copy(
            isRunning = false,
            activeConnections = 0,
            startedAt = null
        )
    }

    private fun applyCorsHeaders(call: ApplicationCall) {
        call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
        call.response.header(HttpHeaders.AccessControlAllowMethods, "GET, POST, OPTIONS")
        call.response.header(HttpHeaders.AccessControlAllowHeaders, "Authorization, Content-Type, x-api-key")
    }

    private suspend fun buildOpenAiModelsList(): List<OpenAiModelInfo> {
        val allConfigs = agentConfigDao.getAllOnce().associateBy { it.roleId }
        val allModels = localModelDao.getAllOnce().associateBy { it.id }
        val createdEpoch = System.currentTimeMillis() / 1000L

        val codingLocal = allConfigs[AgentRole.CODING.id]?.assignedModelId?.let { allModels[it] }
        val visionLocal = allConfigs[AgentRole.VISION.id]?.assignedModelId?.let { allModels[it] }
        val reasoningLocal = allConfigs[AgentRole.REASONING.id]?.assignedModelId?.let { allModels[it] }

        return listOf(
            OpenAiModelInfo(
                id = AgentRole.CODING.apiModelId,
                created = createdEpoch,
                description = "Agente especialista em programação local",
                assignedLocalModel = codingLocal?.name,
                supportsVision = false
            ),
            OpenAiModelInfo(
                id = AgentRole.VISION.apiModelId,
                created = createdEpoch,
                description = "Agente multimodal de visão computacional",
                assignedLocalModel = visionLocal?.name,
                supportsVision = visionLocal?.supportsVision == true
            ),
            OpenAiModelInfo(
                id = AgentRole.REASONING.apiModelId,
                created = createdEpoch,
                description = "Agente central de raciocínio e planejamento",
                assignedLocalModel = reasoningLocal?.name,
                supportsVision = false
            ),
            OpenAiModelInfo(
                id = AgentRole.MULTIAGENT_MODEL_ID,
                created = createdEpoch,
                description = "Orquestração multiagente coordenada pela Reasoning AI",
                assignedLocalModel = reasoningLocal?.name,
                supportsVision = visionLocal?.supportsVision == true
            )
        )
    }

    private suspend fun handleGetModels(call: ApplicationCall) {
        val startMs = System.currentTimeMillis()
        val auth = authenticateAndValidate(call, requestedModel = null) ?: return
        activeConnectionsCounter.incrementAndGet()
        syncCounters()
        try {
            val payload = OpenAiModelsResponse(
                data = buildOpenAiModelsList().filter { auth.permission.allows(it.id) }
            )
            call.respondText(
                text = json.encodeToString(payload),
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.OK
            )
            logRequest(
                method = "GET",
                path = "/v1/models",
                model = null,
                remoteIp = call.request.origin.remoteHost,
                keyName = auth.keyEntity.name,
                statusCode = 200,
                latencyMs = System.currentTimeMillis() - startMs,
                streaming = false,
                summary = "Listou 4 endpoints de agentes locais"
            )
        } finally {
            activeConnectionsCounter.decrementAndGet()
            syncCounters()
        }
    }

    private suspend fun handleGetSingleModel(call: ApplicationCall, rawModelId: String) {
        val startMs = System.currentTimeMillis()
        val auth = authenticateAndValidate(call, requestedModel = null) ?: return
        activeConnectionsCounter.incrementAndGet()
        syncCounters()
        try {
            val models = buildOpenAiModelsList()
            val found = models.firstOrNull { it.id.equals(rawModelId, ignoreCase = true) }
            if (found == null) {
                respondOpenAiError(
                    call,
                    HttpStatusCode.NotFound,
                    "Modelo '$rawModelId' não encontrado. Use GET /v1/models para listar os agentes disponíveis.",
                    "invalid_request_error",
                    "model_not_found"
                )
                logRequest(
                    method = "GET",
                    path = "/v1/models/$rawModelId",
                    model = rawModelId,
                    remoteIp = call.request.origin.remoteHost,
                    keyName = auth.keyEntity.name,
                    statusCode = 404,
                    latencyMs = System.currentTimeMillis() - startMs,
                    streaming = false,
                    summary = "Modelo não encontrado"
                )
            } else if (!auth.permission.allows(found.id)) {
                respondOpenAiError(
                    call,
                    HttpStatusCode.Forbidden,
                    "A chave '${auth.keyEntity.name}' não possui permissão para o modelo '${found.id}'.",
                    "permission_error",
                    "insufficient_key_scope"
                )
                logRequest(
                    method = "GET",
                    path = "/v1/models/$rawModelId",
                    model = found.id,
                    remoteIp = call.request.origin.remoteHost,
                    keyName = auth.keyEntity.name,
                    statusCode = 403,
                    latencyMs = System.currentTimeMillis() - startMs,
                    streaming = false,
                    summary = "Escopo negado"
                )
            } else {
                call.respondText(
                    text = json.encodeToString(found),
                    contentType = ContentType.Application.Json,
                    status = HttpStatusCode.OK
                )
                logRequest(
                    method = "GET",
                    path = "/v1/models/$rawModelId",
                    model = found.id,
                    remoteIp = call.request.origin.remoteHost,
                    keyName = auth.keyEntity.name,
                    statusCode = 200,
                    latencyMs = System.currentTimeMillis() - startMs,
                    streaming = false,
                    summary = "Detalhes de ${found.id}"
                )
            }
        } finally {
            activeConnectionsCounter.decrementAndGet()
            syncCounters()
        }
    }

    private suspend fun handleChatCompletions(call: ApplicationCall) {
        // Authentication and request validation happen before consuming an inference slot.
        handleChatCompletionsWithSlot(call)
    }

    private suspend fun readBodyAtMost(call: ApplicationCall, maxBytes: Int): ByteArray? {
        val channel = call.receiveChannel()
        val output = java.io.ByteArrayOutputStream(minOf(maxBytes, 8 * 1024))
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val remainingWithOverflowByte = maxBytes - output.size() + 1
            if (remainingWithOverflowByte <= 0) return null
            val count = channel.readAvailable(buffer, 0, minOf(buffer.size, remainingWithOverflowByte))
            if (count == -1) return output.toByteArray()
            if (count == 0) {
                // No bytes are currently buffered; suspend until data arrives or EOF is reached.
                // Avoid a tight CPU loop while a chunked client uploads the request slowly.
                channel.awaitContent(1)
                continue
            }
            output.write(buffer, 0, count)
            if (output.size() > maxBytes) return null
        }
    }

    private suspend fun handleChatCompletionsWithSlot(call: ApplicationCall) {
        val startMs = System.currentTimeMillis()
        val remoteIp = call.request.origin.remoteHost

        // Authenticate LAN origin, Bearer token, blocked device, and rate limit BEFORE reading body
        val auth = authenticateAndValidate(call, requestedModel = null) ?: return

        val declaredLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (declaredLength != null && declaredLength > maxRequestBodyBytes.toLong()) {
            respondOpenAiError(
                call,
                HttpStatusCode(413, "Payload Too Large"),
                "Corpo JSON excede o limite de 15 MB.",
                "invalid_request_error",
                "body_too_large"
            )
            return
        }

        // Enforce the cap while streaming, including chunked requests without Content-Length.
        val bodyBytes = try {
            readBodyAtMost(call, maxRequestBodyBytes)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (bodyBytes == null) {
            respondOpenAiError(
                call,
                HttpStatusCode(413, "Payload Too Large"),
                "Corpo JSON excede o limite de 15 MB.",
                "invalid_request_error",
                "body_too_large"
            )
            return
        }
        val rawBody = bodyBytes.toString(Charsets.UTF_8)
        if (rawBody.isBlank()) {
            respondOpenAiError(
                call,
                HttpStatusCode.BadRequest,
                "Corpo JSON vazio.",
                "invalid_request_error",
                "invalid_body"
            )
            return
        }

        val req = runCatching { json.decodeFromString<ChatCompletionRequest>(rawBody) }.getOrElse { err ->
            respondOpenAiError(
                call,
                HttpStatusCode.BadRequest,
                "JSON inválido para /v1/chat/completions: ${err.message}",
                "invalid_request_error",
                "malformed_json"
            )
            return
        }

        val targetModelId = resolveTargetModelId(req.model, req.agent)
        if (targetModelId == null) {
            respondOpenAiError(
                call,
                HttpStatusCode.NotFound,
                "Modelo '${req.agent?.takeIf { it.isNotBlank() } ?: req.model}' não encontrado. Consulte GET /v1/models.",
                "invalid_request_error",
                "model_not_found"
            )
            logRequest("POST", "/v1/chat/completions", req.model, remoteIp, auth.keyEntity.name, 404, System.currentTimeMillis() - startMs, req.stream, "Modelo não encontrado")
            return
        }
        if (!auth.permission.allows(targetModelId)) {
            respondOpenAiError(
                call,
                HttpStatusCode.Forbidden,
                "A chave '${auth.keyEntity.name}' não possui permissão para o modelo '$targetModelId'.",
                "permission_error",
                "insufficient_key_scope"
            )
            logRequest("POST", "/v1/chat/completions", targetModelId, remoteIp, auth.keyEntity.name, 403, System.currentTimeMillis() - startMs, req.stream, "Escopo negado para $targetModelId")
            return
        }

        if (req.messages.isEmpty()) {
            respondOpenAiError(
                call,
                HttpStatusCode.BadRequest,
                "O campo 'messages' não pode estar vazio.",
                "invalid_request_error",
                "empty_messages"
            )
            return
        }

        // Reserve capacity before decoding image payloads or creating temporary files.
        // JSON, model permissions and message presence have already been validated above.
        if (!chatRequestSemaphore.tryAcquire()) {
            respondOpenAiError(
                call,
                HttpStatusCode.ServiceUnavailable,
                "O servidor já está processando duas requisições. Aguarde uma delas terminar e tente novamente.",
                "server_busy",
                "concurrency_limit"
            )
            return
        }

        activeConnectionsCounter.incrementAndGet()
        syncCounters()
        var tempImageFiles: List<File> = emptyList()

        try {
            val parsedResult = parseMessagesWithOptionalImages(req.messages)
            val chatTurns = parsedResult.first
            tempImageFiles = parsedResult.second

            val completionId = "chatcmpl-${UUID.randomUUID().toString().take(12)}"
            val createdEpoch = System.currentTimeMillis() / 1000L

            if (targetModelId == AgentRole.MULTIAGENT_MODEL_ID) {
                val combinedPrompt = chatTurns.joinToString("\n\n") { "${it.role}: ${it.text}" }
                val latestImage = chatTurns.lastOrNull { it.imagePath != null }?.imagePath
                if (req.stream) {
                    coroutineScope {
                        val deferred = async {
                            multiAgentOrchestrator.executeMultiAgentTask(
                                userRequest = combinedPrompt,
                                imagePath = latestImage,
                                source = "API"
                            )
                        }
                        val fastResult = withTimeoutOrNull(250L) { deferred.await() }
                        if (fastResult != null) {
                            if (fastResult.isFailure) {
                                val msg = fastResult.exceptionOrNull()?.message ?: "Falha na execução multiagente"
                                respondOpenAiError(call, HttpStatusCode.UnprocessableEntity, msg, "inference_error", "multiagent_failed")
                                logRequest("POST", "/v1/chat/completions", targetModelId, remoteIp, auth.keyEntity.name, 422, System.currentTimeMillis() - startMs, true, msg)
                            } else {
                                val finalAnswer = fastResult.getOrThrow().finalResponse
                                streamStaticTextAsSse(call, completionId, createdEpoch, targetModelId, finalAnswer)
                                logRequest("POST", "/v1/chat/completions", targetModelId, remoteIp, auth.keyEntity.name, 200, System.currentTimeMillis() - startMs, true, "Multiagente concluído")
                            }
                        } else {
                            call.response.header(HttpHeaders.CacheControl, "no-cache")
                            call.response.header(HttpHeaders.Connection, "keep-alive")
                            var streamErr: String? = null
                            var streamedChars = 0
                            call.respondTextWriter(contentType = ContentType.Text.EventStream, status = HttpStatusCode.OK) {
                                while (!deferred.isCompleted) {
                                    write(": local-dev-multiagent running\n\n")
                                    flush()
                                    delay(3500L)
                                }
                                val res = deferred.await()
                                if (res.isFailure) {
                                    val msg = res.exceptionOrNull()?.message ?: "Falha na execução multiagente"
                                    streamErr = msg
                                    val errObj = OpenAiErrorEnvelope(
                                        OpenAiErrorBody(message = msg, type = "inference_error", code = "multiagent_failed")
                                    )
                                    write("data: ${json.encodeToString(errObj)}\n\n")
                                    write("data: [DONE]\n\n")
                                    flush()
                                } else {
                                    val text = res.getOrThrow().finalResponse
                                    streamedChars = text.length
                                    writeStaticTextChunksToWriter(this, completionId, createdEpoch, targetModelId, text)
                                }
                            }
                            logRequest(
                                "POST",
                                "/v1/chat/completions",
                                targetModelId,
                                remoteIp,
                                auth.keyEntity.name,
                                if (streamErr == null) 200 else 422,
                                System.currentTimeMillis() - startMs,
                                true,
                                streamErr ?: "Multiagente SSE ($streamedChars chars)"
                            )
                        }
                    }
                    return
                }

                val multiRes = multiAgentOrchestrator.executeMultiAgentTask(
                    userRequest = combinedPrompt,
                    imagePath = latestImage,
                    source = "API"
                )
                if (multiRes.isFailure) {
                    val msg = multiRes.exceptionOrNull()?.message ?: "Falha na execução multiagente"
                    respondOpenAiError(call, HttpStatusCode.UnprocessableEntity, msg, "inference_error", "multiagent_failed")
                    logRequest("POST", "/v1/chat/completions", targetModelId, remoteIp, auth.keyEntity.name, 422, System.currentTimeMillis() - startMs, false, msg)
                    return
                }
                val finalAnswer = multiRes.getOrThrow().finalResponse
                respondFullCompletion(call, completionId, createdEpoch, targetModelId, finalAnswer, chatTurns)
                logRequest("POST", "/v1/chat/completions", targetModelId, remoteIp, auth.keyEntity.name, 200, System.currentTimeMillis() - startMs, false, "Multiagente concluído")
                return
            }

            val effectiveMaxTokens = req.maxTokens ?: settingsRepository.settingsFlow.first().defaultMaxTokens
            val role = AgentRole.fromApiModelId(targetModelId) ?: AgentRole.CODING
            val eventFlow = when (role) {
                AgentRole.CODING -> codingAgent.streamExecute(chatTurns, "API", req.temperature, effectiveMaxTokens)
                AgentRole.VISION -> visionAgent.streamExecute(chatTurns, "API", req.temperature, effectiveMaxTokens)
                AgentRole.REASONING -> reasoningAgent.streamExecute(chatTurns, "API", req.temperature, effectiveMaxTokens)
            }

            if (req.stream) {
                coroutineScope {
                    // Bounded buffering applies backpressure when a client reads SSE slowly.
                    // This prevents generated tokens from accumulating without a memory limit.
                    val eventChannel = Channel<InferenceStreamEvent>(capacity = streamEventBufferCapacity)
                    val producerJob = launch {
                        try {
                            eventFlow.collect { eventChannel.send(it) }
                        } finally {
                            eventChannel.close()
                        }
                    }

                    val firstEvent = eventChannel.receiveCatching().getOrNull()
                    if (firstEvent == null) {
                        producerJob.cancel()
                        respondOpenAiError(
                            call,
                            HttpStatusCode.UnprocessableEntity,
                            "Inferência encerrada sem saída.",
                            "inference_error",
                            "local_inference_failed"
                        )
                        logRequest("POST", "/v1/chat/completions", targetModelId, remoteIp, auth.keyEntity.name, 422, System.currentTimeMillis() - startMs, true, "Sem saída")
                        return@coroutineScope
                    }

                    if (firstEvent is InferenceStreamEvent.Failed) {
                        producerJob.cancel()
                        respondOpenAiError(
                            call,
                            HttpStatusCode.UnprocessableEntity,
                            firstEvent.errorMessage,
                            "inference_error",
                            "local_inference_failed"
                        )
                        logRequest("POST", "/v1/chat/completions", targetModelId, remoteIp, auth.keyEntity.name, 422, System.currentTimeMillis() - startMs, true, firstEvent.errorMessage)
                        return@coroutineScope
                    }

                    call.response.header(HttpHeaders.CacheControl, "no-cache")
                    call.response.header(HttpHeaders.Connection, "keep-alive")
                    var sentRoleChunk = false
                    var failedMessage: String? = null
                    val fullText = StringBuilder()

                    call.respondTextWriter(contentType = ContentType.Text.EventStream, status = HttpStatusCode.OK) {
                        suspend fun emitEvent(ev: InferenceStreamEvent) {
                            when (ev) {
                                is InferenceStreamEvent.Token -> {
                                    fullText.append(ev.text)
                                    val delta = if (!sentRoleChunk) {
                                        sentRoleChunk = true
                                        ChatChunkDelta(role = "assistant", content = ev.text)
                                    } else {
                                        ChatChunkDelta(content = ev.text)
                                    }
                                    val chunk = ChatCompletionChunkResponse(
                                        id = completionId,
                                        created = createdEpoch,
                                        model = targetModelId,
                                        choices = listOf(ChatChunkChoice(delta = delta, finishReason = null))
                                    )
                                    write("data: ${json.encodeToString(chunk)}\n\n")
                                    flush()
                                }
                                is InferenceStreamEvent.Completed -> {
                                    val stopChunk = ChatCompletionChunkResponse(
                                        id = completionId,
                                        created = createdEpoch,
                                        model = targetModelId,
                                        choices = listOf(ChatChunkChoice(delta = ChatChunkDelta(), finishReason = "stop"))
                                    )
                                    write("data: ${json.encodeToString(stopChunk)}\n\n")
                                    write("data: [DONE]\n\n")
                                    flush()
                                }
                                is InferenceStreamEvent.Failed -> {
                                    failedMessage = ev.errorMessage
                                    val errObj = OpenAiErrorEnvelope(
                                        OpenAiErrorBody(
                                            message = ev.errorMessage,
                                            type = "inference_error",
                                            code = "local_inference_failed"
                                        )
                                    )
                                    write("data: ${json.encodeToString(errObj)}\n\n")
                                    write("data: [DONE]\n\n")
                                    flush()
                                }
                                else -> Unit
                            }
                        }

                        emitEvent(firstEvent)
                        for (ev in eventChannel) {
                            emitEvent(ev)
                        }
                    }
                    logRequest(
                        "POST",
                        "/v1/chat/completions",
                        targetModelId,
                        remoteIp,
                        auth.keyEntity.name,
                        if (failedMessage == null) 200 else 422,
                        System.currentTimeMillis() - startMs,
                        true,
                        failedMessage ?: "SSE stream (${fullText.length} chars)"
                    )
                }
            } else {
                var finalOutput: String? = null
                var errorMessage: String? = null
                eventFlow.collect { ev ->
                    when (ev) {
                        is InferenceStreamEvent.Completed -> finalOutput = ev.fullText
                        is InferenceStreamEvent.Failed -> errorMessage = ev.errorMessage
                        else -> Unit
                    }
                }
                if (errorMessage != null || finalOutput == null) {
                    val msg = errorMessage ?: "Falha desconhecida na inferência local."
                    respondOpenAiError(call, HttpStatusCode.UnprocessableEntity, msg, "inference_error", "local_inference_failed")
                    logRequest("POST", "/v1/chat/completions", targetModelId, remoteIp, auth.keyEntity.name, 422, System.currentTimeMillis() - startMs, false, msg)
                } else {
                    val completedText = finalOutput.orEmpty()
                    respondFullCompletion(call, completionId, createdEpoch, targetModelId, completedText, chatTurns)
                    logRequest("POST", "/v1/chat/completions", targetModelId, remoteIp, auth.keyEntity.name, 200, System.currentTimeMillis() - startMs, false, "Concluído (${completedText.length} chars)")
                }
            }
        } finally {
            tempImageFiles.forEach { runCatching { it.delete() } }
            activeConnectionsCounter.decrementAndGet()
            syncCounters()
            chatRequestSemaphore.release()
        }
    }

    private suspend fun authenticateAndValidate(
        call: ApplicationCall,
        requestedModel: String?
    ): com.example.security.AuthenticatedKeyResult? {
        val remoteIp = call.request.origin.remoteHost
        val userAgent = call.request.header(HttpHeaders.UserAgent).orEmpty()
        val settings = settingsRepository.settingsFlow.first()

        if (settings.lanOnlyMode && !LocalNetworkDiscovery.isPrivateLanAddress(remoteIp)) {
            respondOpenAiError(
                call,
                HttpStatusCode.Forbidden,
                "Acesso bloqueado: servidor configurado apenas para rede local Wi-Fi (LAN).",
                "permission_error",
                "lan_only_enforced"
            )
            logRequest(call.request.local.method.value, call.request.local.uri, requestedModel, remoteIp, null, 403, 0L, false, "Bloqueado fora da LAN")
            return null
        }

        val authHeader = call.request.header(HttpHeaders.Authorization)
        val bearerToken = when {
            authHeader?.startsWith("Bearer ", ignoreCase = true) == true -> authHeader.substring(7).trim()
            !call.request.header("x-api-key").isNullOrBlank() -> call.request.header("x-api-key")!!.trim()
            else -> null
        }

        if (bearerToken.isNullOrBlank()) {
            respondOpenAiError(
                call,
                HttpStatusCode.Unauthorized,
                "Autenticação obrigatória. Envie 'Authorization: Bearer <API_KEY>'.",
                "authentication_error",
                "missing_api_key"
            )
            logRequest("AUTH", call.request.local.uri, requestedModel, remoteIp, null, 401, 0L, false, "Chave ausente")
            return null
        }

        val auth = apiKeyManager.authenticateBearerToken(bearerToken, remoteIp)
        if (auth == null) {
            respondOpenAiError(
                call,
                HttpStatusCode.Unauthorized,
                "Chave API inválida ou revogada.",
                "authentication_error",
                "invalid_or_revoked_key"
            )
            logRequest("AUTH", call.request.local.uri, requestedModel, remoteIp, null, 401, 0L, false, "Chave inválida ou revogada")
            return null
        }

        if (deviceAccessRepository.isDeviceBlocked(remoteIp, userAgent, auth.keyEntity.id)) {
            respondOpenAiError(
                call,
                HttpStatusCode.Forbidden,
                "Este dispositivo foi bloqueado no Gerenciador de Dispositivos.",
                "permission_error",
                "device_blocked"
            )
            logRequest("AUTH", call.request.local.uri, requestedModel, remoteIp, auth.keyEntity.name, 403, 0L, false, "Dispositivo bloqueado")
            return null
        }

        if (requestedModel != null && !auth.permission.allows(requestedModel)) {
            respondOpenAiError(
                call,
                HttpStatusCode.Forbidden,
                "A chave '${auth.keyEntity.name}' não possui permissão para o modelo '$requestedModel'.",
                "permission_error",
                "insufficient_key_scope"
            )
            logRequest("AUTH", call.request.local.uri, requestedModel, remoteIp, auth.keyEntity.name, 403, 0L, false, "Escopo negado para $requestedModel")
            return null
        }

        if (!checkRateLimit(auth.keyEntity.id, settings.rateLimitPerMinute)) {
            respondOpenAiError(
                call,
                HttpStatusCode.TooManyRequests,
                "Limite de requisições por minuto excedido para esta chave.",
                "rate_limit_error",
                "rate_limit_exceeded"
            )
            logRequest("RATE", call.request.local.uri, requestedModel, remoteIp, auth.keyEntity.name, 429, 0L, false, "Rate limit excedido")
            return null
        }

        deviceAccessRepository.recordDeviceActivity(remoteIp, userAgent, auth.keyEntity)
        return auth
    }

    private fun checkRateLimit(keyId: String, limitPerMinute: Int): Boolean {
        val now = System.currentTimeMillis()
        val window = rateWindowMap.getOrPut(keyId) { mutableListOf() }
        synchronized(window) {
            window.removeAll { now - it > 60_000L }
            if (window.size >= limitPerMinute) return false
            window.add(now)
            return true
        }
    }

    private suspend fun respondFullCompletion(
        call: ApplicationCall,
        completionId: String,
        createdEpoch: Long,
        modelId: String,
        answer: String,
        turns: List<ChatTurn>
    ) {
        val promptTok = (turns.sumOf { it.text.length } / 4).coerceAtLeast(1)
        val compTok = (answer.length / 4).coerceAtLeast(1)
        val resp = ChatCompletionResponse(
            id = completionId,
            created = createdEpoch,
            model = modelId,
            choices = listOf(
                ChatCompletionChoice(
                    message = AssistantMessageDto(content = answer),
                    finishReason = "stop"
                )
            ),
            usage = TokenUsageDto(
                promptTokens = promptTok,
                completionTokens = compTok,
                totalTokens = promptTok + compTok
            )
        )
        call.respondText(
            text = json.encodeToString(resp),
            contentType = ContentType.Application.Json,
            status = HttpStatusCode.OK
        )
    }

    private suspend fun streamStaticTextAsSse(
        call: ApplicationCall,
        completionId: String,
        createdEpoch: Long,
        modelId: String,
        text: String
    ) {
        call.response.header(HttpHeaders.CacheControl, "no-cache")
        call.response.header(HttpHeaders.Connection, "keep-alive")
        call.respondTextWriter(contentType = ContentType.Text.EventStream, status = HttpStatusCode.OK) {
            writeStaticTextChunksToWriter(this, completionId, createdEpoch, modelId, text)
        }
    }

    private fun writeStaticTextChunksToWriter(
        writer: java.io.Writer,
        completionId: String,
        createdEpoch: Long,
        modelId: String,
        text: String
    ) {
        val parts = text.chunked(48).ifEmpty { listOf("") }
        parts.forEachIndexed { idx, part ->
            val delta = if (idx == 0) ChatChunkDelta(role = "assistant", content = part) else ChatChunkDelta(content = part)
            val chunk = ChatCompletionChunkResponse(
                id = completionId,
                created = createdEpoch,
                model = modelId,
                choices = listOf(ChatChunkChoice(delta = delta, finishReason = null))
            )
            writer.write("data: ${json.encodeToString(chunk)}\n\n")
            writer.flush()
        }
        val endChunk = ChatCompletionChunkResponse(
            id = completionId,
            created = createdEpoch,
            model = modelId,
            choices = listOf(ChatChunkChoice(delta = ChatChunkDelta(), finishReason = "stop"))
        )
        writer.write("data: ${json.encodeToString(endChunk)}\n\n")
        writer.write("data: [DONE]\n\n")
        writer.flush()
    }

    private suspend fun respondOpenAiError(
        call: ApplicationCall,
        status: HttpStatusCode,
        message: String,
        type: String,
        code: String
    ) {
        val body = OpenAiErrorEnvelope(
            OpenAiErrorBody(message = message, type = type, code = code)
        )
        call.respondText(
            text = json.encodeToString(body),
            contentType = ContentType.Application.Json,
            status = status
        )
    }

    private fun resolveTargetModelId(requestedModel: String, agentParam: String?): String? {
        val candidate = (agentParam?.takeIf { it.isNotBlank() } ?: requestedModel).trim().lowercase()
        return when (candidate) {
            "local-dev-coding", "coding" -> AgentRole.CODING.apiModelId
            "local-dev-vision", "vision" -> AgentRole.VISION.apiModelId
            "local-dev-reasoning", "reasoning" -> AgentRole.REASONING.apiModelId
            "local-dev-multiagent", "multiagent" -> AgentRole.MULTIAGENT_MODEL_ID
            else -> null
        }
    }

    private fun parseMessagesWithOptionalImages(
        dtos: List<ChatMessageDto>
    ): Pair<List<ChatTurn>, List<File>> {
        val tempFiles = mutableListOf<File>()
        val cacheImgDir = File(context.cacheDir, "api_images").apply { mkdirs() }

        val turns = dtos.map { dto ->
            val elem = dto.content
            when (elem) {
                is JsonPrimitive -> ChatTurn(role = dto.role, text = elem.contentOrNull ?: "")
                is JsonArray -> {
                    val textBuf = StringBuilder()
                    var imgPath: String? = null
                    elem.forEach { part ->
                        val obj = runCatching { part.jsonObject }.getOrNull() ?: return@forEach
                        val type = obj["type"]?.jsonPrimitive?.contentOrNull
                        if (type == "text") {
                            textBuf.append(obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty()).append(' ')
                        } else if (type == "image_url") {
                            val urlStr = obj["image_url"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
                            if (urlStr != null && urlStr.startsWith("data:image", ignoreCase = true)) {
                                val base64Part = urlStr.substringAfter("base64,", "")
                                if (base64Part.isNotBlank()) {
                                    runCatching {
                                        val bytes = Base64.decode(base64Part, Base64.DEFAULT)
                                        val imgFile = File(cacheImgDir, "api_img_${UUID.randomUUID().toString().take(8)}.jpg")
                                        imgFile.writeBytes(bytes)
                                        tempFiles += imgFile
                                        imgPath = imgFile.absolutePath
                                    }
                                }
                            }
                        }
                    }
                    ChatTurn(role = dto.role, text = textBuf.toString().trim(), imagePath = imgPath)
                }
                else -> ChatTurn(role = dto.role, text = elem.toString())
            }
        }
        return turns to tempFiles
    }

    private suspend fun logRequest(
        method: String,
        path: String,
        model: String?,
        remoteIp: String,
        keyName: String?,
        statusCode: Int,
        latencyMs: Long,
        streaming: Boolean,
        summary: String
    ) {
        totalRequestsCounter.incrementAndGet()
        syncCounters()
        runCatching {
            requestLogDao.insert(
                ApiRequestLogEntity(
                    method = method,
                    path = path,
                    modelRequested = model,
                    remoteIp = remoteIp,
                    keyName = keyName,
                    statusCode = statusCode,
                    latencyMs = latencyMs,
                    streaming = streaming,
                    summary = summary.take(240)
                )
            )
        }
    }

    private fun syncCounters() {
        _status.value = _status.value.copy(
            activeConnections = activeConnectionsCounter.get().coerceAtLeast(0),
            totalRequestsHandled = totalRequestsCounter.get().toLong()
        )
    }
}

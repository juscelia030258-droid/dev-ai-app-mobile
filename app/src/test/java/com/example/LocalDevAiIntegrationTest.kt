package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.api_server.LocalNetworkDiscovery
import com.example.core.model.CompatibilityStatus
import com.example.core.model.ModelFormat
import com.example.inference.ModelValidator
import com.example.security.KeyPermission
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalDevAiIntegrationTest {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    @Test
    fun `permanent primary api key persists across restarts and supports regeneration and revocation`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val container = (context.applicationContext as LocalDevAiApplication).container

        val firstKey = container.apiKeyManager.ensurePermanentPrimaryKey()
        val secondKey = container.apiKeyManager.ensurePermanentPrimaryKey()
        assertEquals(firstKey.keyHashSha256, secondKey.keyHashSha256)
        assertTrue(firstKey.isActive)

        val rawPlain = container.apiKeyManager.decryptKeyForDisplay(firstKey)
        assertNotNull(rawPlain)
        assertTrue(rawPlain!!.startsWith("ldai_live_"))

        val authOk = container.apiKeyManager.authenticateBearerToken(rawPlain, "192.168.1.50")
        assertNotNull(authOk)

        container.apiKeyManager.revokePrimaryKey()
        val authRevoked = container.apiKeyManager.authenticateBearerToken(rawPlain, "192.168.1.50")
        assertEquals(null, authRevoked)

        val regenerated = container.apiKeyManager.regeneratePrimaryKey()
        assertTrue(regenerated.isActive)
        assertNotEquals(firstKey.keyHashSha256, regenerated.keyHashSha256)
    }

    @Test
    fun `memory budget guard and gguf validator enforce hardware limits`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val container = (context.applicationContext as LocalDevAiApplication).container

        val adaptiveHeadroom = container.deviceProfiler.recommendedMemoryHeadroomMb()
        assertTrue("Adaptive headroom must remain bounded", adaptiveHeadroom in 256L..512L)
        val presetIds = container.modelRepository.recommendedPresets.map { it.id }
        assertTrue(presetIds.contains("phi4_mini_instruct_q4_k_m"))
        assertTrue(presetIds.contains("qwen2_5_coder_3b_q4_k_m"))

        val (fitsHuge, reason) = container.memoryBudgetManager.checkCanFitModel(
            requestedFootprintMb = 9200L,
            currentlyLoadedMb = 0L,
            willUnloadOthers = true
        )
        assertFalse(fitsHuge)
        assertNotNull(reason)

        val baseModelFootprint = container.memoryBudgetManager.estimateGgufFootprintMb(
            fileSizeBytes = 2_000L * 1024L * 1024L,
            contextLength = 2048
        )
        val visionModelFootprint = container.memoryBudgetManager.estimateGgufFootprintMb(
            fileSizeBytes = 2_000L * 1024L * 1024L,
            contextLength = 2048,
            mmprojSizeBytes = 100L * 1024L * 1024L
        )
        assertTrue("Vision projector must be included in RAM estimate", visionModelFootprint >= baseModelFootprint + 100L)

        val validGguf = File(context.cacheDir, "unit_valid.gguf")
        val textOnlyGguf = File(context.cacheDir, "unit_text_vision.gguf")
        val corruptFile = File(context.cacheDir, "unit_corrupt.gguf")
        try {
            val header = ByteArray(128).apply {
                this[0] = 'G'.code.toByte()
                this[1] = 'G'.code.toByte()
                this[2] = 'U'.code.toByte()
                this[3] = 'F'.code.toByte()
                this[4] = 3
            }
            validGguf.writeBytes(header)
            textOnlyGguf.writeBytes(header)
            corruptFile.writeBytes(ByteArray(128))

            val resValid = ModelValidator.validateModelFile(validGguf, null, 3800L, supportsVisionFlag = false)
            assertEquals(ModelFormat.GGUF, resValid.format)
            assertEquals(CompatibilityStatus.COMPATIBLE, resValid.status)

            val resMissingProj = ModelValidator.validateModelFile(textOnlyGguf, null, 3800L, supportsVisionFlag = true)
            assertEquals(CompatibilityStatus.MISSING_VISION_PROJECTOR, resMissingProj.status)
            assertFalse(resMissingProj.supportsVision)

            val resCorrupt = ModelValidator.validateModelFile(corruptFile, null, 3800L)
            assertEquals(CompatibilityStatus.CORRUPTED_OR_INVALID, resCorrupt.status)
        } finally {
            validGguf.delete()
            textOnlyGguf.delete()
            corruptFile.delete()
        }
    }

    @Test
    fun `real ktor openai api server enforces auth, scopes, revocation, and lan filtering`() = runBlocking {
        kotlinx.coroutines.withTimeout(20_000L) {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val container = (context.applicationContext as LocalDevAiApplication).container
            val freePort = ServerSocket(0).use { it.localPort }

            val startRes = container.openAiApiServer.start(freePort)
            assertTrue("Expected Ktor server to start: ${startRes.exceptionOrNull()}", startRes.isSuccess)

            try {
                val primary = container.apiKeyManager.ensurePermanentPrimaryKey()
                val primaryRaw = container.apiKeyManager.decryptKeyForDisplay(primary)!!

                val healthCode = httpClient.newCall(
                    Request.Builder().url("http://127.0.0.1:$freePort/health").build()
                ).execute().use { it.code }
                assertEquals(200, healthCode)

                val unauthCode = httpClient.newCall(
                    Request.Builder().url("http://127.0.0.1:$freePort/v1/models").build()
                ).execute().use { it.code }
                assertEquals(401, unauthCode)

                val badKeyCode = httpClient.newCall(
                    Request.Builder()
                        .url("http://127.0.0.1:$freePort/v1/models")
                        .header("Authorization", "Bearer ldai_invalid_123")
                        .build()
                ).execute().use { it.code }
                assertEquals(401, badKeyCode)

                val modelsBody = httpClient.newCall(
                    Request.Builder()
                        .url("http://127.0.0.1:$freePort/v1/models")
                        .header("Authorization", "Bearer $primaryRaw")
                        .build()
                ).execute().use { resp ->
                    assertEquals(200, resp.code)
                    resp.body?.string().orEmpty()
                }
                assertTrue(modelsBody.contains("local-dev-coding"))
                assertTrue(modelsBody.contains("local-dev-vision"))
                assertTrue(modelsBody.contains("local-dev-reasoning"))
                assertTrue(modelsBody.contains("local-dev-multiagent"))

                val (codingKey, codingSecret) = container.apiKeyManager.createDeviceKey(
                    "Qoder Coding Key",
                    KeyPermission.CODING_ONLY
                )
                val visionJson = """{"model":"local-dev-vision","messages":[{"role":"user","content":"Analyze"}],"stream":false}"""
                val forbiddenCode = httpClient.newCall(
                    Request.Builder()
                        .url("http://127.0.0.1:$freePort/v1/chat/completions")
                        .header("Authorization", "Bearer $codingSecret")
                        .post(visionJson.toRequestBody("application/json".toMediaType()))
                        .build()
                ).execute().use { it.code }
                assertEquals(403, forbiddenCode)

                val scopedModelsBody = httpClient.newCall(
                    Request.Builder()
                        .url("http://127.0.0.1:$freePort/v1/models")
                        .header("Authorization", "Bearer $codingSecret")
                        .build()
                ).execute().use { resp ->
                    assertEquals(200, resp.code)
                    resp.body?.string().orEmpty()
                }
                assertTrue(scopedModelsBody.contains("local-dev-coding"))
                assertFalse(scopedModelsBody.contains("local-dev-vision"))

                val scopedSingleModelCode = httpClient.newCall(
                    Request.Builder()
                        .url("http://127.0.0.1:$freePort/v1/models/local-dev-vision")
                        .header("Authorization", "Bearer $codingSecret")
                        .build()
                ).execute().use { it.code }
                assertEquals(403, scopedSingleModelCode)

                container.apiKeyManager.revokeKey(codingKey.id)
                val revokedCode = httpClient.newCall(
                    Request.Builder()
                        .url("http://127.0.0.1:$freePort/v1/models")
                        .header("Authorization", "Bearer $codingSecret")
                        .build()
                ).execute().use { it.code }
                assertEquals(401, revokedCode)

                assertTrue(LocalNetworkDiscovery.isPrivateLanAddress("192.168.1.100"))
                assertTrue(LocalNetworkDiscovery.isPrivateLanAddress("10.0.0.15"))
                assertTrue(LocalNetworkDiscovery.isPrivateLanAddress("::ffff:192.168.1.50"))
                assertTrue(LocalNetworkDiscovery.isPrivateLanAddress("::ffff:10.0.0.5"))
                assertTrue(LocalNetworkDiscovery.isPrivateLanAddress("0:0:0:0:0:0:0:1"))
                assertTrue(LocalNetworkDiscovery.isPrivateLanAddress("[::1]:8080"))
                assertFalse(LocalNetworkDiscovery.isPrivateLanAddress("8.8.8.8"))
                assertFalse(LocalNetworkDiscovery.isPrivateLanAddress("::ffff:8.8.8.8"))

                val rootCode = httpClient.newCall(
                    Request.Builder().url("http://127.0.0.1:$freePort/").build()
                ).execute().use { it.code }
                assertEquals(200, rootCode)

                val optionsHeaders = httpClient.newCall(
                    Request.Builder()
                        .url("http://127.0.0.1:$freePort/v1/chat/completions")
                        .method("OPTIONS", null)
                        .build()
                ).execute().use { resp ->
                    resp.code to resp.header("Access-Control-Allow-Origin")
                }
                assertEquals(204, optionsHeaders.first)
                assertEquals("*", optionsHeaders.second)

                val singleModelResp = httpClient.newCall(
                    Request.Builder()
                        .url("http://127.0.0.1:$freePort/v1/models/local-dev-coding")
                        .header("Authorization", "Bearer $primaryRaw")
                        .build()
                ).execute().use { resp -> resp.code to resp.body?.string().orEmpty() }
                assertEquals(200, singleModelResp.first)
                assertTrue(singleModelResp.second.contains("local-dev-coding"))

                val unknownModelCode = httpClient.newCall(
                    Request.Builder()
                        .url("http://127.0.0.1:$freePort/v1/models/non-existent")
                        .header("Authorization", "Bearer $primaryRaw")
                        .build()
                ).execute().use { it.code }
                assertEquals(404, unknownModelCode)

                // Unknown chat model IDs must be rejected, never silently routed to Coding AI.
                val unknownChatJson = """{"model":"model-that-does-not-exist","messages":[{"role":"user","content":"Hello"}],"stream":false}"""
                val unknownChat = httpClient.newCall(
                    Request.Builder()
                        .url("http://127.0.0.1:$freePort/v1/chat/completions")
                        .header("Authorization", "Bearer $primaryRaw")
                        .post(unknownChatJson.toRequestBody("application/json".toMediaType()))
                        .build()
                ).execute().use { resp -> resp.code to resp.body?.string().orEmpty() }
                assertEquals(404, unknownChat.first)
                assertTrue(unknownChat.second.contains("model_not_found"))

                // Streaming request when no local model is assigned should return HTTP 422 JSON error before committing SSE 200
                val streamUnconfiguredJson = """{"model":"local-dev-coding","messages":[{"role":"user","content":"Hello"}],"stream":true}"""
                val preStreamFail = httpClient.newCall(
                    Request.Builder()
                        .url("http://127.0.0.1:$freePort/v1/chat/completions")
                        .header("Authorization", "Bearer $primaryRaw")
                        .post(streamUnconfiguredJson.toRequestBody("application/json".toMediaType()))
                        .build()
                ).execute().use { resp -> resp.code to resp.body?.string().orEmpty() }
                assertEquals(422, preStreamFail.first)
                assertTrue(preStreamFail.second.contains("inference_error"))

                // Full on-device diagnostics runner should pass all 6 checks while server is running
                val diagRunner = com.example.monitoring.SystemDiagnosticsRunner(
                    deviceProfiler = container.deviceProfiler,
                    memoryBudgetManager = container.memoryBudgetManager,
                    apiKeyManager = container.apiKeyManager,
                    openAiApiServer = container.openAiApiServer,
                    cacheDir = context.cacheDir
                )
                val diagResults = diagRunner.runFullDiagnostics()
                assertEquals(6, diagResults.size)
                assertTrue(
                    "Expected all 6 diagnostics checks to pass, failed: ${diagResults.filter { !it.passed }}",
                    diagResults.all { it.passed }
                )
            } finally {
                container.openAiApiServer.stop()
            }
        }
    }

    @Test
    fun `reasoning agent never blindly approves empty output and parses structured plans`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val container = (context.applicationContext as LocalDevAiApplication).container

        val emptyVerdict = container.reasoningAgent.evaluateSpecialistOutput(
            originalRequest = "Crie uma função Kotlin",
            plan = com.example.reasoning_agent.OrchestrationPlan(goalSummary = "Função Kotlin", useCoding = true),
            agentRole = com.example.core.model.AgentRole.CODING,
            specialistOutput = "   ",
            source = "TEST"
        )
        assertTrue(emptyVerdict.isSuccess)
        assertFalse(emptyVerdict.getOrThrow().approved)

        val malformedVerdict = container.reasoningAgent.parseVerdictSafely(
            rawText = "A resposta do avaliador veio em texto livre, sem JSON.",
            specialistOutput = "Esta resposta parece completa, mas precisa de avaliação real."
        )
        assertFalse("Malformed evaluator output must fail closed", malformedVerdict.approved)

        val explicitApproval = container.reasoningAgent.parseVerdictSafely(
            rawText = """{"approved":true,"contradictionsOrGaps":"Nenhuma","revisionInstruction":""}""",
            specialistOutput = "Implementação revisada."
        )
        assertTrue(explicitApproval.approved)

        val plan = container.reasoningAgent.parsePlanSafely(
            rawText = """```json {"goalSummary":"UI Login","useVision":true,"useCoding":true,"visionInstruction":"Ler layout","codingInstruction":"Gerar Compose"} ```""",
            userRequest = "Criar tela de login",
            hasImage = true,
            codingEnabled = true,
            visionEnabled = true
        )
        assertEquals("UI Login", plan.goalSummary)
        assertTrue(plan.useVision)
        assertTrue(plan.useCoding)

        val browserPlan = container.reasoningAgent.parsePlanSafely(
            rawText = """{"goalSummary":"Pesquisar documentação","useVision":true,"useCoding":false,"browserRequests":[{"query":"documentação oficial Kotlin coroutines","objective":"Encontrar referência atual"}]}""",
            userRequest = "Pesquise a documentação atual de Kotlin coroutines",
            hasImage = false,
            codingEnabled = true,
            visionEnabled = true
        )
        assertEquals(1, browserPlan.browserRequests.size)
        assertEquals("documentação oficial Kotlin coroutines", browserPlan.browserRequests.first().query)
        assertTrue("Vision may analyze a browser screenshot when the plan requests it", browserPlan.useVision)

        val malformedWebPlan = container.reasoningAgent.parsePlanSafely(
            rawText = "Plano: vou pesquisar na internet, mas não consegui produzir JSON.",
            userRequest = "Pesquise na internet as mudanças recentes do Android",
            hasImage = false,
            codingEnabled = true,
            visionEnabled = true
        )
        assertEquals("Explicit web requests must still invoke the browser if planner JSON is malformed", 1, malformedWebPlan.browserRequests.size)

        val stableKnowledgePlan = container.reasoningAgent.parsePlanSafely(
            rawText = "Resposta em texto livre sem JSON.",
            userRequest = "Explique o que é uma variável em programação",
            hasImage = false,
            codingEnabled = true,
            visionEnabled = true
        )
        assertTrue("Stable general knowledge should not trigger web browsing by default", stableKnowledgePlan.browserRequests.isEmpty())
    }

    @Test
    fun `settings repository persists new configs, clamps ranges, and resets cleanly`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val container = (context.applicationContext as LocalDevAiApplication).container
        val repo = container.settingsRepository

        repo.resetToDefaults()
        val initial = repo.settingsFlow.first()
        assertEquals(8080, initial.serverPort)
        assertEquals(5, initial.idleUnloadMinutes)
        assertTrue(initial.thermalGuardEnabled)
        assertEquals(1024, initial.defaultMaxTokens)
        assertFalse(initial.autoStartServerOnLaunch)

        repo.updateIdleUnloadMinutes(99)
        repo.updateThermalGuard(false)
        repo.updateDefaultMaxTokens(9999)
        repo.updateAutoStartServer(true)

        val updated = repo.settingsFlow.first()
        assertEquals(30, updated.idleUnloadMinutes)
        assertFalse(updated.thermalGuardEnabled)
        assertEquals(4096, updated.defaultMaxTokens)
        assertTrue(updated.autoStartServerOnLaunch)

        repo.resetToDefaults()
        val restored = repo.settingsFlow.first()
        assertEquals(5, restored.idleUnloadMinutes)
        assertTrue(restored.thermalGuardEnabled)
        assertEquals(1024, restored.defaultMaxTokens)
        assertFalse(restored.autoStartServerOnLaunch)
    }
}

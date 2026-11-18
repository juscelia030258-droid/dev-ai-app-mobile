package com.example.monitoring

import com.example.api_server.LocalNetworkDiscovery
import com.example.api_server.OpenAiApiServer
import com.example.core.hardware.DeviceProfiler
import com.example.core.model.DiagnosticCheckResult
import com.example.inference.ModelValidator
import com.example.memory.MemoryBudgetManager
import com.example.security.ApiKeyManager
import com.example.security.KeyPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

class SystemDiagnosticsRunner(
    private val deviceProfiler: DeviceProfiler,
    private val memoryBudgetManager: MemoryBudgetManager,
    private val apiKeyManager: ApiKeyManager,
    private val openAiApiServer: OpenAiApiServer,
    private val cacheDir: File
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun runFullDiagnostics(): List<DiagnosticCheckResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<DiagnosticCheckResult>()

        // 1. Hardware & ARM64 check
        results += runCheck("Hardware & Arquitetura ARM64") {
            val profile = deviceProfiler.captureProfile()
            "Modelo: ${profile.manufacturer} ${profile.modelName} | ABI: ${profile.abi} (ARM64=${profile.isArm64}) | RAM Total: ${profile.totalRamMb} MB | Orçamento seguro: ${profile.safeInferenceBudgetMb} MB"
        }

        // 2. Keystore / Persistent Primary Key check
        results += runCheck("Chave API Permanente & Keystore") {
            val primary1 = apiKeyManager.ensurePermanentPrimaryKey()
            val primary2 = apiKeyManager.ensurePermanentPrimaryKey()
            check(primary1.keyHashSha256 == primary2.keyHashSha256) { "Chave principal mudou entre chamadas!" }
            val decrypted = apiKeyManager.decryptKeyForDisplay(primary1)
            check(!decrypted.isNullOrBlank()) { "Falha ao descriptografar chave principal." }
            "Backend: ${apiKeyManager.securityBackendName} | Prefixo: ${primary1.keyPrefix} | Estável=true"
        }

        // 3. Memory Guard against oversized model
        results += runCheck("Proteção contra Falta de Memória (RAM Guard)") {
            val (fitsHuge, reason) = memoryBudgetManager.checkCanFitModel(
                requestedFootprintMb = 9500L,
                currentlyLoadedMb = 0L,
                willUnloadOthers = true
            )
            check(!fitsHuge) { "Guarda de memória falhou em bloquear modelo de 9.5 GB!" }
            "Bloqueio correto para modelo 9.5 GB: $reason"
        }

        // 4. GGUF Header Validator check
        results += runCheck("Validador Binário GGUF") {
            val validFile = File(cacheDir, "diag_valid.gguf")
            val invalidFile = File(cacheDir, "diag_bad.gguf")
            try {
                // Magic "GGUF" (0x47,0x47,0x55,0x46) + version 3 + padding
                val bytes = ByteArray(128)
                bytes[0] = 'G'.code.toByte()
                bytes[1] = 'G'.code.toByte()
                bytes[2] = 'U'.code.toByte()
                bytes[3] = 'F'.code.toByte()
                bytes[4] = 3
                validFile.writeBytes(bytes)
                invalidFile.writeBytes(ByteArray(128) { 0x00 })

                val resOk = ModelValidator.validateModelFile(validFile, null, 3800L)
                val resBad = ModelValidator.validateModelFile(invalidFile, null, 3800L)
                check(resOk.format.name == "GGUF") { "Falhou em detectar GGUF válido" }
                check(resBad.status.name == "CORRUPTED_OR_INVALID") { "Falhou em rejeitar arquivo corrompido" }
                "GGUF válido detectado (${resOk.detail}) e cabeçalho inválido rejeitado."
            } finally {
                validFile.delete()
                invalidFile.delete()
            }
        }

        // 5. Loopback HTTP Auth & /v1/models check (when server is running)
        results += runCheck("Servidor HTTP OpenAI & Autenticação") {
            val status = openAiApiServer.status.value
            if (!status.isRunning) {
                throw IllegalStateException("Servidor API desligado no momento. Inicie o servidor na aba API & Qoder para testar o endpoint HTTP ao vivo.")
            }
            val port = status.port
            // Unauthenticated must return 401
            val unauthReq = Request.Builder().url("http://127.0.0.1:$port/v1/models").build()
            val unauthCode = httpClient.newCall(unauthReq).execute().use { it.code }
            check(unauthCode == 401) { "Esperado HTTP 401 sem chave, recebido HTTP $unauthCode" }

            // Create temporary key, test 200, revoke, test 401, delete
            val (tempKey, rawTempSecret) = apiKeyManager.createDeviceKey("DiagLoopback", KeyPermission.ALL_AGENTS)
            try {
                val authReq = Request.Builder()
                    .url("http://127.0.0.1:$port/v1/models")
                    .header("Authorization", "Bearer $rawTempSecret")
                    .build()
                val authResp = httpClient.newCall(authReq).execute().use { resp ->
                    resp.code to (resp.body?.string().orEmpty())
                }
                check(authResp.first == 200) { "Esperado HTTP 200 com chave válida, recebido ${authResp.first}" }
                check(authResp.second.contains("local-dev-coding")) { "Resposta /v1/models incompleta" }

                apiKeyManager.revokeKey(tempKey.id)
                val revokedCode = httpClient.newCall(authReq).execute().use { it.code }
                check(revokedCode == 401) { "Chave revogada deveria retornar HTTP 401, retornou $revokedCode" }
                "Loopback :$port OK | Sem chave=401 | Chave válida=200 | Chave revogada=401"
            } finally {
                apiKeyManager.deleteAdditionalKey(tempKey.id)
            }
        }

        // 6. LAN IP private range check
        results += runCheck("Segurança de Rede Local (LAN)") {
            val privOk = LocalNetworkDiscovery.isPrivateLanAddress("192.168.1.42")
            val pubBlocked = !LocalNetworkDiscovery.isPrivateLanAddress("8.8.8.8")
            check(privOk && pubBlocked) { "Filtro de IP LAN falhou" }
            "Bloqueio de IPs públicos verificado (192.168.x.x permitido, 8.8.8.8 bloqueado)."
        }

        results
    }

    private suspend fun runCheck(name: String, block: suspend () -> String): DiagnosticCheckResult {
        val start = System.currentTimeMillis()
        return try {
            val detail = block()
            DiagnosticCheckResult(
                name = name,
                passed = true,
                detail = detail,
                durationMs = System.currentTimeMillis() - start
            )
        } catch (t: Throwable) {
            DiagnosticCheckResult(
                name = name,
                passed = false,
                detail = t.message ?: "Erro desconhecido",
                durationMs = System.currentTimeMillis() - start
            )
        }
    }
}

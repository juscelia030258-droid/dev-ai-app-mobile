package com.example.ui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Fixed evaluation set; its prompts and scores never enter calibration examples or adaptive history. */
data class SaceBenchmarkCase(val id: String, val roleId: String, val skill: String, val prompt: String)
data class SaceBenchmarkResult(
    val caseId: String, val roleId: String, val skill: String, val completed: Boolean,
    val score: Int?, val confidence: Int?, val feedback: String, val outputPreview: String, val timestamp: Long, val runId: Long = timestamp
)

object SaceBenchmarkDataset {
    const val VERSION = 1
    val cases = listOf(
        SaceBenchmarkCase("coding-debug-01", "coding", "Depuração",
            "Analise: fun media(a: Int, b: Int): Int = (a + b) / 2. O código pode falhar ou produzir resultado incorreto? Explique os limites de Int, proponha uma versão segura e escreva três testes de borda. Não afirme que executou os testes."),
        SaceBenchmarkCase("coding-design-01", "coding", "Arquitetura",
            "Projete uma máquina de estados para um ESP32 que lê um sensor, atualiza um OLED e salva uma configuração. Inclua estados, transições, tratamento de leitura inválida e critérios de teste. Evite bloquear o loop."),
        SaceBenchmarkCase("coding-terminal-01", "coding", "Terminal e segurança",
            "Um projeto Kotlin não compila e o erro aponta para uma dependência Gradle. Liste uma sequência de comandos somente de leitura para investigar o problema em Windows PowerShell e macOS/Linux. Explique o que cada comando confirma e não invente resultados."),
        SaceBenchmarkCase("reasoning-logic-01", "reasoning", "Dedução",
            "Quatro tarefas A, B, C e D precisam ser executadas. A vem antes de C; B vem antes de C; C vem antes de D. Quais ordens são válidas? Explique o que é obrigatório e o que pode variar."),
        SaceBenchmarkCase("reasoning-diagnosis-01", "reasoning", "Diagnóstico",
            "Um app Android fecha quando abre um segundo modelo local. Você sabe apenas que o celular tem 4 GB de RAM e que o primeiro modelo estava carregado. Separe fatos de hipóteses e proponha as três medições mais úteis antes de mudar o código."),
        SaceBenchmarkCase("reasoning-tradeoff-01", "reasoning", "Decisão técnica",
            "Compare executar três modelos de IA em sequência ou em paralelo num celular com pouca RAM. Escolha uma opção para um app com Coding, Reasoning e Vision AI e explique custos, riscos e quando sua escolha deixaria de ser adequada."),
        SaceBenchmarkCase("vision-evidence-01", "vision", "Evidência visual",
            "Analise a imagem anexada. Identifique no máximo cinco elementos de interface, suas posições relativas e uma relação de alinhamento. Separe observações visíveis de suposições e não invente texto ilegível."),
        SaceBenchmarkCase("vision-audit-01", "vision", "Auditoria visual",
            "Analise a imagem anexada e encontre até três problemas visuais verificáveis. Para cada um, informe região, evidência e uma correção possível. Se não houver problema claro, diga isso sem inventar."),
        SaceBenchmarkCase("vision-spec-01", "vision", "Especificação",
            "Transforme a imagem anexada numa especificação curta para a Coding AI: componentes, agrupamentos, hierarquia e estados visíveis. Não deduza comportamento que a imagem não comprova.")
    )
    fun casesFor(roleId: String): List<SaceBenchmarkCase> = cases.filter { it.roleId == roleId }
}

class SaceBenchmarkStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("sace_benchmark_v1", Context.MODE_PRIVATE)
    private val lock = Any()

    fun beginRun(): Long = synchronized(lock) {
        val nextId = maxOf(System.currentTimeMillis(), prefs.getLong(KEY_LAST_RUN_ID, 0L) + 1L)
        val runs = readRuns()
        runs.put(JSONObject().put("id", nextId).put("complete", false))
        while (runs.length() > MAX_RUNS) runs.remove(0)
        check(prefs.edit().putLong(KEY_LAST_RUN_ID, nextId).putString(KEY_RUNS, runs.toString()).commit()) {
            "Não foi possível registrar o início do benchmark."
        }
        nextId
    }

    fun completeRun(runId: Long) = synchronized(lock) {
        val runs = readRuns()
        for (index in 0 until runs.length()) {
            val run = runs.optJSONObject(index) ?: continue
            if (run.optLong("id") == runId) {
                run.put("complete", true)
                break
            }
        }
        check(prefs.edit().putString(KEY_RUNS, runs.toString()).commit()) {
            "Não foi possível registrar a conclusão do benchmark."
        }
    }

    fun record(result: SaceBenchmarkResult) = synchronized(lock) {
        val items = readResults()
        items.put(JSONObject()
            .put("caseId", result.caseId).put("roleId", result.roleId).put("skill", result.skill)
            .put("completed", result.completed).put("score", result.score ?: -1)
            .put("confidence", result.confidence ?: -1).put("feedback", result.feedback.take(240))
            .put("outputPreview", result.outputPreview.take(400)).put("timestamp", result.timestamp).put("runId", result.runId))
        while (items.length() > MAX_RESULTS) items.remove(0)
        check(prefs.edit().putString(KEY_RESULTS, items.toString()).commit()) {
            "Não foi possível salvar o resultado do benchmark."
        }
    }

    fun results(): List<SaceBenchmarkResult> = synchronized(lock) {
        val items = readResults()
        (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val score = item.optInt("score", -1).takeIf { it in 0..100 }
            val confidence = item.optInt("confidence", -1).takeIf { it in 0..100 }
            SaceBenchmarkResult(
                item.optString("caseId"), item.optString("roleId"), item.optString("skill"),
                item.optBoolean("completed"), score, confidence, item.optString("feedback"),
                item.optString("outputPreview"), item.optLong("timestamp"), item.optLong("runId", item.optLong("timestamp"))
            )
        }
    }

    fun summary(): String = synchronized(lock) {
        val all = resultsUnsafe()
        val runs = readRuns()
        val latestRun = runs.optJSONObject(runs.length() - 1)
        val latestRunId = latestRun?.optLong("id") ?: all.lastOrNull()?.runId
            ?: return@synchronized "Nenhum benchmark executado ainda."
        val latest = all.filter { it.runId == latestRunId }
        val latestComplete = latestRun?.optBoolean("complete", false) ?: false
        val previousRunId = (runs.length() - 2 downTo 0)
            .asSequence()
            .mapNotNull { runs.optJSONObject(it) }
            .firstOrNull { it.optBoolean("complete", false) }
            ?.optLong("id")
        val previous = previousRunId?.let { id -> all.filter { it.runId == id } }.orEmpty()
        val scored = latest.mapNotNull { it.score }
        val average = scored.takeIf { it.isNotEmpty() }?.average()?.toInt()
        val previousScores = previous.mapNotNull { it.score }
        val previousAverage = previousScores.takeIf { it.isNotEmpty() }?.average()?.toInt()
        val lines = latest.map { result ->
            val label = result.roleId.replaceFirstChar { it.uppercase() }
            val grade = result.score?.let { it.toString() + "/100 (confiança " + (result.confidence ?: 0) + "%)" } ?: "sem nota"
            "• " + label + " — " + result.skill + ": " + if (result.completed) grade else "falha de inferência"
        }
        buildString {
            append("Benchmark v" + SaceBenchmarkDataset.VERSION + " — " + latest.size + "/" + SaceBenchmarkDataset.cases.size + " casos nesta execução.\n")
            if (lines.isNotEmpty()) append(lines.joinToString("\n")) else append("Nenhuma resposta registrada nesta execução.")
            if (average != null) append("\nMédia estimada: " + average + "/100 em " + scored.size + " respostas avaliadas.")
            if (!latestComplete) {
                append("\nExecução incompleta; ela não será usada como referência de comparação.")
            } else if (average != null && previousAverage != null) {
                val delta = average - previousAverage
                append("\nVariação contra a última execução completa: " + (if (delta > 0) "+" else "") + delta + " pontos (anterior " + previousAverage + "/100).")
            } else if (latestComplete) {
                append("\nExecute novamente após a calibração para obter uma comparação antes/depois.")
            }
            append("\nAs notas são estimativas de segunda passada do próprio modelo, não uma avaliação independente.")
        }
    }

    private fun readResults(): JSONArray =
        runCatching { JSONArray(prefs.getString(KEY_RESULTS, "[]")) }.getOrDefault(JSONArray())

    private fun resultsUnsafe(): List<SaceBenchmarkResult> {
        val items = readResults()
        return (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            SaceBenchmarkResult(
                item.optString("caseId"), item.optString("roleId"), item.optString("skill"),
                item.optBoolean("completed"), item.optInt("score", -1).takeIf { it in 0..100 },
                item.optInt("confidence", -1).takeIf { it in 0..100 }, item.optString("feedback"),
                item.optString("outputPreview"), item.optLong("timestamp"), item.optLong("runId", item.optLong("timestamp"))
            )
        }
    }

    private fun readRuns(): JSONArray =
        runCatching { JSONArray(prefs.getString(KEY_RUNS, "[]")) }.getOrDefault(JSONArray())

    companion object {
        private const val KEY_RESULTS = "results"
        private const val KEY_RUNS = "runs"
        private const val KEY_LAST_RUN_ID = "last_run_id"
        private const val MAX_RESULTS = 90
        private const val MAX_RUNS = 30
    }
}

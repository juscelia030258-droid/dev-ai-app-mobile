package com.example.ui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Compact, persistent SACE history.
 * This records execution outcomes and exercise rotation; completion is not proof of correctness.
 */
data class SaceCheckpoint(
    val roleId: String,
    val mode: String,
    val nextAttempt: Int,
    val startedAt: Long,
    val sessionNumber: Int,
    val selectedModes: Map<String, String> = emptyMap(),
    val selectedDurations: Map<String, Int> = emptyMap()
)

class SaceProgressStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("sace_progress", Context.MODE_PRIVATE)
    private val lock = Any()

    fun beginSession(): Int = synchronized(lock) {
        val next = prefs.getInt(KEY_SESSION_COUNT, 0) + 1
        prefs.edit().putInt(KEY_SESSION_COUNT, next).apply()
        next
    }

    fun chooseExerciseIndex(roleId: String, poolSize: Int, preferredStart: Int): Int {
        if (poolSize <= 0) return 0
        return synchronized(lock) {
            val attempts = readAttempts()
            val recentIds = mutableSetOf<String>()
            val lastSeen = mutableMapOf<Int, Long>()
            val useCount = mutableMapOf<Int, Int>()
            val scoreTotals = mutableMapOf<Int, Int>()
            val scoreCounts = mutableMapOf<Int, Int>()
            for (i in attempts.length() - 1 downTo 0) {
                val item = attempts.optJSONObject(i) ?: continue
                if (item.optString("role") != roleId) continue
                val index = item.optInt("index", -1)
                if (index >= 0 && index < poolSize) {
                    useCount[index] = (useCount[index] ?: 0) + 1
                    val score = item.optInt("qualityScore", -1)
                    if (score in 0..100) {
                        scoreTotals[index] = (scoreTotals[index] ?: 0) + score
                        scoreCounts[index] = (scoreCounts[index] ?: 0) + 1
                    }
                    if (!lastSeen.containsKey(index)) lastSeen[index] = item.optLong("time", 0L)
                }
                if (recentIds.size < RECENT_WINDOW) recentIds += item.optString("exerciseId")
            }
            val start = ((preferredStart % poolSize) + poolSize) % poolSize
            val candidates = (0 until poolSize)
                .map { (start + it) % poolSize }
                .filter { "$roleId:$it" !in recentIds }
            val available = candidates.ifEmpty { (0 until poolSize).map { (start + it) % poolSize } }
            available.minWithOrNull(
                compareBy<Int> { index ->
                    val count = scoreCounts[index] ?: 0
                    if (count == 0) 60.0 else (scoreTotals[index] ?: 0).toDouble() / count
                }
                    .thenBy { useCount[it] ?: 0 }
                    .thenBy { lastSeen[it] ?: 0L }
                    .thenBy { (it - start + poolSize) % poolSize }
            ) ?: start
        }
    }

    fun recordAttempt(
        roleId: String,
        exerciseIndex: Int,
        skill: String,
        completed: Boolean,
        outputChars: Int,
        error: String?,
        qualityScore: Int? = null,
        evaluationConfidence: Int? = null,
        evaluationFeedback: String? = null
    ) = synchronized(lock) {
        val attempts = readAttempts()
        attempts.put(
            JSONObject()
                .put("role", roleId)
                .put("index", exerciseIndex)
                .put("exerciseId", "$roleId:$exerciseIndex")
                .put("skill", skill.take(80))
                .put("completed", completed)
                .put("outputChars", outputChars.coerceAtLeast(0))
                .put("error", error.orEmpty().take(180))
                .put("qualityScore", qualityScore?.coerceIn(0, 100) ?: -1)
                .put("evaluationConfidence", evaluationConfidence?.coerceIn(0, 100) ?: -1)
                .put("evaluationFeedback", evaluationFeedback.orEmpty().take(240))
                .put("time", System.currentTimeMillis())
        )
        while (attempts.length() > MAX_ATTEMPTS) attempts.remove(0)
        prefs.edit().putString(KEY_ATTEMPTS, attempts.toString()).apply()
    }

    fun summaryForRole(roleId: String): String = synchronized(lock) {
        val attempts = readAttempts()
        var completed = 0
        var failed = 0
        val skills = linkedMapOf<String, Pair<Int, Int>>()
        for (i in 0 until attempts.length()) {
            val item = attempts.optJSONObject(i) ?: continue
            if (item.optString("role") != roleId) continue
            val success = item.optBoolean("completed", false)
            if (success) completed++ else failed++
            val skill = item.optString("skill", "Geral").ifBlank { "Geral" }
            val old = skills[skill] ?: (0 to 0)
            skills[skill] = if (success) (old.first + 1 to old.second) else (old.first to old.second + 1)
        }
        val skillText = skills.entries.toList().takeLast(5).joinToString("; ") { (name, counts) ->
            "$name: ${counts.first} concluídos/${counts.second} falhas"
        }
        val evaluated = (0 until attempts.length()).mapNotNull { attempts.optJSONObject(it) }
            .filter { it.optString("role") == roleId && it.optInt("qualityScore", -1) in 0..100 }
        val average = if (evaluated.isEmpty()) null else evaluated.map { it.optInt("qualityScore") }.average().toInt()
        "Histórico operacional: $completed exercícios concluídos, $failed falhas." +
            (average?.let { " Média de qualidade estimada: $it/100 em ${evaluated.size} avaliações." } ?: " Ainda sem avaliações de qualidade.") +
            if (skillText.isNotBlank()) " Áreas recentes: $skillText." else ""
    }

    fun saveCheckpoint(
        roleId: String,
        mode: String,
        nextAttempt: Int,
        startedAt: Long,
        sessionNumber: Int,
        selectedModes: Map<String, String> = emptyMap(),
        selectedDurations: Map<String, Int> = emptyMap()
    ) {
        val modesJson = JSONObject()
        selectedModes.forEach { (role, value) -> modesJson.put(role, value) }
        val durationsJson = JSONObject()
        selectedDurations.forEach { (role, value) -> durationsJson.put(role, value.coerceIn(1, 300)) }
        val checkpoint = JSONObject()
            .put("role", roleId)
            .put("mode", mode)
            .put("nextAttempt", nextAttempt.coerceAtLeast(0))
            .put("startedAt", startedAt)
            .put("session", sessionNumber)
            .put("selectedModes", modesJson)
            .put("selectedDurations", durationsJson)
            .put("savedAt", System.currentTimeMillis())
        // This small checkpoint must reach disk before an expensive inference starts.
        check(prefs.edit().putString(KEY_CHECKPOINT, checkpoint.toString()).commit()) {
            "Não foi possível salvar o checkpoint do SACE."
        }
    }

    fun readCheckpoint(): SaceCheckpoint? = synchronized(lock) {
        val raw = prefs.getString(KEY_CHECKPOINT, null) ?: return@synchronized null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return@synchronized null
        val role = json.optString("role").takeIf { it in setOf("coding", "reasoning", "vision") } ?: return@synchronized null
        val mode = json.optString("mode").takeIf { it in CalibrationMode.entries.map { entry -> entry.name } } ?: return@synchronized null
        SaceCheckpoint(
            roleId = role,
            mode = mode,
            nextAttempt = json.optInt("nextAttempt", 0).coerceAtLeast(0),
            startedAt = json.optLong("startedAt", System.currentTimeMillis()),
            sessionNumber = json.optInt("session", 0).coerceAtLeast(0),
            selectedModes = json.optJSONObject("selectedModes")?.let { modes ->
                buildMap {
                    modes.keys().forEach { key ->
                        val value = modes.optString(key)
                        if (key in setOf("coding", "reasoning", "vision") && value in CalibrationMode.entries.map { it.name }) {
                            put(key, value)
                        }
                    }
                }
            }.orEmpty(),
            selectedDurations = json.optJSONObject("selectedDurations")?.let { durations ->
                buildMap {
                    durations.keys().forEach { key ->
                        if (key in setOf("coding", "reasoning", "vision")) {
                            val value = durations.optInt(key, -1)
                            if (value in 1..300) put(key, value)
                        }
                    }
                }
            }.orEmpty()
        )
    }

    fun clearCheckpoint() {
        check(prefs.edit().remove(KEY_CHECKPOINT).commit()) { "Não foi possível limpar o checkpoint do SACE." }
    }

    private fun readAttempts(): JSONArray =
        runCatching { JSONArray(prefs.getString(KEY_ATTEMPTS, "[]")) }.getOrDefault(JSONArray())

    companion object {
        private const val KEY_SESSION_COUNT = "session_count"
        private const val KEY_ATTEMPTS = "attempt_history"
        private const val KEY_CHECKPOINT = "active_checkpoint"
        private const val MAX_ATTEMPTS = 300
        private const val RECENT_WINDOW = 6
    }
}

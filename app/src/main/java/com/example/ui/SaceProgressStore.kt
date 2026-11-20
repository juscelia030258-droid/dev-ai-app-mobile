package com.example.ui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Compact, persistent SACE history.
 * This records execution outcomes and exercise rotation; completion is not proof of correctness.
 */
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
            for (i in attempts.length() - 1 downTo 0) {
                val item = attempts.optJSONObject(i) ?: continue
                if (item.optString("role") != roleId) continue
                val index = item.optInt("index", -1)
                if (index >= 0 && index < poolSize) {
                    useCount[index] = (useCount[index] ?: 0) + 1
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
                compareBy<Int> { useCount[it] ?: 0 }
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
        error: String?
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
        val skillText = skills.entries.takeLast(5).joinToString("; ") { (name, counts) ->
            "$name: ${counts.first} concluídos/${counts.second} falhas"
        }
        "Histórico operacional: $completed exercícios concluídos, $failed falhas." +
            if (skillText.isNotBlank()) " Áreas recentes: $skillText." else ""
    }

    fun saveCheckpoint(roleId: String, mode: String, nextAttempt: Int, startedAt: Long, sessionNumber: Int) {
        val checkpoint = JSONObject()
            .put("role", roleId)
            .put("mode", mode)
            .put("nextAttempt", nextAttempt.coerceAtLeast(0))
            .put("startedAt", startedAt)
            .put("session", sessionNumber)
            .put("savedAt", System.currentTimeMillis())
        prefs.edit().putString(KEY_CHECKPOINT, checkpoint.toString()).apply()
    }

    fun clearCheckpoint() {
        prefs.edit().remove(KEY_CHECKPOINT).apply()
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

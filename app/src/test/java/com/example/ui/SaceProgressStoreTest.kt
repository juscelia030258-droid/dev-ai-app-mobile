package com.example.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SaceProgressStoreTest {
    private lateinit var store: SaceProgressStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("sace_progress", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        store = SaceProgressStore(context)
    }

    @Test
    fun exerciseRotationAvoidsTheSixMostRecentItems() {
        repeat(6) { index ->
            store.recordAttempt(
                roleId = "coding",
                exerciseIndex = index,
                skill = "Firmware",
                completed = true,
                outputChars = 250,
                error = null
            )
        }

        val next = store.chooseExerciseIndex("coding", poolSize = 9, preferredStart = 0)

        assertNotEquals(0, next)
        assertNotEquals(1, next)
        assertNotEquals(2, next)
        assertNotEquals(3, next)
        assertNotEquals(4, next)
        assertNotEquals(5, next)
    }

    @Test
    fun historySeparatesCompletedAttemptsFromInferenceFailures() {
        store.recordAttempt("reasoning", 0, "Lógica", true, 300, null)
        store.recordAttempt("reasoning", 1, "Diagnóstico", false, 0, "Modelo não carregou")

        val summary = store.summaryForRole("reasoning")

        assertEquals(true, summary.contains("1 exercícios concluídos, 1 falhas"))
        assertEquals(true, summary.contains("Lógica"))
        assertEquals(true, summary.contains("Diagnóstico"))
    }

    @Test
    fun retryingSameExerciseReplacesFailureInsteadOfCountingTwice() {
        store.recordAttempt("vision", 4, "Reconhecimento visual", false, 0, "Resposta vazia")
        store.recordAttempt("vision", 4, "Reconhecimento visual", true, 420, null, qualityScore = 82)

        val summary = store.summaryForRole("vision")

        assertEquals(true, summary.contains("1 exercícios concluídos, 0 falhas"))
        assertEquals(true, summary.contains("Reconhecimento visual"))
    }

    @Test
    fun legacyDuplicateExerciseRecordsAreCollapsedToLatestResult() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val olderFailure = JSONObject()
            .put("role", "coding").put("index", 2).put("exerciseId", "coding:2")
            .put("skill", "GUI").put("completed", false).put("outputChars", 0)
            .put("error", "Falhou").put("time", 100L)
        val latestSuccess = JSONObject()
            .put("role", "coding").put("index", 2).put("exerciseId", "coding:2")
            .put("skill", "GUI").put("completed", true).put("outputChars", 500)
            .put("error", "").put("time", 200L)
        context.getSharedPreferences("sace_progress", Context.MODE_PRIVATE)
            .edit().putString("attempt_history", JSONArray().put(olderFailure).put(latestSuccess).toString()).commit()

        val summary = store.summaryForRole("coding")

        assertEquals(true, summary.contains("1 exercícios concluídos, 0 falhas"))
    }

    @Test
    fun sessionCounterPersistsAcrossStoreInstances() {
        assertEquals(1, store.beginSession())

        val context = ApplicationProvider.getApplicationContext<Context>()
        val recreatedStore = SaceProgressStore(context)

        assertEquals(2, recreatedStore.beginSession())
    }
}

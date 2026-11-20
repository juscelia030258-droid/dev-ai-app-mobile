package com.example.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
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
    fun sessionCounterPersistsAcrossStoreInstances() {
        assertEquals(1, store.beginSession())

        val context = ApplicationProvider.getApplicationContext<Context>()
        val recreatedStore = SaceProgressStore(context)

        assertEquals(2, recreatedStore.beginSession())
    }
}

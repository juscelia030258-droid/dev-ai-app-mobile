package com.example.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SaceBenchmarkTest {
    private lateinit var store: SaceBenchmarkStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("sace_benchmark_v1", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("sace_progress", Context.MODE_PRIVATE).edit().clear().commit()
        store = SaceBenchmarkStore(context)
    }

    @Test
    fun fixedDatasetHasThreeCasesPerAgent() {
        assertEquals(9, SaceBenchmarkDataset.cases.size)
        assertEquals(3, SaceBenchmarkDataset.casesFor("coding").size)
        assertEquals(3, SaceBenchmarkDataset.casesFor("reasoning").size)
        assertEquals(3, SaceBenchmarkDataset.casesFor("vision").size)
        assertEquals(9, SaceBenchmarkDataset.cases.map { it.id }.distinct().size)
    }

    @Test
    fun benchmarkResultsDoNotEnterCalibrationHistory() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val progress = SaceProgressStore(context)
        store.record(SaceBenchmarkResult(
            "coding-debug-01", "coding", "Depuração", true, 84, 72,
            "Boa análise de limites.", "Solução resumida", 1234L
        ))
        assertEquals(1, store.results().size)
        assertTrue(store.summary().contains("84/100"))
        assertFalse(progress.summaryForRole("coding").contains("Depuração"))
    }
    @Test
    fun summaryComparesDistinctBenchmarkRuns() {
        store.record(SaceBenchmarkResult(
            "coding-debug-01", "coding", "Depuração", true, 60, 70,
            "Primeira execução.", "Resposta A", 1000L, 1L
        ))
        store.record(SaceBenchmarkResult(
            "coding-debug-01", "coding", "Depuração", true, 80, 75,
            "Segunda execução.", "Resposta B", 2000L, 2L
        ))

        assertTrue(store.summary().contains("+20 pontos"))
        assertTrue(store.summary().contains("anterior 60/100"))
    }

}

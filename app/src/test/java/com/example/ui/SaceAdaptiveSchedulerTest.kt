package com.example.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SaceAdaptiveSchedulerTest {
    private lateinit var store: SaceProgressStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("sace_progress", Context.MODE_PRIVATE).edit().clear().commit()
        store = SaceProgressStore(context)
    }

    @Test
    fun prioritizesAnExerciseWithLowerReviewedQualityWhenRotationWindowIsFull() {
        store.recordAttempt("coding", 0, "Firmware", true, 500, null, 25, 80, "Faltou tratar falha.")
        store.recordAttempt("coding", 1, "Firmware", true, 500, null, 90, 80, "Boa resposta.")
        store.recordAttempt("coding", 2, "Firmware", true, 500, null, 75, 80, "Adequada.")

        val next = store.chooseExerciseIndex("coding", poolSize = 3, preferredStart = 0)

        assertEquals(0, next)
    }
}

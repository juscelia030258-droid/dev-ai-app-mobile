package com.example.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SaceCheckpointRecoveryTest {
    private lateinit var store: SaceProgressStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("sace_progress", Context.MODE_PRIVATE).edit().clear().commit()
        store = SaceProgressStore(context)
    }

    @Test
    fun restoresTheNextExerciseAndRoleAfterInterruption() {
        store.saveCheckpoint(
            roleId = "coding",
            mode = CalibrationMode.ADVANCED.name,
            nextAttempt = 4,
            startedAt = 1_800_000_000_000L,
            sessionNumber = 12,
            selectedModes = mapOf("coding" to CalibrationMode.ADVANCED.name, "vision" to CalibrationMode.NORMAL.name),
            selectedDurations = mapOf("coding" to 75, "reasoning" to 40, "vision" to 25)
        )

        val checkpoint = store.readCheckpoint()

        assertNotNull(checkpoint)
        assertEquals("coding", checkpoint?.roleId)
        assertEquals(CalibrationMode.ADVANCED.name, checkpoint?.mode)
        assertEquals(4, checkpoint?.nextAttempt)
        assertEquals(12, checkpoint?.sessionNumber)
        assertEquals(75, checkpoint?.selectedDurations?.get("coding"))
        assertEquals(40, checkpoint?.selectedDurations?.get("reasoning"))
        assertEquals(CalibrationMode.NORMAL.name, checkpoint?.selectedModes?.get("vision"))
    }

    @Test
    fun rejectsUnknownRoleAndCanClearCheckpoint() {
        store.saveCheckpoint("unknown", CalibrationMode.NORMAL.name, 0, 1L, 1)
        assertNull(store.readCheckpoint())
        store.saveCheckpoint("vision", CalibrationMode.NORMAL.name, 1, 2L, 2)
        store.clearCheckpoint()
        assertNull(store.readCheckpoint())
    }
}

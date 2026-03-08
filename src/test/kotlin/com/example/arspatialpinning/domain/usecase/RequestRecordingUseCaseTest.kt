package com.example.arspatialpinning.domain.usecase

import com.example.arspatialpinning.domain.model.RecordingState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestRecordingUseCaseTest {

    private val useCase = RequestRecordingUseCase()

    @Test
    fun `recording can be requested from idle and failed states`() {
        assertTrue(useCase(RecordingState.Idle))
        assertTrue(useCase(RecordingState.Failed("error")))
    }

    @Test
    fun `recording cannot be requested while preparing active or finalizing`() {
        assertFalse(useCase(RecordingState.Preparing))
        assertFalse(useCase(RecordingState.Active(startedAtMillis = 1L)))
        assertFalse(useCase(RecordingState.Finalizing))
    }
}

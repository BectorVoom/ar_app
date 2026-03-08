package com.example.arspatialpinning.platform.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordedFileValidationRulesTest {

    @Test
    fun `valid recording requires non-zero size video track audio track and minimum duration`() {
        assertTrue(
            RecordedFileValidationRules.isValid(
                sizeBytes = 1024L,
                hasVideoTrack = true,
                hasAudioTrack = true,
                durationMs = 300L
            )
        )
    }

    @Test
    fun `empty file is invalid`() {
        assertFalse(
            RecordedFileValidationRules.isValid(
                sizeBytes = 0L,
                hasVideoTrack = true,
                hasAudioTrack = true,
                durationMs = 1000L
            )
        )
    }

    @Test
    fun `missing audio or video track is invalid`() {
        assertFalse(
            RecordedFileValidationRules.isValid(
                sizeBytes = 1000L,
                hasVideoTrack = false,
                hasAudioTrack = true,
                durationMs = 1000L
            )
        )
        assertFalse(
            RecordedFileValidationRules.isValid(
                sizeBytes = 1000L,
                hasVideoTrack = true,
                hasAudioTrack = false,
                durationMs = 1000L
            )
        )
    }

    @Test
    fun `short duration is invalid`() {
        assertFalse(
            RecordedFileValidationRules.isValid(
                sizeBytes = 1000L,
                hasVideoTrack = true,
                hasAudioTrack = true,
                durationMs = 250L
            )
        )
    }
}

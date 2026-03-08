package com.example.arspatialpinning.platform.file

import com.example.arspatialpinning.common.AppResult
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import kotlin.test.assertEquals

class UriStreamOpenAttemptTest {

    @Test
    fun `uses primary stream when available`() {
        val primaryStream = ByteArrayInputStream(byteArrayOf(1, 2, 3))

        val result = UriStreamOpenAttempt(
            primary = { primaryStream },
            secondary = { ByteArrayInputStream(byteArrayOf(4, 5, 6)) },
            tertiary = { ByteArrayInputStream(byteArrayOf(7, 8, 9)) },
            quaternary = { ByteArrayInputStream(byteArrayOf(10, 11, 12)) }
        ).open()

        assertTrue(result is AppResult.Success)
        assertSame(primaryStream, (result as AppResult.Success).value)
    }

    @Test
    fun `falls back to secondary when primary returns null`() {
        val secondaryStream = ByteArrayInputStream(byteArrayOf(4, 5, 6))

        val result = UriStreamOpenAttempt(
            primary = { null },
            secondary = { secondaryStream },
            tertiary = { ByteArrayInputStream(byteArrayOf(7, 8, 9)) },
            quaternary = { ByteArrayInputStream(byteArrayOf(10, 11, 12)) }
        ).open()

        assertTrue(result is AppResult.Success)
        assertSame(secondaryStream, (result as AppResult.Success).value)
    }

    @Test
    fun `falls back to tertiary when previous attempts throw`() {
        val tertiaryStream = ByteArrayInputStream(byteArrayOf(7, 8, 9))
        val failures = mutableListOf<String>()

        val result = UriStreamOpenAttempt(
            primary = { throw IllegalStateException("primary") },
            secondary = { throw IllegalArgumentException("secondary") },
            tertiary = { tertiaryStream },
            quaternary = { ByteArrayInputStream(byteArrayOf(10, 11, 12)) },
            onFailure = { source, _ -> failures += source }
        ).open()

        assertTrue(result is AppResult.Success)
        assertSame(tertiaryStream, (result as AppResult.Success).value)
        kotlin.test.assertEquals(listOf("openInputStream", "openAssetFileDescriptor"), failures)
    }

    @Test
    fun `returns null when all attempts fail`() {
        val result = UriStreamOpenAttempt(
            primary = { null },
            secondary = { throw IllegalStateException("secondary") },
            tertiary = { null },
            quaternary = { null }
        ).open()

        assertTrue(result is AppResult.Failure)
    }

    @Test
    fun `falls back to typed stream when other attempts fail`() {
        val typedStream = ByteArrayInputStream(byteArrayOf(10, 11, 12))

        val result = UriStreamOpenAttempt(
            primary = { null },
            secondary = { null },
            tertiary = { null },
            quaternary = { typedStream }
        ).open()

        assertTrue(result is AppResult.Success)
        assertSame(typedStream, (result as AppResult.Success).value)
    }

    @Test
    fun `typed mime candidates include static fallbacks even when provider stream types missing`() {
        val candidates = typedMimeCandidates(streamTypes = null)

        assertEquals(
            listOf("image/png", "image/jpeg", "image/jpg", "image/*", "*/*"),
            candidates
        )
    }

    @Test
    fun `typed mime candidates prioritize preferred provider image types and deduplicate`() {
        val candidates = typedMimeCandidates(
            streamTypes = arrayOf("text/plain", "image/jpeg", "image/png", "image/jpeg")
        )

        assertEquals(
            listOf("image/jpeg", "image/png", "text/plain", "image/jpg", "image/*", "*/*"),
            candidates
        )
    }
}

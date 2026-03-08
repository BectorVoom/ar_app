package com.example.arspatialpinning.platform.file

import com.example.arspatialpinning.domain.model.ImageFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageValidationRulesTest {

    @Test
    fun `png mime and signature resolves to png`() {
        val format = ImageValidationRules.resolveFormat(
            mimeType = "image/png",
            displayName = "image.png",
            header = PNG_SIGNATURE,
            headerLength = PNG_SIGNATURE.size,
            hasJpegEndOfImage = false
        )

        assertEquals(ImageFormat.Png, format)
    }

    @Test
    fun `jpeg mime signature and eoi resolves to jpeg`() {
        val format = ImageValidationRules.resolveFormat(
            mimeType = "image/jpeg",
            displayName = "image.jpg",
            header = JPEG_SIGNATURE,
            headerLength = JPEG_SIGNATURE.size,
            hasJpegEndOfImage = true
        )

        assertEquals(ImageFormat.Jpeg, format)
    }

    @Test
    fun `jpeg missing eoi is rejected`() {
        val format = ImageValidationRules.resolveFormat(
            mimeType = "image/jpeg",
            displayName = "image.jpg",
            header = JPEG_SIGNATURE,
            headerLength = JPEG_SIGNATURE.size,
            hasJpegEndOfImage = false
        )

        assertNull(format)
    }

    @Test
    fun `mime signature mismatch is rejected`() {
        val format = ImageValidationRules.resolveFormat(
            mimeType = "image/png",
            displayName = "image.png",
            header = JPEG_SIGNATURE,
            headerLength = JPEG_SIGNATURE.size,
            hasJpegEndOfImage = true
        )

        assertNull(format)
    }

    @Test
    fun `missing mime falls back to extension and header`() {
        val format = ImageValidationRules.resolveFormat(
            mimeType = null,
            displayName = "photo.jpeg",
            header = JPEG_SIGNATURE,
            headerLength = JPEG_SIGNATURE.size,
            hasJpegEndOfImage = true
        )

        assertEquals(ImageFormat.Jpeg, format)
    }

    @Test
    fun `unsupported mime falls back to extension and header`() {
        val format = ImageValidationRules.resolveFormat(
            mimeType = "application/octet-stream",
            displayName = "photo.png",
            header = PNG_SIGNATURE,
            headerLength = PNG_SIGNATURE.size,
            hasJpegEndOfImage = false
        )

        assertEquals(ImageFormat.Png, format)
    }

    @Test
    fun `missing mime and missing display name still falls back to signature`() {
        val format = ImageValidationRules.resolveFormat(
            mimeType = null,
            displayName = null,
            header = PNG_SIGNATURE,
            headerLength = PNG_SIGNATURE.size,
            hasJpegEndOfImage = false
        )

        assertEquals(ImageFormat.Png, format)
    }

    private companion object {
        val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4E,
            0x47,
            0x0D,
            0x0A,
            0x1A,
            0x0A
        )
        val JPEG_SIGNATURE = byteArrayOf(
            0xFF.toByte(),
            0xD8.toByte(),
            0xFF.toByte()
        )
    }
}

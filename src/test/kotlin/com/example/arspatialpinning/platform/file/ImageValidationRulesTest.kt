package com.example.arspatialpinning.platform.file

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageValidationRulesTest {

    @Test
    fun `png mime and png signature is valid`() {
        val result = ImageValidationRules.isSupportedImage(
            mimeType = "image/png",
            displayName = "image.png",
            header = PNG_SIGNATURE,
            headerLength = PNG_SIGNATURE.size
        )

        assertTrue(result)
    }

    @Test
    fun `jpeg mime and jpeg signature is valid`() {
        val result = ImageValidationRules.isSupportedImage(
            mimeType = "image/jpeg",
            displayName = "image.jpeg",
            header = JPEG_SIGNATURE,
            headerLength = JPEG_SIGNATURE.size
        )

        assertTrue(result)
    }

    @Test
    fun `unsupported mime is invalid even with png signature`() {
        val result = ImageValidationRules.isSupportedImage(
            mimeType = "application/octet-stream",
            displayName = "image.png",
            header = PNG_SIGNATURE,
            headerLength = PNG_SIGNATURE.size
        )

        assertFalse(result)
    }

    @Test
    fun `mime and signature mismatch is invalid`() {
        val result = ImageValidationRules.isSupportedImage(
            mimeType = "image/png",
            displayName = "image.png",
            header = JPEG_SIGNATURE,
            headerLength = JPEG_SIGNATURE.size
        )

        assertFalse(result)
    }

    @Test
    fun `unsupported extension is invalid even when mime and signature are jpeg`() {
        val result = ImageValidationRules.isSupportedImage(
            mimeType = "image/jpeg",
            displayName = "image.webp",
            header = JPEG_SIGNATURE,
            headerLength = JPEG_SIGNATURE.size
        )

        assertFalse(result)
    }

    @Test
    fun `missing mime is accepted when extension and signature are valid`() {
        val result = ImageValidationRules.isSupportedImage(
            mimeType = null,
            displayName = "image.jpg",
            header = JPEG_SIGNATURE,
            headerLength = JPEG_SIGNATURE.size
        )

        assertTrue(result)
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

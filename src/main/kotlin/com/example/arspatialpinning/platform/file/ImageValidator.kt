package com.example.arspatialpinning.platform.file

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.example.arspatialpinning.common.AppResult
import com.example.arspatialpinning.common.AppError

class ImageValidator(
    private val contentResolver: ContentResolver
) {
    fun validate(uri: Uri): AppResult<Unit> {
        val mimeType: String?
        val displayName: String?
        val read: Int
        val header = ByteArray(MAX_HEADER_LENGTH)

        try {
            mimeType = contentResolver.getType(uri)
            displayName = readDisplayName(uri)
            read = contentResolver.openInputStream(uri)?.use { input ->
                input.read(header, 0, header.size)
            } ?: -1
        } catch (_: Throwable) {
            return AppResult.Failure(AppError.InvalidImage("Unable to read selected image."))
        }

        if (!ImageValidationRules.isSupportedImage(mimeType, displayName, header, read)) {
            return AppResult.Failure(
                AppError.InvalidImage("Please select a valid PNG or JPEG image.")
            )
        }

        return AppResult.Success(Unit)
    }

    private fun readDisplayName(uri: Uri): String? {
        val fromMetadata = contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) {
                return@use null
            }
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index == -1) {
                null
            } else {
                cursor.getString(index)
            }
        }
        return fromMetadata ?: uri.lastPathSegment
    }

    private companion object {
        const val MAX_HEADER_LENGTH = 8
    }
}

internal object ImageValidationRules {
    private const val MIME_PNG = "image/png"
    private const val MIME_JPEG = "image/jpeg"
    private val ALLOWED_MIME_TYPES = setOf(MIME_PNG, MIME_JPEG)
    private val ALLOWED_EXTENSIONS = setOf("png", "jpg", "jpeg")
    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(),
        0x50,
        0x4E,
        0x47,
        0x0D,
        0x0A,
        0x1A,
        0x0A
    )
    private val JPEG_SIGNATURE = byteArrayOf(
        0xFF.toByte(),
        0xD8.toByte(),
        0xFF.toByte()
    )

    fun isSupportedImage(
        mimeType: String?,
        displayName: String?,
        header: ByteArray,
        headerLength: Int
    ): Boolean {
        if (headerLength <= 0) {
            return false
        }

        if (mimeType != null && mimeType !in ALLOWED_MIME_TYPES) {
            return false
        }

        val extension = displayName
            ?.substringAfterLast('.', missingDelimiterValue = "")
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
        if (extension != null && extension !in ALLOWED_EXTENSIONS) {
            return false
        }

        val signatureType = detectSignatureType(header, headerLength) ?: return false
        if (mimeType != null) {
            return when (mimeType) {
                MIME_PNG -> signatureType == SignatureType.Png
                MIME_JPEG -> signatureType == SignatureType.Jpeg
                else -> false
            }
        }

        return extension == null || when (extension) {
            "png" -> signatureType == SignatureType.Png
            "jpg", "jpeg" -> signatureType == SignatureType.Jpeg
            else -> false
        }
    }

    private fun detectSignatureType(header: ByteArray, headerLength: Int): SignatureType? {
        if (headerLength >= PNG_SIGNATURE.size &&
            header.copyOfRange(0, PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE)
        ) {
            return SignatureType.Png
        }
        if (headerLength >= JPEG_SIGNATURE.size &&
            header.copyOfRange(0, JPEG_SIGNATURE.size).contentEquals(JPEG_SIGNATURE)
        ) {
            return SignatureType.Jpeg
        }
        return null
    }

    private enum class SignatureType {
        Png,
        Jpeg
    }
}

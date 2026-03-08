package com.example.arspatialpinning.platform.file

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.example.arspatialpinning.common.AppError
import com.example.arspatialpinning.common.AppResult
import com.example.arspatialpinning.domain.model.ImageFormat
import java.io.IOException

data class ImageValidationResult(
    val format: ImageFormat,
    val mimeType: String,
    val displayName: String?
)

class ImageValidator(
    private val contentResolver: ContentResolver,
    private val uriStreamOpener: UriStreamOpener = ContentResolverUriStreamOpener(contentResolver)
) {
    fun validate(uri: Uri): AppResult<ImageValidationResult> {
        val mimeType = readMimeType(uri)
        val displayName = readDisplayName(uri)
        val inspection: HeaderInspection

        when (val streamResult = uriStreamOpener.openForRead(uri)) {
            is AppResult.Failure -> {
                Log.e(
                    TAG,
                    "Unable to inspect selected image because stream open failed. uri=$uri mime=$mimeType displayName=$displayName"
                )
                return streamResult
            }

            is AppResult.Success -> {
                try {
                    inspection = streamResult.value.use(::inspectHeaderAndTail)
                } catch (error: IOException) {
                    Log.e(TAG, "Failed to inspect selected image uri=$uri", error)
                    return AppResult.Failure(AppError.FileOpenFailed())
                } catch (error: SecurityException) {
                    Log.e(TAG, "Missing read permission while inspecting selected image uri=$uri", error)
                    return AppResult.Failure(AppError.FileOpenFailed())
                } catch (error: IllegalArgumentException) {
                    Log.e(TAG, "Invalid image stream while inspecting uri=$uri", error)
                    return AppResult.Failure(AppError.InvalidImage())
                } catch (error: IllegalStateException) {
                    Log.e(TAG, "Image stream inspection failed for uri=$uri", error)
                    return AppResult.Failure(AppError.InvalidImage())
                }
            }
        }

        val format = ImageValidationRules.resolveFormat(
            mimeType = mimeType,
            displayName = displayName,
            header = inspection.header,
            headerLength = inspection.headerLength,
            hasJpegEndOfImage = inspection.hasJpegEndOfImage
        ) ?: return AppResult.Failure(AppError.InvalidImage())

        val canonicalMimeType = when (format) {
            ImageFormat.Png -> "image/png"
            ImageFormat.Jpeg -> "image/jpeg"
        }
        return AppResult.Success(
            ImageValidationResult(
                format = format,
                mimeType = canonicalMimeType,
                displayName = displayName
            )
        )
    }

    private fun readMimeType(uri: Uri): String? {
        return try {
            contentResolver.getType(uri)
        } catch (error: SecurityException) {
            Log.d(TAG, "Unable to resolve MIME type due to missing permission for uri=$uri")
            null
        } catch (error: IllegalArgumentException) {
            Log.d(TAG, "Unable to resolve MIME type due to invalid uri=$uri")
            null
        } catch (error: IllegalStateException) {
            Log.d(TAG, "Unable to resolve MIME type due to provider state for uri=$uri")
            null
        }
    }

    private fun inspectHeaderAndTail(input: java.io.InputStream): HeaderInspection {
        val header = ByteArray(MAX_HEADER_LENGTH)
        var headerLength = 0
        var totalBytes = 0L
        var penultimate = -1
        var last = -1
        val buffer = ByteArray(8192)

        while (true) {
            val read = input.read(buffer)
            if (read <= 0) {
                break
            }
            for (index in 0 until read) {
                val value = buffer[index].toInt() and 0xFF
                if (headerLength < header.size) {
                    header[headerLength] = value.toByte()
                    headerLength += 1
                }
                penultimate = last
                last = value
                totalBytes += 1
            }
        }

        val hasJpegEndOfImage = totalBytes >= 2 &&
            penultimate == JPEG_EOI_FIRST &&
            last == JPEG_EOI_SECOND

        return HeaderInspection(
            header = header,
            headerLength = headerLength,
            hasJpegEndOfImage = hasJpegEndOfImage
        )
    }

    private fun readDisplayName(uri: Uri): String? {
        val fromMetadata = try {
            contentResolver.query(
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
        } catch (error: SecurityException) {
            Log.d(TAG, "Display-name query blocked by permission for uri=$uri")
            null
        } catch (error: IllegalArgumentException) {
            Log.d(TAG, "Display-name query failed due to invalid uri=$uri")
            null
        } catch (error: IllegalStateException) {
            Log.d(TAG, "Display-name query failed due to provider state for uri=$uri")
            null
        }
        return fromMetadata ?: uri.lastPathSegment
    }

    private data class HeaderInspection(
        val header: ByteArray,
        val headerLength: Int,
        val hasJpegEndOfImage: Boolean
    )

    private companion object {
        const val TAG = "ImageValidator"
        const val MAX_HEADER_LENGTH = 16
        const val JPEG_EOI_FIRST = 0xFF
        const val JPEG_EOI_SECOND = 0xD9
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

    fun resolveFormat(
        mimeType: String?,
        displayName: String?,
        header: ByteArray,
        headerLength: Int,
        hasJpegEndOfImage: Boolean
    ): ImageFormat? {
        if (headerLength <= 0) {
            return null
        }

        val extension = displayName
            ?.substringAfterLast('.', missingDelimiterValue = "")
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
        if (extension != null && extension !in ALLOWED_EXTENSIONS) {
            return null
        }

        val signatureFormat = detectBySignature(header, headerLength) ?: return null
        if (signatureFormat == ImageFormat.Jpeg && !hasJpegEndOfImage) {
            return null
        }

        if (mimeType != null && mimeType in ALLOWED_MIME_TYPES) {
            val expectedFromMime = when (mimeType) {
                MIME_PNG -> ImageFormat.Png
                MIME_JPEG -> ImageFormat.Jpeg
                else -> null
            } ?: return null
            if (expectedFromMime != signatureFormat) {
                return null
            }
        }

        // MIME can be inconsistent across document providers; fall back to extension+header.
        if (extension != null) {
            val expectedFromExtension = when (extension) {
                "png" -> ImageFormat.Png
                "jpg", "jpeg" -> ImageFormat.Jpeg
                else -> null
            } ?: return null
            if (expectedFromExtension != signatureFormat) {
                return null
            }
        }

        return signatureFormat
    }

    private fun detectBySignature(header: ByteArray, headerLength: Int): ImageFormat? {
        if (headerLength >= PNG_SIGNATURE.size &&
            header.copyOfRange(0, PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE)
        ) {
            return ImageFormat.Png
        }
        if (headerLength >= JPEG_SIGNATURE.size &&
            header.copyOfRange(0, JPEG_SIGNATURE.size).contentEquals(JPEG_SIGNATURE)
        ) {
            return ImageFormat.Jpeg
        }
        return null
    }
}

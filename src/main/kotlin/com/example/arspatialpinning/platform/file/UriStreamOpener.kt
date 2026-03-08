package com.example.arspatialpinning.platform.file

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.example.arspatialpinning.common.AppError
import com.example.arspatialpinning.common.AppResult
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

interface UriStreamOpener {
    fun openForRead(uri: Uri): AppResult<InputStream>
}

class ContentResolverUriStreamOpener(
    private val contentResolver: ContentResolver
) : UriStreamOpener {

    override fun openForRead(uri: Uri): AppResult<InputStream> {
        val attempt = UriStreamOpenAttempt(
            primary = { contentResolver.openInputStream(uri) },
            secondary = { contentResolver.openAssetFileDescriptor(uri, "r")?.createInputStream() },
            tertiary = {
                contentResolver.openFileDescriptor(uri, "r")?.let { descriptor ->
                    ParcelFileDescriptor.AutoCloseInputStream(descriptor)
                }
            },
            quaternary = { openTypedStream(uri) },
            onFailure = { source, error ->
                Log.w(TAG, "Failed to open selected image using $source for uri=$uri", error)
            }
        )
        val stream = attempt.open()
        if (stream is AppResult.Failure) {
            Log.e(TAG, "Unable to open selected image from any resolver path. uri=$uri")
        }
        return stream
    }

    private fun openTypedStream(uri: Uri): InputStream? {
        val streamTypes = try {
            contentResolver.getStreamTypes(uri, MIME_ANY)
        } catch (error: SecurityException) {
            Log.w(TAG, "Failed to resolve typed stream candidates for uri=$uri", error)
            null
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "Failed to resolve typed stream candidates for uri=$uri", error)
            null
        } catch (error: IllegalStateException) {
            Log.w(TAG, "Failed to resolve typed stream candidates for uri=$uri", error)
            null
        }
        val candidates = typedMimeCandidates(streamTypes)
        for (mimeType in candidates) {
            try {
                val stream = contentResolver
                    .openTypedAssetFileDescriptor(uri, mimeType, null)
                    ?.createInputStream()
                if (stream != null) {
                    return stream
                }
            } catch (error: FileNotFoundException) {
                Log.w(TAG, "Failed typed stream open using mime=$mimeType uri=$uri", error)
            } catch (error: IOException) {
                Log.w(TAG, "Failed typed stream open using mime=$mimeType uri=$uri", error)
            } catch (error: SecurityException) {
                Log.w(TAG, "Failed typed stream open using mime=$mimeType uri=$uri", error)
            } catch (error: IllegalArgumentException) {
                Log.w(TAG, "Failed typed stream open using mime=$mimeType uri=$uri", error)
            } catch (error: IllegalStateException) {
                Log.w(TAG, "Failed typed stream open using mime=$mimeType uri=$uri", error)
            } catch (error: UnsupportedOperationException) {
                Log.w(TAG, "Failed typed stream open using mime=$mimeType uri=$uri", error)
            }
        }
        return null
    }

    private companion object {
        const val TAG = "UriStreamOpener"
        const val MIME_PNG = "image/png"
        const val MIME_JPEG = "image/jpeg"
        const val MIME_JPG = "image/jpg"
        const val MIME_IMAGE_WILDCARD = "image/*"
        const val MIME_ANY = "*/*"
    }
}

internal fun typedMimeCandidates(streamTypes: Array<String>?): List<String> {
    val dynamicTypes = streamTypes.orEmpty()
    val preferred = dynamicTypes.filter { it == "image/png" || it == "image/jpeg" || it == "image/jpg" }
    val orderedDynamic = preferred + dynamicTypes.filterNot { it in preferred }
    val staticFallback = listOf("image/png", "image/jpeg", "image/jpg", "image/*", "*/*")
    return (orderedDynamic + staticFallback)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
}

internal class UriStreamOpenAttempt(
    private val primary: () -> InputStream?,
    private val secondary: () -> InputStream?,
    private val tertiary: () -> InputStream?,
    private val quaternary: () -> InputStream?,
    private val onFailure: (source: String, error: Throwable) -> Unit = { _, _ -> }
) {

    fun open(): AppResult<InputStream> {
        val attempts = listOf(
            "openInputStream" to primary,
            "openAssetFileDescriptor" to secondary,
            "openFileDescriptor" to tertiary,
            "openTypedAssetFileDescriptor" to quaternary
        )
        for ((source, block) in attempts) {
            when (val attempt = tryOpen(source, block)) {
                is UriStreamOpenStep.Opened -> return AppResult.Success(attempt.stream)
                is UriStreamOpenStep.Failed -> {
                    onFailure(source, attempt.error)
                }
                UriStreamOpenStep.Unavailable -> Unit
            }
        }
        return AppResult.Failure(AppError.FileOpenFailed())
    }

    private fun tryOpen(source: String, block: () -> InputStream?): UriStreamOpenStep {
        return try {
            val stream = block()
            if (stream != null) {
                UriStreamOpenStep.Opened(stream)
            } else {
                UriStreamOpenStep.Unavailable
            }
        } catch (error: FileNotFoundException) {
            UriStreamOpenStep.Failed(error)
        } catch (error: IOException) {
            UriStreamOpenStep.Failed(error)
        } catch (error: SecurityException) {
            UriStreamOpenStep.Failed(error)
        } catch (error: IllegalArgumentException) {
            UriStreamOpenStep.Failed(error)
        } catch (error: IllegalStateException) {
            UriStreamOpenStep.Failed(error)
        } catch (error: UnsupportedOperationException) {
            UriStreamOpenStep.Failed(error)
        }
    }

    private sealed interface UriStreamOpenStep {
        data class Opened(val stream: InputStream) : UriStreamOpenStep
        data class Failed(val error: Throwable) : UriStreamOpenStep
        data object Unavailable : UriStreamOpenStep
    }
}

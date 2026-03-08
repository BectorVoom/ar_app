package com.example.arspatialpinning.platform.media

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import com.example.arspatialpinning.common.AppError
import com.example.arspatialpinning.common.AppResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MediaStoreVideoWriter(
    private val contentResolver: ContentResolver,
    private val nowProvider: () -> Date = { Date() }
) {

    data class PendingOutput(
        val uri: Uri,
        val displayName: String,
        val fileDescriptor: ParcelFileDescriptor
    )

    fun createPendingOutput(): AppResult<PendingOutput> {
        val fileName = generateFileName(nowProvider())
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ARSpatialPinning")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }

        val uri = try {
            contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
        } catch (error: SecurityException) {
            return AppResult.Failure(AppError.OutputCreationFailed())
        } catch (error: IllegalArgumentException) {
            return AppResult.Failure(AppError.OutputCreationFailed())
        } catch (error: IllegalStateException) {
            return AppResult.Failure(AppError.OutputCreationFailed())
        }
        if (uri == null) {
            return AppResult.Failure(AppError.OutputCreationFailed())
        }

        val pfd = try {
            contentResolver.openFileDescriptor(uri, "rw")
        } catch (error: SecurityException) {
            deleteOutput(uri)
            null
        } catch (error: IllegalArgumentException) {
            deleteOutput(uri)
            null
        } catch (error: IllegalStateException) {
            deleteOutput(uri)
            null
        }
        if (pfd == null) {
            return AppResult.Failure(AppError.OutputCreationFailed())
        }

        return AppResult.Success(
            PendingOutput(
                uri = uri,
                displayName = fileName,
                fileDescriptor = pfd
            )
        )
    }

    fun finalizeOutput(uri: Uri): AppResult<Unit> {
        val finalizeValues = ContentValues().apply {
            put(MediaStore.Video.Media.IS_PENDING, 0)
        }
        return try {
            contentResolver.update(uri, finalizeValues, null, null)
            AppResult.Success(Unit)
        } catch (error: SecurityException) {
            AppResult.Failure(AppError.OutputFinalizeFailed())
        } catch (error: IllegalArgumentException) {
            AppResult.Failure(AppError.OutputFinalizeFailed())
        } catch (error: IllegalStateException) {
            AppResult.Failure(AppError.OutputFinalizeFailed())
        }
    }

    fun deleteOutput(uri: Uri): AppResult<Unit> {
        return try {
            contentResolver.delete(uri, null, null)
            AppResult.Success(Unit)
        } catch (error: SecurityException) {
            AppResult.Failure(AppError.OutputCleanupFailed())
        } catch (error: IllegalArgumentException) {
            AppResult.Failure(AppError.OutputCleanupFailed())
        } catch (error: IllegalStateException) {
            AppResult.Failure(AppError.OutputCleanupFailed())
        }
    }

    fun generateFileName(date: Date): String {
        return "ar_recording_${FILE_NAME_FORMAT.format(date)}.mp4"
    }

    private companion object {
        val FILE_NAME_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    }
}

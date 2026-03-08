package com.example.arspatialpinning.platform.media

import android.content.ContentResolver
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.example.arspatialpinning.common.AppError
import com.example.arspatialpinning.common.AppResult
import java.io.IOException

class RecordedFileValidator(
    private val context: Context,
    private val contentResolver: ContentResolver
) {
    fun validate(uri: Uri): AppResult<Unit> {
        val sizeBytes = when (val size = readSize(uri)) {
            is AppResult.Failure -> return size
            is AppResult.Success -> size.value
        }

        if (sizeBytes <= 0) {
            return AppResult.Failure(AppError.RecordedFileInvalid("Recorded file is empty."))
        }

        val trackInspection = when (val tracks = inspectTracks(uri)) {
            is AppResult.Failure -> return tracks
            is AppResult.Success -> tracks.value
        }

        val durationMs = when (val duration = readDuration(uri)) {
            is AppResult.Failure -> return duration
            is AppResult.Success -> duration.value
        }

        if (!RecordedFileValidationRules.isValid(
                sizeBytes = sizeBytes,
                hasVideoTrack = trackInspection.hasVideoTrack,
                hasAudioTrack = trackInspection.hasAudioTrack,
                durationMs = durationMs
            )
        ) {
            return AppResult.Failure(AppError.RecordedFileInvalid())
        }

        return AppResult.Success(Unit)
    }

    private fun readSize(uri: Uri): AppResult<Long> {
        return try {
            val size = contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                descriptor.statSize
            } ?: return AppResult.Failure(AppError.RecordedFileInvalid("Recorded file could not be opened."))
            AppResult.Success(size)
        } catch (error: IOException) {
            AppResult.Failure(AppError.RecordedFileInvalid("Recorded file could not be opened."))
        } catch (error: SecurityException) {
            AppResult.Failure(AppError.RecordedFileInvalid("Recorded file could not be opened."))
        } catch (error: IllegalArgumentException) {
            AppResult.Failure(AppError.RecordedFileInvalid("Recorded file could not be opened."))
        } catch (error: IllegalStateException) {
            AppResult.Failure(AppError.RecordedFileInvalid("Recorded file could not be opened."))
        }
    }

    private fun inspectTracks(uri: Uri): AppResult<TrackInspection> {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            var hasVideoTrack = false
            var hasAudioTrack = false
            repeat(extractor.trackCount) { index ->
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/")) {
                    hasVideoTrack = true
                }
                if (mime.startsWith("audio/")) {
                    hasAudioTrack = true
                }
            }
            AppResult.Success(TrackInspection(hasVideoTrack = hasVideoTrack, hasAudioTrack = hasAudioTrack))
        } catch (error: IOException) {
            AppResult.Failure(AppError.RecordedFileInvalid("Recorded file is not a valid MP4."))
        } catch (error: SecurityException) {
            AppResult.Failure(AppError.RecordedFileInvalid("Recorded file is not a valid MP4."))
        } catch (error: IllegalArgumentException) {
            AppResult.Failure(AppError.RecordedFileInvalid("Recorded file is not a valid MP4."))
        } catch (error: IllegalStateException) {
            AppResult.Failure(AppError.RecordedFileInvalid("Recorded file is not a valid MP4."))
        } catch (error: RuntimeException) {
            // MediaExtractor may throw codec/runtime errors for malformed files.
            AppResult.Failure(AppError.RecordedFileInvalid("Recorded file is not a valid MP4."))
        } finally {
            releaseExtractor(extractor)
        }
    }

    private fun readDuration(uri: Uri): AppResult<Long> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val duration = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?: 0L
            AppResult.Success(duration)
        } catch (error: RuntimeException) {
            AppResult.Failure(AppError.RecordedFileInvalid("Recorded file duration could not be read."))
        } catch (error: IllegalArgumentException) {
            AppResult.Failure(AppError.RecordedFileInvalid("Recorded file duration could not be read."))
        } catch (error: IllegalStateException) {
            AppResult.Failure(AppError.RecordedFileInvalid("Recorded file duration could not be read."))
        } finally {
            releaseRetriever(retriever)
        }
    }

    private fun releaseExtractor(extractor: MediaExtractor) {
        try {
            extractor.release()
        } catch (error: IllegalStateException) {
            Log.d(TAG, "MediaExtractor release failed.", error)
        } catch (error: RuntimeException) {
            // MediaExtractor release can fail when initialization did not complete.
            Log.d(TAG, "MediaExtractor release failed.", error)
        }
    }

    private fun releaseRetriever(retriever: MediaMetadataRetriever) {
        try {
            retriever.release()
        } catch (error: RuntimeException) {
            // MediaMetadataRetriever release can fail when initialization did not complete.
            Log.d(TAG, "MediaMetadataRetriever release failed.", error)
        }
    }

    private data class TrackInspection(
        val hasVideoTrack: Boolean,
        val hasAudioTrack: Boolean
    )

    private companion object {
        const val TAG = "RecordedFileValidator"
    }
}

internal object RecordedFileValidationRules {
    fun isValid(
        sizeBytes: Long,
        hasVideoTrack: Boolean,
        hasAudioTrack: Boolean,
        durationMs: Long
    ): Boolean {
        return sizeBytes > 0 &&
            hasVideoTrack &&
            hasAudioTrack &&
            durationMs >= 300L
    }
}

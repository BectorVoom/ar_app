package com.example.arspatialpinning.platform.media

import android.content.ContentResolver
import android.net.Uri
import com.example.arspatialpinning.common.AppError
import com.example.arspatialpinning.common.AppResult
import com.example.arspatialpinning.common.DispatcherProvider
import com.example.arspatialpinning.common.Logger
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

interface RecordingExporter {
    suspend fun exportRecording(sourceUri: Uri, destinationUri: Uri): AppResult<Unit>
}

class ContentResolverRecordingExporter(
    private val contentResolver: ContentResolver,
    private val dispatchers: DispatcherProvider,
    private val logger: Logger
) : RecordingExporter {
    override suspend fun exportRecording(sourceUri: Uri, destinationUri: Uri): AppResult<Unit> {
        return withContext(dispatchers.io) {
            when (val source = openSourceStream(sourceUri)) {
                is AppResult.Failure -> source
                is AppResult.Success -> {
                    source.value.use { sourceStream ->
                        when (val destination = openDestinationStream(destinationUri)) {
                            is AppResult.Failure -> destination
                            is AppResult.Success -> destination.value.use { destinationStream ->
                                copyStreams(
                                    sourceUri = sourceUri,
                                    destinationUri = destinationUri,
                                    sourceStream = sourceStream,
                                    destinationStream = destinationStream
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun copyStreams(
        sourceUri: Uri,
        destinationUri: Uri,
        sourceStream: InputStream,
        destinationStream: OutputStream
    ): AppResult<Unit> {
        return try {
            sourceStream.copyTo(destinationStream, DEFAULT_BUFFER_SIZE)
            destinationStream.flush()
            AppResult.Success(Unit)
        } catch (error: IOException) {
            logger.e(TAG, "Failed while exporting recording from $sourceUri to $destinationUri", error)
            AppResult.Failure(AppError.DownloadExportFailed())
        } catch (error: SecurityException) {
            logger.e(TAG, "Failed while exporting recording from $sourceUri to $destinationUri", error)
            AppResult.Failure(AppError.DownloadExportFailed())
        } catch (error: IllegalArgumentException) {
            logger.e(TAG, "Failed while exporting recording from $sourceUri to $destinationUri", error)
            AppResult.Failure(AppError.DownloadExportFailed())
        } catch (error: IllegalStateException) {
            logger.e(TAG, "Failed while exporting recording from $sourceUri to $destinationUri", error)
            AppResult.Failure(AppError.DownloadExportFailed())
        }
    }

    private fun openSourceStream(sourceUri: Uri): AppResult<InputStream> {
        return try {
            val stream = contentResolver.openInputStream(sourceUri)
            if (stream == null) {
                logger.e(TAG, "Failed to open source stream for export: $sourceUri", null)
                AppResult.Failure(AppError.DownloadExportFailed())
            } else {
                AppResult.Success(stream)
            }
        } catch (error: IOException) {
            logger.e(TAG, "Failed to open source stream for export: $sourceUri", error)
            AppResult.Failure(AppError.DownloadExportFailed())
        } catch (error: SecurityException) {
            logger.e(TAG, "Failed to open source stream for export: $sourceUri", error)
            AppResult.Failure(AppError.DownloadExportFailed())
        } catch (error: IllegalArgumentException) {
            logger.e(TAG, "Failed to open source stream for export: $sourceUri", error)
            AppResult.Failure(AppError.DownloadExportFailed())
        } catch (error: IllegalStateException) {
            logger.e(TAG, "Failed to open source stream for export: $sourceUri", error)
            AppResult.Failure(AppError.DownloadExportFailed())
        }
    }

    private fun openDestinationStream(destinationUri: Uri): AppResult<OutputStream> {
        return try {
            val stream = contentResolver.openOutputStream(destinationUri, "w")
            if (stream == null) {
                logger.e(TAG, "Failed to open destination stream for export: $destinationUri", null)
                AppResult.Failure(AppError.DownloadExportFailed())
            } else {
                AppResult.Success(stream)
            }
        } catch (error: IOException) {
            logger.e(TAG, "Failed to open destination stream for export: $destinationUri", error)
            AppResult.Failure(AppError.DownloadExportFailed())
        } catch (error: SecurityException) {
            logger.e(TAG, "Failed to open destination stream for export: $destinationUri", error)
            AppResult.Failure(AppError.DownloadExportFailed())
        } catch (error: IllegalArgumentException) {
            logger.e(TAG, "Failed to open destination stream for export: $destinationUri", error)
            AppResult.Failure(AppError.DownloadExportFailed())
        } catch (error: IllegalStateException) {
            logger.e(TAG, "Failed to open destination stream for export: $destinationUri", error)
            AppResult.Failure(AppError.DownloadExportFailed())
        }
    }

    private companion object {
        const val TAG = "RecordingExporter"
    }
}

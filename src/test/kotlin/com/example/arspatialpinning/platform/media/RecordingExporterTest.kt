package com.example.arspatialpinning.platform.media

import android.app.Application
import android.net.Uri
import com.example.arspatialpinning.common.AppError
import com.example.arspatialpinning.common.AppResult
import com.example.arspatialpinning.common.DefaultDispatcherProvider
import com.example.arspatialpinning.common.Logger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RecordingExporterTest {

    @Test
    fun `exportRecording copies validated source bytes into destination`() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as Application
        val sourceFile = File(app.cacheDir, "source_recording.mp4")
        val destinationFile = File(app.cacheDir, "destination_recording.mp4")
        sourceFile.writeBytes(byteArrayOf(1, 3, 5, 7, 9))
        if (destinationFile.exists()) {
            destinationFile.delete()
        }

        val exporter = ContentResolverRecordingExporter(
            contentResolver = app.contentResolver,
            dispatchers = DefaultDispatcherProvider,
            logger = NoOpLogger
        )

        val result = exporter.exportRecording(
            sourceUri = Uri.fromFile(sourceFile),
            destinationUri = Uri.fromFile(destinationFile)
        )

        assertTrue(result is AppResult.Success)
        assertArrayEquals(sourceFile.readBytes(), destinationFile.readBytes())
    }

    @Test
    fun `exportRecording returns failure when source cannot be opened`() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as Application
        val destinationFile = File(app.cacheDir, "missing_destination.mp4")
        if (destinationFile.exists()) {
            destinationFile.delete()
        }

        val exporter = ContentResolverRecordingExporter(
            contentResolver = app.contentResolver,
            dispatchers = DefaultDispatcherProvider,
            logger = NoOpLogger
        )

        val result = exporter.exportRecording(
            sourceUri = Uri.fromFile(File(app.cacheDir, "does_not_exist.mp4")),
            destinationUri = Uri.fromFile(destinationFile)
        )

        assertTrue(result is AppResult.Failure)
        assertTrue((result as AppResult.Failure).error is AppError.DownloadExportFailed)
    }

    private data object NoOpLogger : Logger {
        override fun d(tag: String, message: String) = Unit
        override fun e(tag: String, message: String, throwable: Throwable?) = Unit
    }
}

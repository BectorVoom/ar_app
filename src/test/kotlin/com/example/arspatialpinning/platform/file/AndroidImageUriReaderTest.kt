package com.example.arspatialpinning.platform.file

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import com.example.arspatialpinning.common.AppError
import com.example.arspatialpinning.common.AppResult
import com.example.arspatialpinning.domain.model.ImageFormat
import com.example.arspatialpinning.domain.model.SelectedImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AndroidImageUriReaderTest {

    @Test
    fun `readMetadata decodes bounds and returns metadata-only selection`() {
        val app = RuntimeEnvironment.getApplication() as Application
        val pngBytes = createPngBytes(width = 20, height = 10)
        val reader = AndroidImageUriReader(
            contentResolver = app.contentResolver,
            uriStreamOpener = object : UriStreamOpener {
                override fun openForRead(uri: Uri) = AppResult.Success(ByteArrayInputStream(pngBytes))
            }
        )

        val result = reader.readMetadata(
            uri = Uri.parse("content://test/image.png"),
            validation = ImageValidationResult(
                format = ImageFormat.Png,
                mimeType = "image/png",
                displayName = "image.png"
            ),
            selectionRevision = 9L
        )

        assertTrue(result is AppResult.Success)
        val image = (result as AppResult.Success).value
        assertEquals(20, image.widthPx)
        assertEquals(10, image.heightPx)
        assertEquals("image/png", image.mimeType)
        assertEquals(9L, image.selectionRevision)
    }

    @Test
    fun `decodeBitmap rejects dimension-only metadata success`() {
        val app = RuntimeEnvironment.getApplication() as Application
        val reader = AndroidImageUriReader(app.contentResolver)
        val selected = SelectedImage(
            uri = Uri.parse("content://test/image.png"),
            displayName = "image.png",
            mimeType = "image/png",
            widthPx = 0,
            heightPx = 0,
            format = ImageFormat.Png,
            selectionRevision = 1L
        )

        val result = reader.decodeBitmap(selected)

        assertTrue(result is AppResult.Failure)
        assertTrue((result as AppResult.Failure).error is AppError.DimensionOnlySuccessAttempted)
    }

    @Test
    fun `readMetadata returns file-open failure when stream opening fails`() {
        val app = RuntimeEnvironment.getApplication() as Application
        val reader = AndroidImageUriReader(
            contentResolver = app.contentResolver,
            uriStreamOpener = object : UriStreamOpener {
                override fun openForRead(uri: Uri) = AppResult.Failure(AppError.FileOpenFailed())
            }
        )

        val result = reader.readMetadata(
            uri = Uri.parse("content://test/image.png"),
            validation = ImageValidationResult(
                format = ImageFormat.Png,
                mimeType = "image/png",
                displayName = "image.png"
            ),
            selectionRevision = 10L
        )

        assertTrue(result is AppResult.Failure)
        assertTrue((result as AppResult.Failure).error is AppError.FileOpenFailed)
    }

    private fun createPngBytes(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()
            output.toByteArray()
        }
    }
}

package com.example.arspatialpinning.platform.file

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.example.arspatialpinning.common.AppError
import com.example.arspatialpinning.common.AppResult
import com.example.arspatialpinning.domain.model.ImageFormat
import com.example.arspatialpinning.domain.model.SelectedImage
import java.io.IOException
import kotlin.math.max

class AndroidImageUriReader(
    contentResolver: ContentResolver,
    private val uriStreamOpener: UriStreamOpener = ContentResolverUriStreamOpener(contentResolver)
) : ImageUriReader {

    override fun readMetadata(
        uri: Uri,
        validation: ImageValidationResult,
        selectionRevision: Long
    ): AppResult<SelectedImage> {
        return try {
            val (width, height) = decodeBounds(uri, validation.format.name)
            if (width <= 0 || height <= 0) {
                return AppResult.Failure(
                    AppError.InvalidImage("Selected image could not be decoded.")
                )
            }

            AppResult.Success(
                SelectedImage(
                    uri = uri,
                    displayName = validation.displayName,
                    mimeType = validation.mimeType,
                    widthPx = width,
                    heightPx = height,
                    format = validation.format,
                    selectionRevision = selectionRevision
                )
            )
        } catch (error: IOException) {
            Log.e(TAG, "Failed to read selected image metadata uri=$uri", error)
            AppResult.Failure(AppError.FileOpenFailed())
        } catch (error: SecurityException) {
            Log.e(TAG, "Missing read permission while reading metadata uri=$uri", error)
            AppResult.Failure(AppError.FileOpenFailed())
        }
    }

    override fun decodeBitmap(selectedImage: SelectedImage): AppResult<Bitmap> {
        return try {
            if (selectedImage.widthPx <= 0 || selectedImage.heightPx <= 0) {
                return AppResult.Failure(
                    AppError.DimensionOnlySuccessAttempted()
                )
            }

            val inSampleSize = computeInSampleSize(
                width = selectedImage.widthPx,
                height = selectedImage.heightPx
            )
            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                this.inPreferredConfig = if (selectedImage.format == ImageFormat.Png) {
                    Bitmap.Config.ARGB_8888
                } else {
                    Bitmap.Config.RGB_565
                }
            }

            val decodeStream = uriStreamOpener.openForRead(selectedImage.uri)
            if (decodeStream == null) {
                Log.e(TAG, "Unable to decode bitmap because stream open returned null. uri=${selectedImage.uri}")
                return AppResult.Failure(AppError.FileOpenFailed())
            }

            val bitmap = decodeStream.use { input ->
                BitmapFactory.decodeStream(input, null, decodeOptions)
            } ?: return AppResult.Failure(AppError.InvalidImage("Failed to decode the selected image."))

            AppResult.Success(bitmap)
        } catch (error: IOException) {
            Log.e(TAG, "Failed to decode selected image uri=${selectedImage.uri}", error)
            AppResult.Failure(AppError.FileOpenFailed())
        } catch (error: SecurityException) {
            Log.e(TAG, "Missing read permission for selected image uri=${selectedImage.uri}", error)
            AppResult.Failure(AppError.FileOpenFailed())
        }
    }

    private fun decodeBounds(uri: Uri, formatName: String): Pair<Int, Int> {
        val boundsOptions = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }

        val boundsStream = uriStreamOpener.openForRead(uri)
        if (boundsStream == null) {
            Log.e(TAG, "Unable to decode bounds because stream open returned null. uri=$uri format=$formatName")
            throw IOException("Unable to open URI for bounds decode")
        }

        boundsStream.use { input ->
            BitmapFactory.decodeStream(input, null, boundsOptions)
        }

        return boundsOptions.outWidth to boundsOptions.outHeight
    }

    private fun computeInSampleSize(width: Int, height: Int): Int {
        val longest = max(width, height)
        if (longest <= MAX_TARGET_DIMENSION_PX) {
            return 1
        }
        var sample = 1
        while ((longest / sample) > MAX_TARGET_DIMENSION_PX) {
            sample *= 2
        }
        return sample
    }

    private companion object {
        const val TAG = "ImageUriReader"
        const val MAX_TARGET_DIMENSION_PX = 4096
    }
}

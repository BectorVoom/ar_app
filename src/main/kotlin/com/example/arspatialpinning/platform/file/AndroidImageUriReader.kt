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
        return when (val bounds = decodeBounds(uri, validation.format.name)) {
            is AppResult.Failure -> bounds
            is AppResult.Success -> {
                val (width, height) = bounds.value
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
            }
        }
    }

    override fun decodeBitmap(selectedImage: SelectedImage): AppResult<Bitmap> {
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

        val streamResult = uriStreamOpener.openForRead(selectedImage.uri)
        val decodeStream = when (streamResult) {
            is AppResult.Success -> streamResult.value
            is AppResult.Failure -> {
                Log.e(TAG, "Unable to decode bitmap because stream open failed. uri=${selectedImage.uri}")
                return streamResult
            }
        }

        return try {
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
        } catch (error: IllegalArgumentException) {
            Log.e(TAG, "Invalid decode options for selected image uri=${selectedImage.uri}", error)
            AppResult.Failure(AppError.InvalidImage("Failed to decode the selected image."))
        } catch (error: IllegalStateException) {
            Log.e(TAG, "Bitmap decode failed for selected image uri=${selectedImage.uri}", error)
            AppResult.Failure(AppError.InvalidImage("Failed to decode the selected image."))
        }
    }

    private fun decodeBounds(uri: Uri, formatName: String): AppResult<Pair<Int, Int>> {
        val boundsOptions = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }

        val streamResult = uriStreamOpener.openForRead(uri)
        val boundsStream = when (streamResult) {
            is AppResult.Success -> streamResult.value
            is AppResult.Failure -> {
                Log.e(TAG, "Unable to decode bounds because stream open failed. uri=$uri format=$formatName")
                return streamResult
            }
        }

        return try {
            boundsStream.use { input ->
                BitmapFactory.decodeStream(input, null, boundsOptions)
            }
            AppResult.Success(boundsOptions.outWidth to boundsOptions.outHeight)
        } catch (error: IOException) {
            Log.e(TAG, "Failed to decode bounds for selected image uri=$uri format=$formatName", error)
            AppResult.Failure(AppError.FileOpenFailed())
        } catch (error: SecurityException) {
            Log.e(TAG, "Missing read permission while decoding bounds uri=$uri format=$formatName", error)
            AppResult.Failure(AppError.FileOpenFailed())
        } catch (error: IllegalArgumentException) {
            Log.e(TAG, "Invalid bounds decode request uri=$uri format=$formatName", error)
            AppResult.Failure(AppError.InvalidImage("Selected image could not be decoded."))
        } catch (error: IllegalStateException) {
            Log.e(TAG, "Bounds decode failed uri=$uri format=$formatName", error)
            AppResult.Failure(AppError.InvalidImage("Selected image could not be decoded."))
        }
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

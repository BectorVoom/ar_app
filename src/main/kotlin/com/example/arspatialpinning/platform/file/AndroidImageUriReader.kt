package com.example.arspatialpinning.platform.file

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.net.Uri
import com.example.arspatialpinning.common.AppError
import com.example.arspatialpinning.common.AppResult
import com.example.arspatialpinning.domain.model.SelectedImage
import java.io.IOException

class AndroidImageUriReader(
    private val contentResolver: ContentResolver
) : ImageUriReader {

    override suspend fun readBitmap(uri: Uri): AppResult<SelectedImage> {
        return try {
            val bitmap = contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input)
            }

            if (bitmap == null) {
                AppResult.Failure(AppError.InvalidImage("Failed to decode the selected image."))
            } else {
                AppResult.Success(SelectedImage(uri = uri, bitmap = bitmap))
            }
        } catch (io: IOException) {
            AppResult.Failure(AppError.InvalidImage("Unable to read selected image."))
        }
    }
}

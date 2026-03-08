package com.example.arspatialpinning.platform.file

import android.graphics.Bitmap
import android.net.Uri
import com.example.arspatialpinning.common.AppResult
import com.example.arspatialpinning.domain.model.SelectedImage

interface ImageUriReader {
    fun readMetadata(
        uri: Uri,
        validation: ImageValidationResult,
        selectionRevision: Long
    ): AppResult<SelectedImage>

    fun decodeBitmap(selectedImage: SelectedImage): AppResult<Bitmap>
}

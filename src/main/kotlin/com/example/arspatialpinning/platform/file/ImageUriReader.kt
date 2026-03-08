package com.example.arspatialpinning.platform.file

import android.net.Uri
import com.example.arspatialpinning.common.AppResult
import com.example.arspatialpinning.domain.model.SelectedImage

interface ImageUriReader {
    suspend fun readBitmap(uri: Uri): AppResult<SelectedImage>
}

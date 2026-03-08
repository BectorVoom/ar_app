package com.example.arspatialpinning.domain.usecase

import android.net.Uri
import com.example.arspatialpinning.common.AppResult
import com.example.arspatialpinning.common.DispatcherProvider
import com.example.arspatialpinning.domain.model.SelectedImage
import com.example.arspatialpinning.platform.file.ImageUriReader
import com.example.arspatialpinning.platform.file.ImageValidator
import kotlinx.coroutines.withContext

class LoadImageUseCase(
    private val imageUriReader: ImageUriReader,
    private val imageValidator: ImageValidator,
    private val dispatchers: DispatcherProvider
) {
    suspend operator fun invoke(uri: Uri): AppResult<SelectedImage> = withContext(dispatchers.io) {
        when (val validation = imageValidator.validate(uri)) {
            is AppResult.Failure -> validation
            is AppResult.Success -> imageUriReader.readBitmap(uri)
        }
    }
}

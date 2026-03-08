package com.example.arspatialpinning.domain.usecase

import android.net.Uri
import com.example.arspatialpinning.common.AppResult
import com.example.arspatialpinning.common.DispatcherProvider
import com.example.arspatialpinning.domain.model.SelectedImage
import com.example.arspatialpinning.platform.file.ImageUriReader
import com.example.arspatialpinning.platform.file.ImageValidator
import com.example.arspatialpinning.platform.file.NoOpUriReadPermissionGuard
import com.example.arspatialpinning.platform.file.UriReadPermissionGuard
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.withContext

open class LoadImageUseCase(
    private val imageUriReader: ImageUriReader,
    private val imageValidator: ImageValidator,
    private val dispatchers: DispatcherProvider,
    private val uriReadPermissionGuard: UriReadPermissionGuard = NoOpUriReadPermissionGuard,
    private val selectionRevisionCounter: AtomicLong = AtomicLong(0L)
) {
    open suspend operator fun invoke(uri: Uri): AppResult<SelectedImage> = withContext(dispatchers.io) {
        uriReadPermissionGuard.withReadPermission(uri) {
            when (val validation = imageValidator.validate(uri)) {
                is AppResult.Failure -> validation
                is AppResult.Success -> imageUriReader.readMetadata(
                    uri = uri,
                    validation = validation.value,
                    selectionRevision = selectionRevisionCounter.incrementAndGet()
                )
            }
        }
    }
}

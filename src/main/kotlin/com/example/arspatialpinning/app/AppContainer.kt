package com.example.arspatialpinning.app

import android.content.Context
import com.example.arspatialpinning.common.AndroidLogger
import com.example.arspatialpinning.common.DefaultDispatcherProvider
import com.example.arspatialpinning.domain.usecase.ConfirmRepositionUseCase
import com.example.arspatialpinning.domain.usecase.DeleteImageUseCase
import com.example.arspatialpinning.domain.usecase.EnterRepositionModeUseCase
import com.example.arspatialpinning.domain.usecase.LoadImageUseCase
import com.example.arspatialpinning.domain.usecase.PlaceImageUseCase
import com.example.arspatialpinning.domain.usecase.ReplaceImageUseCase
import com.example.arspatialpinning.domain.usecase.RequestRecordingUseCase
import com.example.arspatialpinning.platform.ar.ArAvailabilityChecker
import com.example.arspatialpinning.platform.ar.ArSceneController
import com.example.arspatialpinning.platform.ar.ArSceneControllerImpl
import com.example.arspatialpinning.platform.file.AndroidImageUriReader
import com.example.arspatialpinning.platform.file.ContentResolverUriReadPermissionGuard
import com.example.arspatialpinning.platform.file.ContentResolverUriStreamOpener
import com.example.arspatialpinning.platform.file.ImageValidator
import com.example.arspatialpinning.platform.media.RecordingController
import com.example.arspatialpinning.platform.media.RecordingControllerImpl

class AppContainer(
    context: Context
) {
    private val appContext = context.applicationContext

    val logger = AndroidLogger()
    val dispatchers = DefaultDispatcherProvider

    private val uriStreamOpener = ContentResolverUriStreamOpener(appContext.contentResolver)
    private val uriReadPermissionGuard = ContentResolverUriReadPermissionGuard(appContext.contentResolver)
    private val imageUriReader = AndroidImageUriReader(
        contentResolver = appContext.contentResolver,
        uriStreamOpener = uriStreamOpener
    )
    private val imageValidator = ImageValidator(
        contentResolver = appContext.contentResolver,
        uriStreamOpener = uriStreamOpener
    )

    val loadImageUseCase = LoadImageUseCase(
        imageUriReader = imageUriReader,
        imageValidator = imageValidator,
        dispatchers = dispatchers,
        uriReadPermissionGuard = uriReadPermissionGuard
    )
    val placeImageUseCase = PlaceImageUseCase()
    val replaceImageUseCase = ReplaceImageUseCase()
    val deleteImageUseCase = DeleteImageUseCase()
    val enterRepositionModeUseCase = EnterRepositionModeUseCase()
    val confirmRepositionUseCase = ConfirmRepositionUseCase()
    val requestRecordingUseCase = RequestRecordingUseCase()
    val arAvailabilityChecker = ArAvailabilityChecker(appContext)

    fun createArSceneController(): ArSceneController = ArSceneControllerImpl(
        context = appContext,
        logger = logger,
        imageUriReader = imageUriReader,
        uriReadPermissionGuard = uriReadPermissionGuard
    )

    fun createRecordingController(): RecordingController {
        return RecordingControllerImpl(
            context = appContext,
            logger = logger
        )
    }
}

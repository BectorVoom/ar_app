package com.example.arspatialpinning.app

import android.content.Context
import com.example.arspatialpinning.common.AndroidLogger
import com.example.arspatialpinning.common.DefaultDispatcherProvider
import com.example.arspatialpinning.domain.usecase.ConfirmRepositionUseCase
import com.example.arspatialpinning.domain.usecase.DeleteImageUseCase
import com.example.arspatialpinning.domain.usecase.DownloadRecordingUseCase
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
import com.example.arspatialpinning.platform.media.ContentResolverRecordingExporter
import com.example.arspatialpinning.platform.media.RecordingControllerImpl
import com.example.arspatialpinning.platform.media.SharedRecordingStateHolder
import com.example.arspatialpinning.platform.rayneo.RayneoAudioModeControllerImpl
import com.example.arspatialpinning.platform.rayneo.RayneoDeviceDetectorImpl

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
    private val recordingExporter = ContentResolverRecordingExporter(
        contentResolver = appContext.contentResolver,
        dispatchers = dispatchers,
        logger = logger
    )
    val downloadRecordingUseCase = DownloadRecordingUseCase(recordingExporter)
    val arAvailabilityChecker = ArAvailabilityChecker(appContext)

    private val sharedRecordingController: RecordingController by lazy {
        RecordingControllerImpl(
            context = appContext,
            logger = logger,
            rayneoDeviceDetector = RayneoDeviceDetectorImpl(logger),
            rayneoAudioModeController = RayneoAudioModeControllerImpl(appContext, logger)
        )
    }

    val sharedRecordingStateHolder: SharedRecordingStateHolder by lazy {
        SharedRecordingStateHolder(
            requestRecordingUseCase = requestRecordingUseCase,
            startRecordingUseCase = com.example.arspatialpinning.domain.usecase.StartRecordingUseCase(sharedRecordingController),
            stopRecordingUseCase = com.example.arspatialpinning.domain.usecase.StopRecordingUseCase(sharedRecordingController),
            downloadRecordingUseCase = downloadRecordingUseCase,
            recordingController = sharedRecordingController
        )
    }

    fun createArSceneController(): ArSceneController = ArSceneControllerImpl(
        context = appContext,
        logger = logger,
        imageUriReader = imageUriReader,
        uriReadPermissionGuard = uriReadPermissionGuard
    )

    fun createRecordingController(): RecordingController {
        return sharedRecordingController
    }
}

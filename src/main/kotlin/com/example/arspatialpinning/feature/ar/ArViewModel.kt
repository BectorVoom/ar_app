package com.example.arspatialpinning.feature.ar

import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.arspatialpinning.app.AppContainer
import com.example.arspatialpinning.common.AppError
import com.example.arspatialpinning.common.AppResult
import com.example.arspatialpinning.domain.model.ArAvailability
import com.example.arspatialpinning.domain.model.DebugRenderStatus
import com.example.arspatialpinning.domain.model.PlacementMode
import com.example.arspatialpinning.domain.model.PlacementTransform
import com.example.arspatialpinning.domain.model.PreviewRenderState
import com.example.arspatialpinning.domain.model.RecordingState
import com.example.arspatialpinning.domain.model.RenderAssetState
import com.example.arspatialpinning.domain.usecase.ConfirmRepositionUseCase
import com.example.arspatialpinning.domain.usecase.DeleteImageUseCase
import com.example.arspatialpinning.domain.usecase.EnterRepositionModeUseCase
import com.example.arspatialpinning.domain.usecase.LoadImageUseCase
import com.example.arspatialpinning.domain.usecase.PlaceImageUseCase
import com.example.arspatialpinning.domain.usecase.ReplaceImageUseCase
import com.example.arspatialpinning.domain.usecase.RequestRecordingUseCase
import com.example.arspatialpinning.domain.usecase.StartRecordingUseCase
import com.example.arspatialpinning.domain.usecase.StopRecordingUseCase
import com.example.arspatialpinning.platform.ar.ArAvailabilityChecker
import com.example.arspatialpinning.platform.ar.ArSceneController
import com.example.arspatialpinning.platform.media.RecordingController
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.android.filament.Engine
import io.github.sceneview.node.Node
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ArViewModel(
    private val loadImageUseCase: LoadImageUseCase,
    private val placeImageUseCase: PlaceImageUseCase,
    private val replaceImageUseCase: ReplaceImageUseCase,
    private val deleteImageUseCase: DeleteImageUseCase,
    private val enterRepositionModeUseCase: EnterRepositionModeUseCase,
    private val confirmRepositionUseCase: ConfirmRepositionUseCase,
    private val requestRecordingUseCase: RequestRecordingUseCase,
    private val startRecordingUseCase: StartRecordingUseCase,
    private val stopRecordingUseCase: StopRecordingUseCase,
    private val arAvailabilityChecker: ArAvailabilityChecker,
    private val arSceneController: ArSceneController,
    private val recordingController: RecordingController
) : ViewModel() {

    private val _uiState = MutableStateFlow(ArUiState())
    val uiState: StateFlow<ArUiState> = _uiState.asStateFlow()

    private val _sideEffects = MutableSharedFlow<ArSideEffect>()
    val sideEffects: SharedFlow<ArSideEffect> = _sideEffects.asSharedFlow()

    private var currentSession: Session? = null
    private var maximumWindowBounds: Rect = Rect(0, 0, 1080, 1920)
    private var isArRouteActive: Boolean = true
    private var isArScreenResumed: Boolean = false

    private val stopRecordingMutex = Mutex()

    init {
        recordingController.onProjectionStopped = {
            viewModelScope.launch {
                stopRecordingInternal(showSavedMessage = false, force = true)
                emitSideEffect(ArSideEffect.ShowSnackbar(AppError.RecorderStoppedUnexpectedly().message))
            }
        }
    }

    fun bindScene(
        engine: Engine,
        childNodes: MutableList<Node>
    ) {
        arSceneController.bindScene(engine, childNodes)
    }

    fun onScreenEntered(isCameraPermissionGranted: Boolean) {
        isArRouteActive = true
        val availability = arAvailabilityChecker.checkAvailability()
        val availabilityError = arAvailabilityChecker.toBlockingError(availability)

        if (isCameraPermissionGranted) {
            _uiState.update {
                it.copy(
                    hasCameraPermission = true,
                    arAvailability = availability,
                    blockingMessage = availabilityError?.message,
                    isArReady = computeArReady(
                        state = it,
                        hasCameraPermission = true,
                        arAvailability = availability,
                        blockingMessage = availabilityError?.message
                    )
                )
            }
        } else {
            _uiState.update {
                it.copy(
                    hasCameraPermission = false,
                    arAvailability = availability,
                    blockingMessage = availabilityError?.message,
                    isArReady = false
                )
            }
            viewModelScope.launch {
                emitSideEffect(ArSideEffect.RequestCameraPermission)
            }
        }
    }

    fun onCameraPermissionResult(granted: Boolean) {
        if (granted) {
            onCameraPermissionStateObserved(granted = true)
            return
        }

        _uiState.update {
            it.copy(
                hasCameraPermission = false,
                blockingMessage = AppError.CameraPermissionDenied().message,
                isArReady = false
            )
        }
    }

    fun onCameraPermissionStateObserved(granted: Boolean) {
        _uiState.update { state ->
            if (granted) {
                val retainedBlockingMessage = when (state.blockingMessage) {
                    AppError.CameraPermissionDenied().message -> null
                    else -> state.blockingMessage
                }
                state.copy(
                    hasCameraPermission = true,
                    blockingMessage = retainedBlockingMessage,
                    isArReady = computeArReady(
                        state = state,
                        hasCameraPermission = true,
                        blockingMessage = retainedBlockingMessage
                    )
                )
            } else {
                state.copy(
                    hasCameraPermission = false,
                    isArReady = false
                )
            }
        }
    }

    fun onSessionCreated(session: Session) {
        currentSession = session
        _uiState.update { state ->
            state.copy(isArReady = computeArReady(state))
        }
    }

    fun onFrameUpdated(frame: Frame, viewportWidthPx: Int, viewportHeightPx: Int) {
        val state = _uiState.value
        val frameResult = arSceneController.processFrame(
            frame = frame,
            viewportWidthPx = viewportWidthPx,
            viewportHeightPx = viewportHeightPx,
            placementMode = state.placementMode
        )
        val previewState = if (state.renderAssetState is RenderAssetState.Preparing) {
            PreviewRenderState.HiddenPreparing
        } else {
            frameResult.previewRenderState
        }
        val previewErrorReason = (previewState as? PreviewRenderState.Error)?.reason
        _uiState.update {
            it.copy(
                isCameraTracking = frameResult.isCameraTracking,
                currentHit = frameResult.hitUiModel,
                previewRenderState = previewState,
                debugRenderStatus = frameResult.debugRenderStatus,
                transientMessage = previewErrorReason
            )
        }
        if (previewErrorReason != null && state.transientMessage != previewErrorReason) {
            viewModelScope.launch {
                emitSideEffect(ArSideEffect.ShowSnackbar(previewErrorReason))
            }
        }
    }

    fun onUiEvent(event: ArUiEvent, hasRecordAudioPermission: Boolean) {
        _uiState.update { it.copy(hasRecordAudioPermission = hasRecordAudioPermission) }
        when (event) {
            ArUiEvent.SelectImageClicked -> onSelectImageClicked()
            ArUiEvent.PlaceClicked -> onPlaceClicked()
            ArUiEvent.RepositionClicked -> onRepositionClicked()
            ArUiEvent.ConfirmRepositionClicked -> onConfirmRepositionClicked()
            ArUiEvent.CancelRepositionClicked -> onCancelRepositionClicked()
            ArUiEvent.DeleteClicked -> onDeleteClicked()
            ArUiEvent.RecordClicked -> onRecordClicked(hasRecordAudioPermission)
            ArUiEvent.StopRecordingClicked -> onStopRecordingClicked()
            ArUiEvent.BackClicked -> onBackRequested()
        }
    }

    fun onArScreenResumed() {
        isArScreenResumed = true
        arSceneController.resume()
    }

    fun onArScreenPaused() {
        isArScreenResumed = false
        arSceneController.pause()
        if (_uiState.value.recordingState is RecordingState.Active) {
            viewModelScope.launch {
                stopRecordingInternal(showSavedMessage = false, force = true)
                emitSideEffect(ArSideEffect.ShowSnackbar("Recording stopped because AR screen is no longer active."))
            }
        }
    }

    fun onArScreenStopped() {
        if (_uiState.value.recordingState is RecordingState.Active) {
            viewModelScope.launch {
                stopRecordingInternal(showSavedMessage = false, force = true)
            }
        }
    }

    fun onImageSelected(uri: Uri?) {
        if (uri == null) {
            viewModelScope.launch {
                emitSideEffect(ArSideEffect.ShowSnackbar("Image selection canceled."))
            }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    selectedImage = null,
                    placedImage = null,
                    placementMode = PlacementMode.WaitingForPlacement,
                    renderAssetState = RenderAssetState.Preparing,
                    previewRenderState = PreviewRenderState.HiddenPreparing,
                    debugRenderStatus = DebugRenderStatus(),
                    transientMessage = null
                )
            }

            when (val metadataResult = loadImageUseCase(uri)) {
                is AppResult.Success -> {
                    val replacement = replaceImageUseCase(metadataResult.value)
                    when (val prepare = arSceneController.prepareSelectedImage(metadataResult.value)) {
                        is AppResult.Success -> {
                            _uiState.update {
                                it.copy(
                                    selectedImage = replacement.selectedImage,
                                    placedImage = replacement.placedImage,
                                    placementMode = replacement.placementMode,
                                    renderAssetState = RenderAssetState.Ready(prepare.value),
                                    previewRenderState = PreviewRenderState.HiddenNoStableHit,
                                    debugRenderStatus = arSceneController.currentDebugRenderStatus(),
                                    blockingMessage = null
                                )
                            }
                        }

                        is AppResult.Failure -> {
                            _uiState.update {
                                it.copy(
                                    selectedImage = null,
                                    placedImage = null,
                                    placementMode = PlacementMode.WaitingForPlacement,
                                    renderAssetState = RenderAssetState.Error(prepare.error.message),
                                    previewRenderState = PreviewRenderState.Error(prepare.error.message),
                                    debugRenderStatus = arSceneController.currentDebugRenderStatus()
                                )
                            }
                            emitSideEffect(ArSideEffect.ShowSnackbar(prepare.error.message))
                        }
                    }
                }

                is AppResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            selectedImage = null,
                            placedImage = null,
                            placementMode = PlacementMode.WaitingForPlacement,
                            renderAssetState = RenderAssetState.Error(metadataResult.error.message),
                            previewRenderState = PreviewRenderState.Error(metadataResult.error.message),
                            debugRenderStatus = arSceneController.currentDebugRenderStatus()
                        )
                    }
                    emitSideEffect(ArSideEffect.ShowSnackbar(metadataResult.error.message))
                }
            }
        }
    }

    fun onRecordAudioPermissionResult(granted: Boolean) {
        _uiState.update { it.copy(hasRecordAudioPermission = granted) }
        if (!canStartRecordingFromArScreen()) {
            _uiState.update { it.copy(recordingState = RecordingState.Idle) }
            viewModelScope.launch {
                emitSideEffect(ArSideEffect.ShowSnackbar("Recording can only start while the AR screen is active."))
            }
            return
        }

        if (granted) {
            viewModelScope.launch {
                emitSideEffect(
                    ArSideEffect.RequestMediaProjectionConsent(recordingController.createConsentIntent())
                )
            }
        } else {
            _uiState.update { it.copy(recordingState = RecordingState.Idle) }
            viewModelScope.launch {
                emitSideEffect(ArSideEffect.ShowSnackbar(AppError.MicrophonePermissionDenied().message))
            }
        }
    }

    fun onMediaProjectionConsentResult(resultCode: Int, data: Intent?) {
        if (!canStartRecordingFromArScreen()) {
            _uiState.update { it.copy(recordingState = RecordingState.Idle) }
            viewModelScope.launch {
                emitSideEffect(ArSideEffect.ShowSnackbar("Recording can only start while the AR screen is active."))
            }
            return
        }

        if (resultCode != Activity.RESULT_OK || data == null) {
            _uiState.update { it.copy(recordingState = RecordingState.Idle) }
            viewModelScope.launch {
                emitSideEffect(ArSideEffect.ShowSnackbar(AppError.MediaProjectionDenied().message))
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(recordingState = RecordingState.Preparing) }
            when (
                val result = startRecordingUseCase(
                    consentResultCode = resultCode,
                    consentData = data,
                    maximumWindowBounds = maximumWindowBounds
                )
            ) {
                is AppResult.Success -> {
                    _uiState.update { it.copy(recordingState = RecordingState.Active(System.currentTimeMillis())) }
                }

                is AppResult.Failure -> {
                    _uiState.update { it.copy(recordingState = RecordingState.Failed(result.error.message)) }
                    emitSideEffect(ArSideEffect.ShowSnackbar(result.error.message))
                }
            }
        }
    }

    fun onMaximumWindowBoundsChanged(bounds: Rect) {
        val previous = maximumWindowBounds
        maximumWindowBounds = Rect(bounds)

        val sizeChanged = previous.width() != 0 &&
            previous.height() != 0 &&
            (previous.width() != bounds.width() || previous.height() != bounds.height())

        if (sizeChanged && _uiState.value.recordingState is RecordingState.Active) {
            viewModelScope.launch {
                stopRecordingInternal(showSavedMessage = false, force = true)
                emitSideEffect(ArSideEffect.ShowSnackbar("Recording stopped due to window size change."))
            }
        }
    }

    fun onRouteExit() {
        isArRouteActive = false
        isArScreenResumed = false
        viewModelScope.launch {
            stopRecordingInternal(showSavedMessage = false, force = true)
        }
        currentSession = null
        arSceneController.release()
        _uiState.value = ArUiState()
    }

    fun onTransformGesture(scaleFactor: Float, rotationDegreesDelta: Float) {
        val state = _uiState.value
        if (state.placementMode != PlacementMode.Placed) {
            return
        }
        val placedImage = state.placedImage ?: return

        val newScale = (placedImage.transform.scale * scaleFactor).coerceIn(0.25f, 4.0f)
        val newRotation = normalizeDegrees(placedImage.transform.rotationYDegrees + rotationDegreesDelta)
        arSceneController.applyTransform(newScale, newRotation)

        _uiState.update {
            it.copy(
                placedImage = placedImage.copy(
                    transform = PlacementTransform(
                        scale = newScale,
                        rotationYDegrees = newRotation
                    )
                )
            )
        }
    }

    internal fun setUiStateForTest(state: ArUiState) {
        _uiState.value = state
    }

    override fun onCleared() {
        super.onCleared()
        arSceneController.release()
        recordingController.release()
    }

    private fun onSelectImageClicked() {
        if (!_uiState.value.canSelectImage) {
            return
        }
        viewModelScope.launch {
            emitSideEffect(ArSideEffect.LaunchImagePicker)
        }
    }

    private fun onPlaceClicked() {
        val state = _uiState.value
        if (!state.canPlace) {
            return
        }
        val preparedAsset = (state.renderAssetState as? RenderAssetState.Ready)?.asset ?: return
        val session = currentSession ?: return

        when (val result = arSceneController.placePreparedImage(session, preparedAsset)) {
            is AppResult.Success -> {
                val placedByUseCase = placeImageUseCase.createPlacedState(
                    anchorId = result.value.anchorId,
                    preparedAsset = preparedAsset,
                    rotationYDegrees = result.value.transform.rotationYDegrees
                ).copy(transform = result.value.transform)

                _uiState.update {
                    it.copy(
                        placedImage = placedByUseCase,
                        placementMode = PlacementMode.Placed,
                        previewRenderState = PreviewRenderState.HiddenNoStableHit,
                        debugRenderStatus = arSceneController.currentDebugRenderStatus()
                    )
                }
            }

            is AppResult.Failure -> {
                when (result.error) {
                    is AppError.StaleOrMissingPreparedAssetHandle,
                    is AppError.MetadataOnlySuccessAttempted,
                    is AppError.DimensionOnlySuccessAttempted,
                    is AppError.PreviewIdentityMismatch -> {
                        _uiState.update {
                            it.copy(
                                renderAssetState = RenderAssetState.Error(result.error.message),
                                previewRenderState = PreviewRenderState.Error(result.error.message)
                            )
                        }
                    }

                    else -> Unit
                }
                viewModelScope.launch {
                    emitSideEffect(ArSideEffect.ShowSnackbar(result.error.message))
                }
            }
        }
    }

    private fun onRepositionClicked() {
        val newMode = enterRepositionModeUseCase(hasPlacedImage = _uiState.value.placedImage != null)
        arSceneController.enterRepositionMode()
        _uiState.update { it.copy(placementMode = newMode) }
    }

    private fun onConfirmRepositionClicked() {
        val session = currentSession ?: return
        when (val result = arSceneController.confirmReposition(session)) {
            is AppResult.Success -> {
                val newMode = confirmRepositionUseCase(
                    hasPlacedImage = true,
                    hasSelectedImage = _uiState.value.selectedImage != null
                )
                _uiState.update {
                    it.copy(
                        placedImage = result.value,
                        placementMode = newMode,
                        debugRenderStatus = arSceneController.currentDebugRenderStatus()
                    )
                }
            }

            is AppResult.Failure -> {
                viewModelScope.launch {
                    emitSideEffect(ArSideEffect.ShowSnackbar(result.error.message))
                }
            }
        }
    }

    private fun onCancelRepositionClicked() {
        arSceneController.cancelReposition()
        _uiState.update { it.copy(placementMode = PlacementMode.Placed) }
    }

    private fun onDeleteClicked() {
        arSceneController.deleteImage()
        val newMode = deleteImageUseCase()
        _uiState.update {
            it.copy(
                placedImage = null,
                placementMode = newMode,
                previewRenderState = PreviewRenderState.HiddenNoStableHit,
                debugRenderStatus = arSceneController.currentDebugRenderStatus()
            )
        }
        viewModelScope.launch {
            emitSideEffect(ArSideEffect.ShowSnackbar("Image removed"))
        }
    }

    private fun onRecordClicked(hasRecordAudioPermission: Boolean) {
        if (!canStartRecordingFromArScreen()) {
            viewModelScope.launch {
                emitSideEffect(ArSideEffect.ShowSnackbar("Recording can only start from the active AR screen."))
            }
            return
        }
        if (!requestRecordingUseCase(_uiState.value.recordingState)) {
            return
        }
        if (!_uiState.value.canRecord) {
            return
        }

        _uiState.update { it.copy(recordingState = RecordingState.Preparing) }
        viewModelScope.launch {
            if (!hasRecordAudioPermission) {
                emitSideEffect(ArSideEffect.RequestRecordAudioPermission)
            } else {
                emitSideEffect(
                    ArSideEffect.RequestMediaProjectionConsent(recordingController.createConsentIntent())
                )
            }
        }
    }

    private fun onStopRecordingClicked() {
        viewModelScope.launch {
            stopRecordingInternal(showSavedMessage = true)
        }
    }

    private fun onBackRequested() {
        viewModelScope.launch {
            if (_uiState.value.recordingState !is RecordingState.Idle &&
                _uiState.value.recordingState !is RecordingState.Failed
            ) {
                stopRecordingInternal(showSavedMessage = false, force = true)
            }
            emitSideEffect(ArSideEffect.NavigateBack)
        }
    }

    private suspend fun stopRecordingInternal(
        showSavedMessage: Boolean,
        force: Boolean = false
    ) = stopRecordingMutex.withLock {
        val state = _uiState.value.recordingState
        if (!force && state is RecordingState.Idle) {
            return@withLock
        }
        if (state is RecordingState.Finalizing) {
            return@withLock
        }

        _uiState.update { it.copy(recordingState = RecordingState.Finalizing) }
        when (val result = stopRecordingUseCase()) {
            is AppResult.Success -> {
                _uiState.update { it.copy(recordingState = RecordingState.Idle) }
                if (showSavedMessage) {
                    emitSideEffect(ArSideEffect.ShowSnackbar("Recording saved"))
                }
            }

            is AppResult.Failure -> {
                _uiState.update { it.copy(recordingState = RecordingState.Failed(result.error.message)) }
                emitSideEffect(ArSideEffect.ShowSnackbar(result.error.message))
            }
        }
    }

    private suspend fun emitSideEffect(effect: ArSideEffect) {
        _sideEffects.emit(effect)
    }

    private fun computeArReady(
        state: ArUiState,
        hasCameraPermission: Boolean = state.hasCameraPermission,
        arAvailability: ArAvailability = state.arAvailability,
        blockingMessage: String? = state.blockingMessage
    ): Boolean {
        val availabilityReady = arAvailability == ArAvailability.Supported ||
            arAvailability == ArAvailability.Checking ||
            arAvailability == ArAvailability.Unknown
        return hasCameraPermission &&
            availabilityReady &&
            blockingMessage == null &&
            currentSession != null
    }

    private fun canStartRecordingFromArScreen(): Boolean = isArRouteActive && isArScreenResumed

    private fun normalizeDegrees(value: Float): Float {
        var normalized = value % 360f
        if (normalized < 0f) {
            normalized += 360f
        }
        return normalized
    }

    companion object {
        fun provideFactory(appContainer: AppContainer): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val arSceneController = appContainer.createArSceneController()
                    val recordingController = appContainer.createRecordingController()
                    return ArViewModel(
                        loadImageUseCase = appContainer.loadImageUseCase,
                        placeImageUseCase = appContainer.placeImageUseCase,
                        replaceImageUseCase = appContainer.replaceImageUseCase,
                        deleteImageUseCase = appContainer.deleteImageUseCase,
                        enterRepositionModeUseCase = appContainer.enterRepositionModeUseCase,
                        confirmRepositionUseCase = appContainer.confirmRepositionUseCase,
                        requestRecordingUseCase = appContainer.requestRecordingUseCase,
                        startRecordingUseCase = StartRecordingUseCase(recordingController),
                        stopRecordingUseCase = StopRecordingUseCase(recordingController),
                        arAvailabilityChecker = appContainer.arAvailabilityChecker,
                        arSceneController = arSceneController,
                        recordingController = recordingController
                    ) as T
                }
            }
        }
    }
}

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
import com.example.arspatialpinning.domain.model.PlacementMode
import com.example.arspatialpinning.domain.model.PlacementTransform
import com.example.arspatialpinning.domain.model.RecordingState
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
import com.example.arspatialpinning.platform.ar.HitTestResult
import com.example.arspatialpinning.platform.media.RecordingController
import com.google.ar.core.Frame
import com.google.ar.core.Pose
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
import kotlin.math.abs

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
    private var stabilizedHit: HitTestResult? = null
    private var candidateTrackableId: String? = null
    private var candidatePose: Pose? = null
    private var stabilizationFrames: Int = 0
    private var maximumWindowBounds: Rect = Rect(0, 0, 1080, 1920)
    private var isArRouteActive: Boolean = true
    private var isArScreenResumed: Boolean = false

    init {
        recordingController.onProjectionStopped = {
            viewModelScope.launch {
                stopRecordingInternal(showSavedMessage = false)
                emitSideEffect(ArSideEffect.ShowSnackbar("Recording stopped by the system."))
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
        val availabilityError = when (val availability = arAvailabilityChecker.checkAvailability()) {
            is AppResult.Success -> null
            is AppResult.Failure -> availability.error
        }

        if (isCameraPermissionGranted) {
            _uiState.update {
                it.copy(
                    cameraPermissionGranted = true,
                    blockingError = availabilityError,
                    isArReady = computeArReady(
                        it,
                        cameraPermissionGranted = true,
                        blockingError = availabilityError
                    )
                )
            }
        } else {
            _uiState.update {
                it.copy(
                    cameraPermissionGranted = false,
                    blockingError = availabilityError,
                    isArReady = false
                )
            }
            viewModelScope.launch {
                emitSideEffect(ArSideEffect.RequestCameraPermission)
            }
        }
    }

    fun onCameraPermissionResult(granted: Boolean) {
        _uiState.update {
            if (granted) {
                val retainedBlockingError = when (it.blockingError) {
                    is AppError.PermissionDenied -> null
                    else -> it.blockingError
                }
                it.copy(
                    cameraPermissionGranted = true,
                    blockingError = retainedBlockingError,
                    isArReady = computeArReady(
                        it,
                        cameraPermissionGranted = true,
                        blockingError = retainedBlockingError
                    )
                )
            } else {
                it.copy(
                    cameraPermissionGranted = false,
                    blockingError = AppError.PermissionDenied(
                        message = "Camera permission is required to use AR."
                    ),
                    isArReady = false
                )
            }
        }
    }

    fun onSessionCreated(session: Session) {
        currentSession = session
        _uiState.update { it.copy(isArReady = computeArReady(it)) }
        syncPlacementPreview()
    }

    fun onFrameUpdated(frame: Frame, viewportWidthPx: Int, viewportHeightPx: Int) {
        val hit = arSceneController.computeCenterHit(frame, viewportWidthPx, viewportHeightPx)
        updateReticleState(hit)
        syncPlacementPreview()
    }

    fun onUiEvent(event: ArUiEvent, hasRecordAudioPermission: Boolean) {
        when (event) {
            ArUiEvent.SelectImageClicked -> onSelectImageClicked()
            ArUiEvent.PlaceClicked -> onPlaceClicked()
            ArUiEvent.MoveClicked -> onMoveClicked()
            ArUiEvent.ConfirmMoveClicked -> onConfirmMoveClicked()
            ArUiEvent.DeleteClicked -> onDeleteClicked()
            ArUiEvent.RecordClicked -> onRecordClicked(hasRecordAudioPermission)
            ArUiEvent.StopRecordingClicked -> onStopRecordingClicked()
            ArUiEvent.BackClicked -> onBackRequested()
        }
    }

    fun onArScreenResumed() {
        isArScreenResumed = true
    }

    fun onArScreenPaused() {
        isArScreenResumed = false
        if (_uiState.value.recordingState is RecordingState.Active) {
            viewModelScope.launch {
                stopRecordingInternal(showSavedMessage = false)
                emitSideEffect(ArSideEffect.ShowSnackbar("Recording stopped because AR screen is no longer active."))
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
            when (val result = loadImageUseCase(uri)) {
                is AppResult.Success -> {
                    when (val prepareResult = arSceneController.prepareSelectedImage(result.value)) {
                        is AppResult.Failure -> {
                            handleError(prepareResult.error)
                            return@launch
                        }

                        is AppResult.Success -> Unit
                    }

                    val replacement = replaceImageUseCase(result.value)
                    clearReticleStabilization()
                    _uiState.update {
                        it.copy(
                            selectedImage = replacement.selectedImage,
                            placedImage = replacement.placedImage,
                            placementMode = replacement.placementMode,
                            isImagePrepared = true,
                            blockingError = null
                        )
                    }
                    syncPlacementPreview()
                }

                is AppResult.Failure -> {
                    handleError(result.error)
                }
            }
        }
    }

    fun onRecordAudioPermissionResult(granted: Boolean) {
        if (!canStartRecordingFromArScreen()) {
            _uiState.update { it.copy(recordingState = RecordingState.Idle) }
            viewModelScope.launch {
                emitSideEffect(ArSideEffect.ShowSnackbar("Recording can only start while the AR screen is active."))
            }
            return
        }

        if (granted) {
            viewModelScope.launch {
                emitAppWindowRecordingGuidanceIfSupported()
                emitSideEffect(
                    ArSideEffect.RequestMediaProjectionConsent(recordingController.createConsentIntent())
                )
            }
        } else {
            _uiState.update { it.copy(recordingState = RecordingState.Idle) }
            viewModelScope.launch {
                emitSideEffect(
                    ArSideEffect.ShowSnackbar("Microphone permission is required to record.")
                )
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
                emitSideEffect(ArSideEffect.ShowSnackbar("Screen capture consent was canceled."))
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
                stopRecordingInternal(showSavedMessage = false)
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
        clearReticleStabilization()
        arSceneController.clear()
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

    override fun onCleared() {
        super.onCleared()
        arSceneController.clear()
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
        val selectedImage = state.selectedImage ?: return
        val hit = stabilizedHit ?: return
        val session = currentSession ?: return

        when (val result = arSceneController.placeImage(session, selectedImage, hit)) {
            is AppResult.Success -> {
                val placedByUseCase = placeImageUseCase.createPlacedState(
                    anchorId = result.value.anchorId,
                    selectedImage = selectedImage,
                    rotationYDegrees = result.value.transform.rotationYDegrees
                ).copy(transform = result.value.transform)

                _uiState.update {
                    it.copy(
                        placedImage = placedByUseCase,
                        placementMode = PlacementMode.Placed
                    )
                }
                syncPlacementPreview()
            }

            is AppResult.Failure -> {
                handleError(result.error)
            }
        }
    }

    private fun onMoveClicked() {
        val newMode = enterRepositionModeUseCase(hasPlacedImage = _uiState.value.placedImage != null)
        _uiState.update { it.copy(placementMode = newMode) }
        syncPlacementPreview()
    }

    private fun onConfirmMoveClicked() {
        val hit = stabilizedHit ?: return
        val session = currentSession ?: return
        when (val result = arSceneController.reposition(session, hit)) {
            is AppResult.Success -> {
                val newMode = confirmRepositionUseCase(
                    hasPlacedImage = true,
                    hasSelectedImage = _uiState.value.selectedImage != null
                )
                _uiState.update {
                    it.copy(
                        placedImage = result.value,
                        placementMode = newMode
                    )
                }
                syncPlacementPreview()
            }

            is AppResult.Failure -> {
                viewModelScope.launch {
                    emitSideEffect(ArSideEffect.ShowSnackbar(result.error.message))
                }
            }
        }
    }

    private fun onDeleteClicked() {
        arSceneController.deleteImage()
        val newMode = deleteImageUseCase()
        _uiState.update {
            it.copy(
                placedImage = null,
                placementMode = newMode
            )
        }
        syncPlacementPreview()
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

        _uiState.update { it.copy(recordingState = RecordingState.Preparing) }
        viewModelScope.launch {
            if (!hasRecordAudioPermission) {
                emitSideEffect(ArSideEffect.RequestRecordAudioPermission)
            } else {
                emitAppWindowRecordingGuidanceIfSupported()
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
                stopRecordingInternal(showSavedMessage = false)
            }
            emitSideEffect(ArSideEffect.NavigateBack)
        }
    }

    private suspend fun stopRecordingInternal(
        showSavedMessage: Boolean,
        force: Boolean = false
    ) {
        val state = _uiState.value.recordingState
        if (!force && state is RecordingState.Idle) {
            return
        }

        if (state !is RecordingState.Finalizing) {
            _uiState.update { it.copy(recordingState = RecordingState.Finalizing) }
        }

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

    private fun handleError(error: AppError) {
        if (error.blocking) {
            _uiState.update { it.copy(blockingError = error, isArReady = false) }
            return
        }

        viewModelScope.launch {
            emitSideEffect(ArSideEffect.ShowSnackbar(error.message))
        }
    }

    private fun updateReticleState(hit: HitTestResult?) {
        if (hit == null) {
            clearReticleStabilization()
            _uiState.update {
                it.copy(
                    reticle = ReticleUiState(
                        hasValidHit = false,
                        stabilizationFrames = 0,
                        isStabilized = false
                    )
                )
            }
            return
        }

        val sameTrackable = candidateTrackableId == hit.trackableId
        val samePose = candidatePose?.let { isApproxSamePose(it, hit.hitPose) } ?: false

        stabilizationFrames = if (sameTrackable && samePose) {
            stabilizationFrames + 1
        } else {
            1
        }

        candidateTrackableId = hit.trackableId
        candidatePose = hit.hitPose

        val stabilized = stabilizationFrames >= REQUIRED_STABLE_FRAMES
        stabilizedHit = if (stabilized) hit else null

        _uiState.update {
            it.copy(
                reticle = ReticleUiState(
                    hasValidHit = true,
                    stabilizationFrames = stabilizationFrames,
                    isStabilized = stabilized
                )
            )
        }
    }

    private fun clearReticleStabilization() {
        candidateTrackableId = null
        candidatePose = null
        stabilizationFrames = 0
        stabilizedHit = null
    }

    private fun isApproxSamePose(first: Pose, second: Pose): Boolean {
        val t1 = first.translation
        val t2 = second.translation
        return abs(t1[0] - t2[0]) < 0.02f &&
            abs(t1[1] - t2[1]) < 0.02f &&
            abs(t1[2] - t2[2]) < 0.02f
    }

    private fun normalizeDegrees(value: Float): Float {
        var normalized = value % 360f
        if (normalized < 0f) {
            normalized += 360f
        }
        return normalized
    }

    private suspend fun emitSideEffect(effect: ArSideEffect) {
        _sideEffects.emit(effect)
    }

    private fun syncPlacementPreview() {
        val state = _uiState.value
        val shouldPreview = state.placementMode == PlacementMode.WaitingForPlacement &&
            state.selectedImage != null &&
            state.isImagePrepared &&
            state.isArReady

        if (!shouldPreview) {
            arSceneController.hidePlacementPreview()
            return
        }

        arSceneController.updatePlacementPreview(stabilizedHit)
    }

    private fun computeArReady(
        state: ArUiState,
        cameraPermissionGranted: Boolean = state.cameraPermissionGranted,
        blockingError: AppError? = state.blockingError
    ): Boolean {
        return cameraPermissionGranted && blockingError == null && currentSession != null
    }

    private fun canStartRecordingFromArScreen(): Boolean = isArRouteActive && isArScreenResumed

    private suspend fun emitAppWindowRecordingGuidanceIfSupported() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            emitSideEffect(
                ArSideEffect.ShowSnackbar("In the next dialog, select this app window to record.")
            )
        }
    }

    companion object {
        private const val REQUIRED_STABLE_FRAMES = 3

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

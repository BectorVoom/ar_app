package com.example.arspatialpinning.platform.media

import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import com.example.arspatialpinning.common.AppError
import com.example.arspatialpinning.common.AppResult
import com.example.arspatialpinning.domain.model.RecordedVideoArtifact
import com.example.arspatialpinning.domain.model.RecordingState
import com.example.arspatialpinning.domain.usecase.DownloadRecordingUseCase
import com.example.arspatialpinning.domain.usecase.RequestRecordingUseCase
import com.example.arspatialpinning.domain.usecase.StartRecordingUseCase
import com.example.arspatialpinning.domain.usecase.StopRecordingUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SharedRecordingUiState(
    val recordingState: RecordingState = RecordingState.Idle,
    val lastCompletedRecording: RecordedVideoArtifact? = null,
    val hasRecordAudioPermission: Boolean = false,
    val isAppResumed: Boolean = false,
    val transientMessage: String? = null
) {
    val canRecord: Boolean = isAppResumed && recordingState is RecordingState.Idle
    val canDownloadRecording: Boolean =
        recordingState is RecordingState.Idle && lastCompletedRecording != null
}

sealed interface SharedRecordingSideEffect {
    data object RequestRecordAudioPermission : SharedRecordingSideEffect
    data class RequestMediaProjectionConsent(val intent: Intent) : SharedRecordingSideEffect
    data class LaunchDownloadDestinationPicker(val suggestedFileName: String) : SharedRecordingSideEffect
    data class ShowSnackbar(val message: String) : SharedRecordingSideEffect
}

class SharedRecordingStateHolder(
    private val requestRecordingUseCase: RequestRecordingUseCase,
    private val startRecordingUseCase: StartRecordingUseCase,
    private val stopRecordingUseCase: StopRecordingUseCase,
    private val downloadRecordingUseCase: DownloadRecordingUseCase,
    private val recordingController: RecordingController
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val transitionMutex = Mutex()

    private val _uiState = MutableStateFlow(SharedRecordingUiState())
    val uiState: StateFlow<SharedRecordingUiState> = _uiState.asStateFlow()

    private val _sideEffects = MutableSharedFlow<SharedRecordingSideEffect>()
    val sideEffects: SharedFlow<SharedRecordingSideEffect> = _sideEffects.asSharedFlow()

    private var maximumWindowBounds = Rect(0, 0, DEFAULT_CAPTURE_WIDTH, DEFAULT_CAPTURE_HEIGHT)
    private var pendingStartAfterPermission = false
    private var pendingStopAfterPreparing = false

    init {
        recordingController.onProjectionStopped = {
            scope.launch {
                requestStopRecording(showSavedMessage = false, force = true)
                emitSnackbar(AppError.RecorderStoppedUnexpectedly().message)
            }
        }
    }

    fun onRecordAudioPermissionStateObserved(granted: Boolean) {
        _uiState.update { it.copy(hasRecordAudioPermission = granted) }
    }

    fun onAppResumed() {
        _uiState.update { it.copy(isAppResumed = true) }
    }

    fun onAppPaused() {
        _uiState.update { it.copy(isAppResumed = false) }
    }

    fun onAppStopped() {
        val state = _uiState.value.recordingState
        if (state is RecordingState.Idle || state is RecordingState.Finalizing) {
            return
        }
        scope.launch {
            requestStopRecording(showSavedMessage = false, force = true)
        }
    }

    fun onMaximumWindowBoundsChanged(bounds: Rect) {
        val previous = maximumWindowBounds
        maximumWindowBounds = Rect(bounds)
        val sizeChanged = previous.width() > 0 &&
            previous.height() > 0 &&
            (previous.width() != bounds.width() || previous.height() != bounds.height())
        if (sizeChanged && _uiState.value.recordingState is RecordingState.Active) {
            scope.launch {
                requestStopRecording(showSavedMessage = false, force = true)
                emitSnackbar("Recording stopped due to window size change.")
            }
        }
    }

    fun onRecordClick() {
        val state = _uiState.value
        if (!requestRecordingUseCase(state.recordingState) || !state.canRecord) {
            return
        }
        if (!state.hasRecordAudioPermission) {
            pendingStartAfterPermission = true
            scope.launch {
                _sideEffects.emit(SharedRecordingSideEffect.RequestRecordAudioPermission)
            }
            return
        }
        pendingStartAfterPermission = false
        emitMediaProjectionConsentRequest()
    }

    fun onRecordAudioPermissionResult(granted: Boolean) {
        onRecordAudioPermissionStateObserved(granted)
        val shouldContinue = pendingStartAfterPermission
        pendingStartAfterPermission = false
        if (!shouldContinue) {
            return
        }

        if (granted) {
            val state = _uiState.value
            if (state.recordingState !is RecordingState.Idle) {
                return
            }
            emitMediaProjectionConsentRequest()
        } else {
            scope.launch {
                emitSnackbar(AppError.MicrophonePermissionDenied().message)
            }
        }
    }

    fun onMediaProjectionConsentResult(resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            scope.launch {
                emitSnackbar(AppError.MediaProjectionDenied().message)
            }
            return
        }

        scope.launch {
            startRecordingInternal(resultCode, data)
        }
    }

    fun onStopRecordClick(showSavedMessage: Boolean = true) {
        scope.launch {
            requestStopRecording(showSavedMessage = showSavedMessage)
        }
    }

    fun onDownloadRecordingClick() {
        val state = _uiState.value
        val artifact = state.lastCompletedRecording ?: return
        if (!state.canDownloadRecording) {
            return
        }
        scope.launch {
            _sideEffects.emit(
                SharedRecordingSideEffect.LaunchDownloadDestinationPicker(
                    suggestedFileName = artifact.displayName
                )
            )
        }
    }

    fun onDownloadDestinationSelected(destinationUri: Uri?) {
        val uri = destinationUri ?: return
        val state = _uiState.value
        val artifact = state.lastCompletedRecording ?: return
        if (state.recordingState !is RecordingState.Idle) {
            return
        }

        scope.launch {
            when (
                val result = downloadRecordingUseCase(
                    sourceUri = artifact.sourceUri,
                    destinationUri = uri
                )
            ) {
                is AppResult.Success -> emitSnackbar("Recording exported")
                is AppResult.Failure -> emitSnackbar(result.error.message)
            }
        }
    }

    fun clearTransientMessage() {
        _uiState.update { it.copy(transientMessage = null) }
    }

    fun release() {
        recordingController.onProjectionStopped = null
        runBlocking {
            requestStopRecording(showSavedMessage = false, force = true)
        }
        recordingController.release()
        scope.coroutineContext.cancel()
    }

    private suspend fun startRecordingInternal(
        resultCode: Int,
        data: Intent
    ) = transitionMutex.withLock {
        val state = _uiState.value
        if (state.recordingState !is RecordingState.Idle) {
            return@withLock
        }

        _uiState.update { it.copy(recordingState = RecordingState.Preparing) }
        val result = try {
            startRecordingUseCase(
                consentResultCode = resultCode,
                consentData = data,
                maximumWindowBounds = maximumWindowBounds
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: IllegalStateException) {
            AppResult.Failure(AppError.RecorderStartFailed())
        } catch (error: RuntimeException) {
            // Defensive: platform/media layers can still surface runtime faults.
            AppResult.Failure(AppError.RecorderStartFailed())
        }
        when (result) {
            is AppResult.Success -> {
                _uiState.update {
                    it.copy(recordingState = RecordingState.Active(System.currentTimeMillis()))
                }
                if (pendingStopAfterPreparing) {
                    pendingStopAfterPreparing = false
                    stopRecordingLocked(showSavedMessage = false, force = true)
                }
            }

            is AppResult.Failure -> {
                pendingStopAfterPreparing = false
                _uiState.update { it.copy(recordingState = RecordingState.Idle) }
                emitSnackbar(result.error.message)
            }
        }
    }

    private suspend fun requestStopRecording(
        showSavedMessage: Boolean,
        force: Boolean = false
    ) = transitionMutex.withLock {
        stopRecordingLocked(showSavedMessage = showSavedMessage, force = force)
    }

    private suspend fun stopRecordingLocked(
        showSavedMessage: Boolean,
        force: Boolean
    ) {
        val state = _uiState.value.recordingState
        if (!force && state is RecordingState.Idle) {
            return
        }
        if (state is RecordingState.Finalizing) {
            return
        }
        if (state is RecordingState.Preparing) {
            pendingStopAfterPreparing = true
            return
        }

        _uiState.update { it.copy(recordingState = RecordingState.Finalizing) }
        when (val result = safeStopRecording()) {
            is AppResult.Success -> {
                _uiState.update {
                    it.copy(
                        recordingState = RecordingState.Idle,
                        lastCompletedRecording = result.value ?: it.lastCompletedRecording
                    )
                }
                if (showSavedMessage && result.value != null) {
                    emitSnackbar("Recording saved")
                }
            }

            is AppResult.Failure -> {
                _uiState.update { it.copy(recordingState = RecordingState.Idle) }
                emitSnackbar(result.error.message)
            }
        }
    }

    private suspend fun safeStopRecording(): AppResult<RecordedVideoArtifact?> {
        return try {
            stopRecordingUseCase()
        } catch (error: CancellationException) {
            throw error
        } catch (error: IllegalStateException) {
            AppResult.Failure(AppError.RecorderStopFailed())
        } catch (error: RuntimeException) {
            // Defensive: platform/media layers can still surface runtime faults.
            AppResult.Failure(AppError.RecorderStopFailed())
        }
    }

    private suspend fun emitSnackbar(message: String) {
        _uiState.update { it.copy(transientMessage = message) }
        _sideEffects.emit(SharedRecordingSideEffect.ShowSnackbar(message))
    }

    private fun emitMediaProjectionConsentRequest() {
        scope.launch {
            when (val consentIntent = recordingController.createConsentIntent()) {
                is AppResult.Success -> {
                    _sideEffects.emit(
                        SharedRecordingSideEffect.RequestMediaProjectionConsent(consentIntent.value)
                    )
                }

                is AppResult.Failure -> emitSnackbar(consentIntent.error.message)
            }
        }
    }

    private companion object {
        private const val DEFAULT_CAPTURE_WIDTH = 1080
        private const val DEFAULT_CAPTURE_HEIGHT = 1920
    }
}

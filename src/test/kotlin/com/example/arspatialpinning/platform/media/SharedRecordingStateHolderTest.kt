package com.example.arspatialpinning.platform.media

import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import app.cash.turbine.test
import com.example.arspatialpinning.common.AppError
import com.example.arspatialpinning.common.AppResult
import com.example.arspatialpinning.domain.model.RecordedVideoArtifact
import com.example.arspatialpinning.domain.usecase.DownloadRecordingUseCase
import com.example.arspatialpinning.domain.usecase.RequestRecordingUseCase
import com.example.arspatialpinning.domain.usecase.StartRecordingUseCase
import com.example.arspatialpinning.domain.usecase.StopRecordingUseCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SharedRecordingStateHolderTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun recordClick_withoutMicrophonePermission_requestsPermission() = runTest {
        val holder = createHolder()
        holder.onAppResumed()
        holder.onRecordAudioPermissionStateObserved(false)

        holder.sideEffects.test {
            holder.onRecordClick()
            assertEquals(SharedRecordingSideEffect.RequestRecordAudioPermission, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        holder.release()
    }

    @Test
    fun recordClick_whenConsentIntentCreationFails_emitsMappedFailure() = runTest {
        val controller = FakeRecordingController().apply {
            consentIntentResult = AppResult.Failure(AppError.MediaProjectionConsentIntentFailed())
        }
        val holder = createHolder(recordingController = controller)
        holder.onAppResumed()
        holder.onRecordAudioPermissionStateObserved(true)

        holder.sideEffects.test {
            holder.onRecordClick()
            assertEquals(
                SharedRecordingSideEffect.ShowSnackbar(AppError.MediaProjectionConsentIntentFailed().message),
                awaitItem()
            )
            cancelAndIgnoreRemainingEvents()
        }

        holder.release()
    }

    @Test
    fun validatedStop_updatesLastCompletedRecording_onlyAfterStopSuccess() = runTest {
        val artifact = RecordedVideoArtifact(
            sourceUri = Uri.parse("content://recordings/validated.mp4"),
            displayName = "validated.mp4"
        )
        val recordingController = FakeRecordingController().apply {
            stopResult = AppResult.Success(artifact)
        }
        val holder = createHolder(recordingController = recordingController)
        holder.onAppResumed()
        holder.onRecordAudioPermissionStateObserved(true)

        holder.sideEffects.test {
            holder.onRecordClick()
            val consent = awaitItem()
            assertTrue(consent is SharedRecordingSideEffect.RequestMediaProjectionConsent)
            cancelAndIgnoreRemainingEvents()
        }

        holder.onMediaProjectionConsentResult(Activity.RESULT_OK, Intent("projection-consent"))
        advanceUntilIdle()
        assertTrue(holder.uiState.value.recordingState is com.example.arspatialpinning.domain.model.RecordingState.Active)
        assertNull(holder.uiState.value.lastCompletedRecording)

        holder.onStopRecordClick(showSavedMessage = false)
        advanceUntilIdle()

        assertTrue(holder.uiState.value.recordingState is com.example.arspatialpinning.domain.model.RecordingState.Idle)
        assertEquals(artifact, holder.uiState.value.lastCompletedRecording)
        holder.release()
    }

    @Test
    fun repeatedStopRequests_areIdempotent_afterFinalizationStarts() = runTest {
        val stopGate = CompletableDeferred<Unit>()
        val recordingController = FakeRecordingController().apply {
            stopCompletion = stopGate
            stopResult = AppResult.Success(null)
        }
        val holder = createHolder(recordingController = recordingController)
        holder.onAppResumed()
        holder.onRecordAudioPermissionStateObserved(true)
        holder.onMediaProjectionConsentResult(Activity.RESULT_OK, Intent("projection-consent"))
        advanceUntilIdle()

        holder.onStopRecordClick(showSavedMessage = false)
        holder.onStopRecordClick(showSavedMessage = false)
        advanceUntilIdle()

        assertEquals(1, recordingController.stopCalls)
        stopGate.complete(Unit)
        advanceUntilIdle()
        holder.release()
    }

    @Test
    fun stopRequestedDuringPreparing_isAppliedAfterStart_andKeepsDownloadableArtifact() = runTest {
        val startGate = CompletableDeferred<Unit>()
        val artifact = RecordedVideoArtifact(
            sourceUri = Uri.parse("content://recordings/queued-stop.mp4"),
            displayName = "queued-stop.mp4"
        )
        val recordingController = FakeRecordingController().apply {
            startCompletion = startGate
            stopResult = AppResult.Success(artifact)
        }
        val holder = createHolder(recordingController = recordingController)
        holder.onAppResumed()
        holder.onRecordAudioPermissionStateObserved(true)

        holder.onMediaProjectionConsentResult(Activity.RESULT_OK, Intent("projection-consent"))
        advanceUntilIdle()
        assertTrue(holder.uiState.value.recordingState is com.example.arspatialpinning.domain.model.RecordingState.Preparing)

        holder.onStopRecordClick(showSavedMessage = false)
        advanceUntilIdle()
        assertTrue(holder.uiState.value.recordingState is com.example.arspatialpinning.domain.model.RecordingState.Preparing)
        assertEquals(0, recordingController.stopCalls)

        startGate.complete(Unit)
        advanceUntilIdle()

        assertTrue(holder.uiState.value.recordingState is com.example.arspatialpinning.domain.model.RecordingState.Idle)
        assertEquals(1, recordingController.stopCalls)
        assertEquals(artifact, holder.uiState.value.lastCompletedRecording)
        holder.release()
    }

    @Test
    fun exportFailure_keepsLastCompletedRecording_availableForRetry() = runTest {
        val artifact = RecordedVideoArtifact(
            sourceUri = Uri.parse("content://recordings/validated.mp4"),
            displayName = "validated.mp4"
        )
        val recordingController = FakeRecordingController().apply {
            stopResult = AppResult.Success(artifact)
        }
        val exporter = FakeRecordingExporter().apply {
            result = AppResult.Failure(AppError.DownloadExportFailed())
        }
        val holder = createHolder(recordingController = recordingController, recordingExporter = exporter)
        holder.onAppResumed()
        holder.onRecordAudioPermissionStateObserved(true)
        holder.onMediaProjectionConsentResult(Activity.RESULT_OK, Intent("projection-consent"))
        advanceUntilIdle()
        holder.onStopRecordClick(showSavedMessage = false)
        advanceUntilIdle()

        holder.onDownloadDestinationSelected(Uri.parse("content://downloads/target.mp4"))
        advanceUntilIdle()

        assertEquals(artifact, holder.uiState.value.lastCompletedRecording)
        holder.release()
    }

    @Test
    fun appStopped_whileRecording_stopsDefensively() = runTest {
        val recordingController = FakeRecordingController().apply {
            stopResult = AppResult.Success(null)
        }
        val holder = createHolder(recordingController = recordingController)
        holder.onAppResumed()
        holder.onRecordAudioPermissionStateObserved(true)
        holder.onMediaProjectionConsentResult(Activity.RESULT_OK, Intent("projection-consent"))
        advanceUntilIdle()

        holder.onAppStopped()
        advanceUntilIdle()

        assertEquals(1, recordingController.stopCalls)
        assertTrue(holder.uiState.value.recordingState is com.example.arspatialpinning.domain.model.RecordingState.Idle)
        holder.release()
    }

    @Test
    fun stopRecording_exception_resetsStateToIdle_andShowsFailure() = runTest {
        val recordingController = FakeRecordingController().apply {
            throwOnStop = true
        }
        val holder = createHolder(recordingController = recordingController)
        holder.onAppResumed()
        holder.onRecordAudioPermissionStateObserved(true)
        holder.onMediaProjectionConsentResult(Activity.RESULT_OK, Intent("projection-consent"))
        advanceUntilIdle()

        holder.sideEffects.test {
            holder.onStopRecordClick(showSavedMessage = false)
            assertEquals(
                SharedRecordingSideEffect.ShowSnackbar(AppError.RecorderStopFailed().message),
                awaitItem()
            )
            cancelAndIgnoreRemainingEvents()
        }

        assertTrue(holder.uiState.value.recordingState is com.example.arspatialpinning.domain.model.RecordingState.Idle)
        holder.release()
    }

    private fun createHolder(
        recordingController: FakeRecordingController = FakeRecordingController(),
        recordingExporter: FakeRecordingExporter = FakeRecordingExporter()
    ): SharedRecordingStateHolder {
        return SharedRecordingStateHolder(
            requestRecordingUseCase = RequestRecordingUseCase(),
            startRecordingUseCase = StartRecordingUseCase(recordingController),
            stopRecordingUseCase = StopRecordingUseCase(recordingController),
            downloadRecordingUseCase = DownloadRecordingUseCase(recordingExporter),
            recordingController = recordingController
        )
    }

    private class FakeRecordingController : RecordingController {
        var stopCalls: Int = 0
        var startCompletion: CompletableDeferred<Unit>? = null
        var stopCompletion: CompletableDeferred<Unit>? = null
        var startResult: AppResult<Unit> = AppResult.Success(Unit)
        var stopResult: AppResult<RecordedVideoArtifact?> = AppResult.Success(null)
        var consentIntentResult: AppResult<Intent> = AppResult.Success(Intent("projection-consent"))
        var throwOnStop: Boolean = false
        override var onProjectionStopped: (() -> Unit)? = null

        override fun createConsentIntent(): AppResult<Intent> = consentIntentResult

        override suspend fun startRecording(
            consentResultCode: Int,
            consentData: Intent,
            maximumWindowBounds: Rect
        ): AppResult<Unit> {
            startCompletion?.await()
            return startResult
        }

        override suspend fun stopRecording(): AppResult<RecordedVideoArtifact?> {
            stopCalls += 1
            stopCompletion?.await()
            if (throwOnStop) {
                throw IllegalStateException("stop failed")
            }
            return stopResult
        }

        override fun release() = Unit
    }

    private class FakeRecordingExporter : RecordingExporter {
        var result: AppResult<Unit> = AppResult.Success(Unit)

        override suspend fun exportRecording(sourceUri: Uri, destinationUri: Uri): AppResult<Unit> {
            return result
        }
    }

    class MainDispatcherRule(
        private val dispatcher: TestDispatcher = StandardTestDispatcher()
    ) : TestWatcher() {
        override fun starting(description: Description) {
            Dispatchers.setMain(dispatcher)
        }

        override fun finished(description: Description) {
            Dispatchers.resetMain()
        }
    }
}

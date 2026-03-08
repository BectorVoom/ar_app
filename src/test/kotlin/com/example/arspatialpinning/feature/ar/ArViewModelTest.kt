package com.example.arspatialpinning.feature.ar

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import app.cash.turbine.test
import com.example.arspatialpinning.common.AppError
import com.example.arspatialpinning.common.AppResult
import com.example.arspatialpinning.common.DefaultDispatcherProvider
import com.example.arspatialpinning.domain.model.PlacedImageState
import com.example.arspatialpinning.domain.model.PlacementTransform
import com.example.arspatialpinning.domain.model.RecordingState
import com.example.arspatialpinning.domain.model.SelectedImage
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
import com.example.arspatialpinning.platform.file.AndroidImageUriReader
import com.example.arspatialpinning.platform.file.ImageValidator
import com.example.arspatialpinning.platform.media.RecordingController
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.android.filament.Engine
import io.github.sceneview.node.Node
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ArViewModelTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun onRouteExit_clearsState_andReleasesOwnedResources() = runTest {
        val fakeArSceneController = FakeArSceneController()
        val fakeRecordingController = FakeRecordingController()
        val viewModel = buildViewModel(fakeArSceneController, fakeRecordingController)

        viewModel.onRouteExit()
        advanceUntilIdle()

        assertEquals(1, fakeArSceneController.clearCalls)
        assertEquals(1, fakeRecordingController.stopCalls)
        assertEquals(ArUiState(), viewModel.uiState.value)
    }

    @Test
    fun onImageSelected_withInvalidUri_emitsNonBlockingSnackbar() = runTest {
        val viewModel = buildViewModel(
            arSceneController = FakeArSceneController(),
            recordingController = FakeRecordingController()
        )

        viewModel.sideEffects.test {
            viewModel.onImageSelected(Uri.parse("content://invalid/image.png"))
            advanceUntilIdle()

            val sideEffect = awaitItem()
            assertNull(viewModel.uiState.value.blockingError)
            assertTrue(sideEffect is ArSideEffect.ShowSnackbar)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun recordClick_whenArScreenNotResumed_emitsSnackbar_andDoesNotRequestConsent() = runTest {
        val viewModel = buildViewModel(
            arSceneController = FakeArSceneController(),
            recordingController = FakeRecordingController()
        )

        viewModel.sideEffects.test {
            viewModel.onUiEvent(
                event = ArUiEvent.RecordClicked,
                hasRecordAudioPermission = true
            )
            advanceUntilIdle()

            val sideEffect = awaitItem()
            assertTrue(sideEffect is ArSideEffect.ShowSnackbar)
            assertTrue(viewModel.uiState.value.recordingState is RecordingState.Idle)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun backClick_whileRecording_stopsRecording_andNavigatesBack() = runTest {
        val fakeRecordingController = FakeRecordingController()
        val viewModel = buildViewModel(
            arSceneController = FakeArSceneController(),
            recordingController = fakeRecordingController
        )
        viewModel.onArScreenResumed()

        viewModel.sideEffects.test {
            viewModel.onUiEvent(
                event = ArUiEvent.RecordClicked,
                hasRecordAudioPermission = true
            )
            advanceUntilIdle()
            assertTrue(awaitItem() is ArSideEffect.RequestMediaProjectionConsent)

            viewModel.onMediaProjectionConsentResult(
                resultCode = Activity.RESULT_OK,
                data = Intent("projection-consent")
            )
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.recordingState is RecordingState.Active)

            viewModel.onUiEvent(
                event = ArUiEvent.BackClicked,
                hasRecordAudioPermission = true
            )
            advanceUntilIdle()

            assertEquals(1, fakeRecordingController.stopCalls)
            assertTrue(viewModel.uiState.value.recordingState is RecordingState.Idle)
            assertEquals(ArSideEffect.NavigateBack, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun backClick_whileRecording_emitsNavigateBack_onlyAfterStopFinishes() = runTest {
        val fakeRecordingController = FakeRecordingController()
        val stopGate = CompletableDeferred<Unit>()
        fakeRecordingController.stopCompletion = stopGate
        val viewModel = buildViewModel(
            arSceneController = FakeArSceneController(),
            recordingController = fakeRecordingController
        )
        viewModel.onArScreenResumed()

        viewModel.sideEffects.test {
            viewModel.onUiEvent(
                event = ArUiEvent.RecordClicked,
                hasRecordAudioPermission = true
            )
            advanceUntilIdle()
            assertTrue(awaitItem() is ArSideEffect.RequestMediaProjectionConsent)

            viewModel.onMediaProjectionConsentResult(
                resultCode = Activity.RESULT_OK,
                data = Intent("projection-consent")
            )
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.recordingState is RecordingState.Active)

            viewModel.onUiEvent(
                event = ArUiEvent.BackClicked,
                hasRecordAudioPermission = true
            )
            advanceUntilIdle()

            assertEquals(1, fakeRecordingController.stopCalls)
            assertTrue(fakeRecordingController.stopEntered)
            assertTrue(viewModel.uiState.value.recordingState is RecordingState.Finalizing)
            expectNoEvents()

            stopGate.complete(Unit)
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.recordingState is RecordingState.Idle)
            assertEquals(ArSideEffect.NavigateBack, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun recordClick_withoutMicrophonePermission_requestsPermission_thenProjectionConsent() = runTest {
        val viewModel = buildViewModel(
            arSceneController = FakeArSceneController(),
            recordingController = FakeRecordingController()
        )
        viewModel.onArScreenResumed()

        viewModel.sideEffects.test {
            viewModel.onUiEvent(
                event = ArUiEvent.RecordClicked,
                hasRecordAudioPermission = false
            )
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.recordingState is RecordingState.Preparing)
            assertEquals(ArSideEffect.RequestRecordAudioPermission, awaitItem())
            expectNoEvents()

            viewModel.onRecordAudioPermissionResult(granted = true)
            advanceUntilIdle()
            assertTrue(awaitItem() is ArSideEffect.RequestMediaProjectionConsent)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun mediaProjectionConsent_canceled_resetsToIdle_andShowsSnackbar() = runTest {
        val viewModel = buildViewModel(
            arSceneController = FakeArSceneController(),
            recordingController = FakeRecordingController()
        )
        viewModel.onArScreenResumed()

        viewModel.sideEffects.test {
            viewModel.onUiEvent(
                event = ArUiEvent.RecordClicked,
                hasRecordAudioPermission = true
            )
            advanceUntilIdle()
            assertTrue(awaitItem() is ArSideEffect.RequestMediaProjectionConsent)

            viewModel.onMediaProjectionConsentResult(
                resultCode = Activity.RESULT_CANCELED,
                data = null
            )
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.recordingState is RecordingState.Idle)
            val snackbar = awaitItem()
            assertTrue(snackbar is ArSideEffect.ShowSnackbar)
            assertEquals("Screen capture consent was canceled.", (snackbar as ArSideEffect.ShowSnackbar).message)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun windowSizeChange_whileRecording_stopsRecording_andShowsReason() = runTest {
        val fakeRecordingController = FakeRecordingController()
        val viewModel = buildViewModel(
            arSceneController = FakeArSceneController(),
            recordingController = fakeRecordingController
        )
        viewModel.onArScreenResumed()

        viewModel.sideEffects.test {
            viewModel.onUiEvent(
                event = ArUiEvent.RecordClicked,
                hasRecordAudioPermission = true
            )
            advanceUntilIdle()
            assertTrue(awaitItem() is ArSideEffect.RequestMediaProjectionConsent)

            viewModel.onMediaProjectionConsentResult(
                resultCode = Activity.RESULT_OK,
                data = Intent("projection-consent")
            )
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.recordingState is RecordingState.Active)

            viewModel.onMaximumWindowBoundsChanged(Rect(0, 0, 1080, 1920))
            viewModel.onMaximumWindowBoundsChanged(Rect(0, 0, 1080, 2000))
            advanceUntilIdle()

            assertEquals(1, fakeRecordingController.stopCalls)
            assertTrue(viewModel.uiState.value.recordingState is RecordingState.Idle)
            val snackbar = awaitItem()
            assertTrue(snackbar is ArSideEffect.ShowSnackbar)
            assertEquals(
                "Recording stopped due to window size change.",
                (snackbar as ArSideEffect.ShowSnackbar).message
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun stopRecordingClicked_twice_isSafe_andDoesNotDoubleStopWhenIdle() = runTest {
        val fakeRecordingController = FakeRecordingController()
        val viewModel = buildViewModel(
            arSceneController = FakeArSceneController(),
            recordingController = fakeRecordingController
        )
        viewModel.onArScreenResumed()

        viewModel.sideEffects.test {
            viewModel.onUiEvent(
                event = ArUiEvent.RecordClicked,
                hasRecordAudioPermission = true
            )
            advanceUntilIdle()
            assertTrue(awaitItem() is ArSideEffect.RequestMediaProjectionConsent)

            viewModel.onMediaProjectionConsentResult(
                resultCode = Activity.RESULT_OK,
                data = Intent("projection-consent")
            )
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.recordingState is RecordingState.Active)

            viewModel.onUiEvent(
                event = ArUiEvent.StopRecordingClicked,
                hasRecordAudioPermission = true
            )
            advanceUntilIdle()

            assertEquals(1, fakeRecordingController.stopCalls)
            assertTrue(viewModel.uiState.value.recordingState is RecordingState.Idle)
            val snackbar = awaitItem()
            assertTrue(snackbar is ArSideEffect.ShowSnackbar)
            assertEquals("Recording saved", (snackbar as ArSideEffect.ShowSnackbar).message)

            viewModel.onUiEvent(
                event = ArUiEvent.StopRecordingClicked,
                hasRecordAudioPermission = true
            )
            advanceUntilIdle()

            assertEquals(1, fakeRecordingController.stopCalls)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun onArScreenPaused_whileRecording_stopsRecording_andShowsReason() = runTest {
        val fakeRecordingController = FakeRecordingController()
        val viewModel = buildViewModel(
            arSceneController = FakeArSceneController(),
            recordingController = fakeRecordingController
        )
        viewModel.onArScreenResumed()

        viewModel.sideEffects.test {
            viewModel.onUiEvent(
                event = ArUiEvent.RecordClicked,
                hasRecordAudioPermission = true
            )
            advanceUntilIdle()
            assertTrue(awaitItem() is ArSideEffect.RequestMediaProjectionConsent)

            viewModel.onMediaProjectionConsentResult(
                resultCode = Activity.RESULT_OK,
                data = Intent("projection-consent")
            )
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.recordingState is RecordingState.Active)

            viewModel.onArScreenPaused()
            advanceUntilIdle()

            assertEquals(1, fakeRecordingController.stopCalls)
            assertTrue(viewModel.uiState.value.recordingState is RecordingState.Idle)
            val snackbar = awaitItem()
            assertTrue(snackbar is ArSideEffect.ShowSnackbar)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun buildViewModel(
        arSceneController: ArSceneController,
        recordingController: RecordingController
    ): ArViewModel {
        val context = RuntimeEnvironment.getApplication() as Application
        val loadImageUseCase = LoadImageUseCase(
            imageUriReader = AndroidImageUriReader(context.contentResolver),
            imageValidator = ImageValidator(context.contentResolver),
            dispatchers = DefaultDispatcherProvider
        )

        return ArViewModel(
            loadImageUseCase = loadImageUseCase,
            placeImageUseCase = PlaceImageUseCase(),
            replaceImageUseCase = ReplaceImageUseCase(),
            deleteImageUseCase = DeleteImageUseCase(),
            enterRepositionModeUseCase = EnterRepositionModeUseCase(),
            confirmRepositionUseCase = ConfirmRepositionUseCase(),
            requestRecordingUseCase = RequestRecordingUseCase(),
            startRecordingUseCase = StartRecordingUseCase(recordingController),
            stopRecordingUseCase = StopRecordingUseCase(recordingController),
            arAvailabilityChecker = ArAvailabilityChecker(context),
            arSceneController = arSceneController,
            recordingController = recordingController
        )
    }

    private class FakeArSceneController : ArSceneController {
        var clearCalls: Int = 0

        override fun bindScene(engine: Engine, childNodes: MutableList<Node>) = Unit

        override fun prepareSelectedImage(selectedImage: SelectedImage): AppResult<Unit> {
            return AppResult.Success(Unit)
        }

        override fun updatePlacementPreview(hitTestResult: HitTestResult?) = Unit

        override fun hidePlacementPreview() = Unit

        override fun computeCenterHit(
            frame: Frame,
            viewportWidthPx: Int,
            viewportHeightPx: Int
        ): HitTestResult? = null

        override fun placeImage(
            session: Session,
            selectedImage: SelectedImage,
            hitTestResult: HitTestResult
        ): AppResult<PlacedImageState> {
            return AppResult.Success(
                PlacedImageState(
                    anchorId = "anchor",
                    widthMeters = 0.3f,
                    heightMeters = 0.3f,
                    transform = PlacementTransform()
                )
            )
        }

        override fun reposition(
            session: Session,
            hitTestResult: HitTestResult
        ): AppResult<PlacedImageState> {
            return AppResult.Failure(AppError.Unexpected("Unused in test"))
        }

        override fun applyTransform(scale: Float, rotationYDegrees: Float) = Unit

        override fun deleteImage() = Unit

        override fun clear() {
            clearCalls += 1
        }
    }

    private class FakeRecordingController : RecordingController {
        var stopCalls: Int = 0
        var stopEntered: Boolean = false
        var stopCompletion: CompletableDeferred<Unit>? = null
        override var onProjectionStopped: (() -> Unit)? = null

        override fun createConsentIntent(): Intent = Intent("projection-consent")

        override suspend fun startRecording(
            consentResultCode: Int,
            consentData: Intent,
            maximumWindowBounds: Rect
        ): AppResult<Unit> = AppResult.Success(Unit)

        override suspend fun stopRecording(): AppResult<Unit> {
            stopCalls += 1
            stopEntered = true
            stopCompletion?.await()
            return AppResult.Success(Unit)
        }

        override fun release() = Unit
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

package com.example.arspatialpinning.feature.ar

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import app.cash.turbine.test
import com.example.arspatialpinning.common.AppError
import com.example.arspatialpinning.common.AppResult
import com.example.arspatialpinning.common.DefaultDispatcherProvider
import com.example.arspatialpinning.domain.model.DebugRenderStatus
import com.example.arspatialpinning.domain.model.HitTestUiModel
import com.example.arspatialpinning.domain.model.ImageFormat
import com.example.arspatialpinning.domain.model.PlacedImageState
import com.example.arspatialpinning.domain.model.PlacementMode
import com.example.arspatialpinning.domain.model.PlacementTransform
import com.example.arspatialpinning.domain.model.PreparedRenderAsset
import com.example.arspatialpinning.domain.model.PreviewRenderState
import com.example.arspatialpinning.domain.model.RecordingState
import com.example.arspatialpinning.domain.model.RenderAssetState
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
import com.example.arspatialpinning.platform.ar.FrameProcessingResult
import com.example.arspatialpinning.platform.file.ImageUriReader
import com.example.arspatialpinning.platform.file.ImageValidationResult
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
import org.junit.Assert.assertFalse
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
    fun onImageSelected_success_setsRenderAssetReady() = runTest {
        val fakeArController = FakeArSceneController()
        val fakeLoadImageUseCase = FakeLoadImageUseCase(
            result = AppResult.Success(createSelectedImage(selectionRevision = 1L))
        )
        val viewModel = buildViewModel(
            loadImageUseCase = fakeLoadImageUseCase,
            arSceneController = fakeArController,
            recordingController = FakeRecordingController()
        )

        viewModel.onImageSelected(Uri.parse("content://test/success.png"))
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.renderAssetState is RenderAssetState.Ready)
        assertEquals(PreviewRenderState.HiddenNoStableHit, viewModel.uiState.value.previewRenderState)
        assertTrue(viewModel.uiState.value.selectedImage != null)
        assertEquals("asset-1", viewModel.uiState.value.debugRenderStatus.preparedAssetHandleId)
    }

    @Test
    fun onImageSelected_prepareFailure_setsRenderAssetError_andNoSelection() = runTest {
        val fakeArController = FakeArSceneController().apply {
            prepareResult = AppResult.Failure(AppError.PreviewRenderCreationFailed("Preview failed"))
        }
        val fakeLoadImageUseCase = FakeLoadImageUseCase(
            result = AppResult.Success(createSelectedImage(selectionRevision = 2L))
        )
        val viewModel = buildViewModel(
            loadImageUseCase = fakeLoadImageUseCase,
            arSceneController = fakeArController,
            recordingController = FakeRecordingController()
        )

        viewModel.onImageSelected(Uri.parse("content://test/failure.png"))
        advanceUntilIdle()

        val renderState = viewModel.uiState.value.renderAssetState
        assertTrue(renderState is RenderAssetState.Error)
        assertTrue(viewModel.uiState.value.previewRenderState is PreviewRenderState.Error)
        assertNull(viewModel.uiState.value.selectedImage)
    }

    @Test
    fun onImageSelected_metadataOnlySuccessAttempted_setsExplicitErrorState() = runTest {
        val fakeArController = FakeArSceneController().apply {
            prepareResult = AppResult.Failure(AppError.MetadataOnlySuccessAttempted())
        }
        val viewModel = buildViewModel(
            loadImageUseCase = FakeLoadImageUseCase(AppResult.Success(createSelectedImage(selectionRevision = 3L))),
            arSceneController = fakeArController,
            recordingController = FakeRecordingController()
        )

        viewModel.onImageSelected(Uri.parse("content://test/metadata-only.png"))
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.renderAssetState is RenderAssetState.Error)
        assertTrue(viewModel.uiState.value.previewRenderState is PreviewRenderState.Error)
    }

    @Test
    fun transformGesture_clampsScale_andNormalizesRotation() = runTest {
        val fakeArController = FakeArSceneController()
        val viewModel = buildViewModel(
            loadImageUseCase = FakeLoadImageUseCase(AppResult.Success(createSelectedImage(selectionRevision = 4L))),
            arSceneController = fakeArController,
            recordingController = FakeRecordingController()
        )

        viewModel.setUiStateForTest(
            ArUiState(
                selectedImage = createSelectedImage(selectionRevision = 4L),
                placedImage = PlacedImageState(
                    anchorId = "anchor",
                    widthMeters = 0.3f,
                    heightMeters = 0.3f,
                    transform = PlacementTransform(scale = 1f, rotationYDegrees = 350f)
                ),
                placementMode = PlacementMode.Placed
            )
        )

        viewModel.onTransformGesture(scaleFactor = 10f, rotationDegreesDelta = 30f)
        val firstTransform = viewModel.uiState.value.placedImage?.transform
        assertEquals(4.0f, firstTransform?.scale ?: 0f, 0.0001f)
        assertEquals(20f, firstTransform?.rotationYDegrees ?: 0f, 0.0001f)
        assertEquals(4.0f, fakeArController.lastAppliedScale ?: 0f, 0.0001f)
        assertEquals(20f, fakeArController.lastAppliedRotation ?: 0f, 0.0001f)

        viewModel.onTransformGesture(scaleFactor = 0.01f, rotationDegreesDelta = -50f)
        val secondTransform = viewModel.uiState.value.placedImage?.transform
        assertEquals(0.25f, secondTransform?.scale ?: 0f, 0.0001f)
        assertEquals(330f, secondTransform?.rotationYDegrees ?: 0f, 0.0001f)
    }

    @Test
    fun recordClick_withoutMicrophonePermission_requestsPermission() = runTest {
        val viewModel = buildViewModel(
            loadImageUseCase = FakeLoadImageUseCase(AppResult.Success(createSelectedImage(selectionRevision = 5L))),
            arSceneController = FakeArSceneController(),
            recordingController = FakeRecordingController()
        )
        viewModel.onArScreenResumed()
        viewModel.setUiStateForTest(ArUiState(isArReady = true))

        viewModel.sideEffects.test {
            viewModel.onUiEvent(
                event = ArUiEvent.RecordClicked,
                hasRecordAudioPermission = false
            )
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.recordingState is RecordingState.Preparing)
            assertEquals(ArSideEffect.RequestRecordAudioPermission, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun mediaProjectionDenied_resetsIdle_andShowsSnackbar() = runTest {
        val viewModel = buildViewModel(
            loadImageUseCase = FakeLoadImageUseCase(AppResult.Success(createSelectedImage(selectionRevision = 6L))),
            arSceneController = FakeArSceneController(),
            recordingController = FakeRecordingController()
        )
        viewModel.onArScreenResumed()
        viewModel.setUiStateForTest(ArUiState(isArReady = true))

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
            val effect = awaitItem()
            assertTrue(effect is ArSideEffect.ShowSnackbar)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun stopRecording_isIdempotentWhileFinalizing() = runTest {
        val fakeRecordingController = FakeRecordingController()
        val stopGate = CompletableDeferred<Unit>()
        fakeRecordingController.stopCompletion = stopGate
        val viewModel = buildViewModel(
            loadImageUseCase = FakeLoadImageUseCase(AppResult.Success(createSelectedImage(selectionRevision = 7L))),
            arSceneController = FakeArSceneController(),
            recordingController = fakeRecordingController
        )
        viewModel.onArScreenResumed()
        viewModel.setUiStateForTest(ArUiState(isArReady = true))

        viewModel.sideEffects.test {
            viewModel.onUiEvent(ArUiEvent.RecordClicked, hasRecordAudioPermission = true)
            advanceUntilIdle()
            assertTrue(awaitItem() is ArSideEffect.RequestMediaProjectionConsent)

            viewModel.onMediaProjectionConsentResult(
                resultCode = Activity.RESULT_OK,
                data = Intent("projection-consent")
            )
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.recordingState is RecordingState.Active)

            viewModel.onUiEvent(ArUiEvent.StopRecordingClicked, hasRecordAudioPermission = true)
            viewModel.onUiEvent(ArUiEvent.StopRecordingClicked, hasRecordAudioPermission = true)
            advanceUntilIdle()

            assertEquals(1, fakeRecordingController.stopCalls)
            assertTrue(viewModel.uiState.value.recordingState is RecordingState.Finalizing)
            stopGate.complete(Unit)
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.recordingState is RecordingState.Idle)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun onRouteExit_clearsState_andReleasesControllers() = runTest {
        val fakeArController = FakeArSceneController()
        val fakeRecordingController = FakeRecordingController()
        val viewModel = buildViewModel(
            loadImageUseCase = FakeLoadImageUseCase(AppResult.Success(createSelectedImage(selectionRevision = 8L))),
            arSceneController = fakeArController,
            recordingController = fakeRecordingController
        )

        viewModel.onRouteExit()
        advanceUntilIdle()

        assertEquals(1, fakeArController.releaseCalls)
        assertEquals(1, fakeRecordingController.stopCalls)
        assertEquals(ArUiState(), viewModel.uiState.value)
    }

    @Test
    fun onCameraPermissionStateObserved_revoked_disablesArReadiness() = runTest {
        val viewModel = buildViewModel(
            loadImageUseCase = FakeLoadImageUseCase(AppResult.Success(createSelectedImage(selectionRevision = 9L))),
            arSceneController = FakeArSceneController(),
            recordingController = FakeRecordingController()
        )

        viewModel.setUiStateForTest(
            ArUiState(
                hasCameraPermission = true,
                isArReady = true
            )
        )

        viewModel.onCameraPermissionStateObserved(granted = false)

        assertFalse(viewModel.uiState.value.hasCameraPermission)
        assertFalse(viewModel.uiState.value.isArReady)
    }

    private fun buildViewModel(
        loadImageUseCase: LoadImageUseCase,
        arSceneController: ArSceneController,
        recordingController: RecordingController
    ): ArViewModel {
        val context = RuntimeEnvironment.getApplication() as Application

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

    private fun createSelectedImage(selectionRevision: Long): SelectedImage {
        return SelectedImage(
            uri = Uri.parse("content://test/selected.png"),
            displayName = "selected.png",
            mimeType = "image/png",
            widthPx = 8,
            heightPx = 8,
            format = ImageFormat.Png,
            selectionRevision = selectionRevision
        )
    }

    private class FakeArSceneController : ArSceneController {
        var releaseCalls: Int = 0
        var prepareResult: AppResult<PreparedRenderAsset> = AppResult.Success(
            PreparedRenderAsset(
                assetHandleId = "asset-1",
                widthPx = 300,
                heightPx = 300,
                aspectRatio = 1f,
                selectionRevision = 1L
            )
        )
        var lastAppliedScale: Float? = null
        var lastAppliedRotation: Float? = null
        private var debugStatus = DebugRenderStatus()

        override fun bindScene(engine: Engine, childNodes: MutableList<Node>) = Unit

        override fun resume() = Unit

        override fun pause() = Unit

        override fun prepareSelectedImage(selectedImage: SelectedImage): AppResult<PreparedRenderAsset> {
            val prepared = prepareResult
            if (prepared is AppResult.Success) {
                debugStatus = DebugRenderStatus(
                    previewNodeExists = true,
                    previewNodeAttached = true,
                    previewNodeVisible = false,
                    preparedAssetHandleId = prepared.value.assetHandleId,
                    previewAssetHandleId = prepared.value.assetHandleId
                )
            }
            return prepared
        }

        override fun processFrame(
            frame: Frame,
            viewportWidthPx: Int,
            viewportHeightPx: Int,
            placementMode: PlacementMode
        ): FrameProcessingResult {
            return FrameProcessingResult(
                isCameraTracking = true,
                hitUiModel = HitTestUiModel(),
                previewRenderState = PreviewRenderState.HiddenNoStableHit,
                debugRenderStatus = debugStatus
            )
        }

        override fun placePreparedImage(
            session: Session,
            preparedAsset: PreparedRenderAsset
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

        override fun enterRepositionMode() = Unit

        override fun confirmReposition(session: Session): AppResult<PlacedImageState> {
            return AppResult.Failure(AppError.Unexpected("Unused"))
        }

        override fun cancelReposition() = Unit

        override fun applyTransform(scale: Float, rotationYDegrees: Float) {
            lastAppliedScale = scale
            lastAppliedRotation = rotationYDegrees
        }

        override fun deleteImage() = Unit

        override fun currentDebugRenderStatus(): DebugRenderStatus = debugStatus

        override fun release() {
            releaseCalls += 1
        }
    }

    private class FakeLoadImageUseCase(
        private val result: AppResult<SelectedImage>
    ) : LoadImageUseCase(
        imageUriReader = object : ImageUriReader {
            override fun readMetadata(
                uri: Uri,
                validation: ImageValidationResult,
                selectionRevision: Long
            ): AppResult<SelectedImage> = result

            override fun decodeBitmap(selectedImage: SelectedImage): AppResult<Bitmap> {
                return AppResult.Success(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888))
            }
        },
        imageValidator = ImageValidator(
            (RuntimeEnvironment.getApplication() as Application).contentResolver
        ),
        dispatchers = DefaultDispatcherProvider
    ) {
        override suspend fun invoke(uri: Uri): AppResult<SelectedImage> = result
    }

    private class FakeRecordingController : RecordingController {
        var stopCalls: Int = 0
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

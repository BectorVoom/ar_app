package com.example.arspatialpinning.feature.ar

import android.app.Application
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
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
import com.example.arspatialpinning.domain.model.RecordedVideoArtifact
import com.example.arspatialpinning.domain.model.RecordingState
import com.example.arspatialpinning.domain.model.RenderAssetState
import com.example.arspatialpinning.domain.model.SelectedImage
import com.example.arspatialpinning.domain.usecase.ConfirmRepositionUseCase
import com.example.arspatialpinning.domain.usecase.DeleteImageUseCase
import com.example.arspatialpinning.domain.usecase.DownloadRecordingUseCase
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
import com.example.arspatialpinning.platform.media.RecordingExporter
import com.example.arspatialpinning.platform.media.SharedRecordingStateHolder
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.android.filament.Engine
import io.github.sceneview.node.Node
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
    fun onImageSelected_success_setsRenderAssetReady() = runTest {
        val fakeArController = FakeArSceneController()
        val fakeLoadImageUseCase = FakeLoadImageUseCase(
            result = AppResult.Success(createSelectedImage(selectionRevision = 1L))
        )
        val viewModel = buildViewModel(
            loadImageUseCase = fakeLoadImageUseCase,
            arSceneController = fakeArController
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
        val viewModel = buildViewModel(
            loadImageUseCase = FakeLoadImageUseCase(AppResult.Success(createSelectedImage(selectionRevision = 2L))),
            arSceneController = fakeArController
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
            arSceneController = fakeArController
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
            arSceneController = fakeArController
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
    fun onRouteExit_clearsArState_butKeepsSharedRecordingState() = runTest {
        val fakeArController = FakeArSceneController()
        val fakeRecordingController = FakeRecordingController().apply {
            stopResult = AppResult.Success(
                RecordedVideoArtifact(
                    sourceUri = Uri.parse("content://recordings/validated.mp4"),
                    displayName = "validated.mp4"
                )
            )
        }
        val sharedHolder = createSharedRecordingStateHolder(
            recordingController = fakeRecordingController,
            recordingExporter = FakeRecordingExporter()
        )
        sharedHolder.onAppResumed()
        sharedHolder.onRecordAudioPermissionStateObserved(true)
        sharedHolder.onMediaProjectionConsentResult(Activity.RESULT_OK, Intent("projection-consent"))
        advanceUntilIdle()
        sharedHolder.onStopRecordClick(showSavedMessage = false)
        advanceUntilIdle()

        val viewModel = buildViewModel(
            loadImageUseCase = FakeLoadImageUseCase(AppResult.Success(createSelectedImage(selectionRevision = 10L))),
            arSceneController = fakeArController,
            sharedRecordingStateHolder = sharedHolder
        )
        val recordingArtifact = sharedHolder.uiState.value.lastCompletedRecording
        viewModel.setUiStateForTest(
            ArUiState(
                hasCameraPermission = true,
                isArReady = true,
                selectedImage = createSelectedImage(selectionRevision = 10L),
                renderAssetState = RenderAssetState.Error("x"),
                previewRenderState = PreviewRenderState.Error("x")
            )
        )

        viewModel.onRouteExit()
        advanceUntilIdle()

        assertEquals(1, fakeArController.releaseCalls)
        assertTrue(viewModel.uiState.value.recordingState is RecordingState.Idle)
        assertEquals(recordingArtifact, viewModel.uiState.value.lastCompletedRecording)
        assertEquals(RenderAssetState.None, viewModel.uiState.value.renderAssetState)
        assertEquals(PreviewRenderState.HiddenNoSelection, viewModel.uiState.value.previewRenderState)
        assertNull(viewModel.uiState.value.selectedImage)
        sharedHolder.release()
    }

    @Test
    fun sharedRecordingPermission_isMirroredIntoArUiState() = runTest {
        val fakeRecordingController = FakeRecordingController()
        val sharedHolder = createSharedRecordingStateHolder(
            recordingController = fakeRecordingController,
            recordingExporter = FakeRecordingExporter()
        )
        val viewModel = buildViewModel(
            loadImageUseCase = FakeLoadImageUseCase(AppResult.Success(createSelectedImage(selectionRevision = 11L))),
            arSceneController = FakeArSceneController(),
            sharedRecordingStateHolder = sharedHolder
        )

        sharedHolder.onRecordAudioPermissionStateObserved(true)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.hasRecordAudioPermission)
        sharedHolder.release()
    }

    private fun buildViewModel(
        loadImageUseCase: LoadImageUseCase,
        arSceneController: ArSceneController,
        sharedRecordingStateHolder: SharedRecordingStateHolder = createSharedRecordingStateHolder(
            recordingController = FakeRecordingController(),
            recordingExporter = FakeRecordingExporter()
        )
    ): ArViewModel {
        val context = RuntimeEnvironment.getApplication() as Application

        return ArViewModel(
            loadImageUseCase = loadImageUseCase,
            placeImageUseCase = PlaceImageUseCase(),
            replaceImageUseCase = ReplaceImageUseCase(),
            deleteImageUseCase = DeleteImageUseCase(),
            enterRepositionModeUseCase = EnterRepositionModeUseCase(),
            confirmRepositionUseCase = ConfirmRepositionUseCase(),
            arAvailabilityChecker = ArAvailabilityChecker(context),
            arSceneController = arSceneController,
            sharedRecordingStateHolder = sharedRecordingStateHolder
        )
    }

    private fun createSharedRecordingStateHolder(
        recordingController: RecordingController,
        recordingExporter: RecordingExporter
    ): SharedRecordingStateHolder {
        return SharedRecordingStateHolder(
            requestRecordingUseCase = RequestRecordingUseCase(),
            startRecordingUseCase = StartRecordingUseCase(recordingController),
            stopRecordingUseCase = StopRecordingUseCase(recordingController),
            downloadRecordingUseCase = DownloadRecordingUseCase(recordingExporter),
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
        var stopResult: AppResult<RecordedVideoArtifact?> = AppResult.Success(null)
        override var onProjectionStopped: (() -> Unit)? = null

        override fun createConsentIntent(): AppResult<Intent> = AppResult.Success(Intent("projection-consent"))

        override suspend fun startRecording(
            consentResultCode: Int,
            consentData: Intent,
            maximumWindowBounds: Rect
        ): AppResult<Unit> = AppResult.Success(Unit)

        override suspend fun stopRecording(): AppResult<RecordedVideoArtifact?> = stopResult

        override fun release() = Unit
    }

    private class FakeRecordingExporter : RecordingExporter {
        override suspend fun exportRecording(sourceUri: Uri, destinationUri: Uri): AppResult<Unit> {
            return AppResult.Success(Unit)
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

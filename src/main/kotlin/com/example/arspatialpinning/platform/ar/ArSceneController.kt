package com.example.arspatialpinning.platform.ar

import com.example.arspatialpinning.common.AppResult
import com.example.arspatialpinning.domain.model.DebugRenderStatus
import com.example.arspatialpinning.domain.model.HitTestUiModel
import com.example.arspatialpinning.domain.model.PlacedImageState
import com.example.arspatialpinning.domain.model.PlacementMode
import com.example.arspatialpinning.domain.model.PreparedRenderAsset
import com.example.arspatialpinning.domain.model.PreviewRenderState
import com.example.arspatialpinning.domain.model.SelectedImage
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.android.filament.Engine
import io.github.sceneview.node.Node

data class FrameProcessingResult(
    val isCameraTracking: Boolean,
    val hitUiModel: HitTestUiModel,
    val previewRenderState: PreviewRenderState,
    val debugRenderStatus: DebugRenderStatus
)

interface ArSceneController {
    fun bindScene(
        engine: Engine,
        childNodes: MutableList<Node>
    )

    fun resume()

    fun pause()

    fun prepareSelectedImage(selectedImage: SelectedImage): AppResult<PreparedRenderAsset>

    fun processFrame(
        frame: Frame,
        viewportWidthPx: Int,
        viewportHeightPx: Int,
        placementMode: PlacementMode
    ): FrameProcessingResult

    fun placePreparedImage(
        session: Session,
        preparedAsset: PreparedRenderAsset
    ): AppResult<PlacedImageState>

    fun enterRepositionMode()

    fun confirmReposition(session: Session): AppResult<PlacedImageState>

    fun cancelReposition()

    fun applyTransform(scale: Float, rotationYDegrees: Float)

    fun deleteImage()

    fun currentDebugRenderStatus(): DebugRenderStatus

    fun release()
}

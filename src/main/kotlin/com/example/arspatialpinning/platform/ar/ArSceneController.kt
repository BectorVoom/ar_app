package com.example.arspatialpinning.platform.ar

import com.example.arspatialpinning.common.AppResult
import com.example.arspatialpinning.domain.model.PlacedImageState
import com.example.arspatialpinning.domain.model.SelectedImage
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.android.filament.Engine
import io.github.sceneview.node.Node

interface ArSceneController {
    fun bindScene(
        engine: Engine,
        childNodes: MutableList<Node>
    )

    fun prepareSelectedImage(selectedImage: SelectedImage): AppResult<Unit>

    fun updatePlacementPreview(hitTestResult: HitTestResult?)

    fun hidePlacementPreview()

    fun computeCenterHit(
        frame: Frame,
        viewportWidthPx: Int,
        viewportHeightPx: Int
    ): HitTestResult?

    fun placeImage(
        session: Session,
        selectedImage: SelectedImage,
        hitTestResult: HitTestResult
    ): AppResult<PlacedImageState>

    fun reposition(
        session: Session,
        hitTestResult: HitTestResult
    ): AppResult<PlacedImageState>

    fun applyTransform(scale: Float, rotationYDegrees: Float)

    fun deleteImage()

    fun clear()
}

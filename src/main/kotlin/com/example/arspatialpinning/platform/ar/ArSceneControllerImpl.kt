package com.example.arspatialpinning.platform.ar

import android.content.Context
import com.example.arspatialpinning.common.AppError
import com.example.arspatialpinning.common.AppResult
import com.example.arspatialpinning.common.Logger
import com.example.arspatialpinning.domain.model.PlacedImageState
import com.example.arspatialpinning.domain.model.PlacementTransform
import com.example.arspatialpinning.domain.model.SelectedImage
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.android.filament.Engine
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.math.Size
import io.github.sceneview.node.ImageNode
import io.github.sceneview.node.Node
import kotlin.math.atan2

class ArSceneControllerImpl(
    private val context: Context,
    private val logger: Logger,
    private val textureLoader: TextureLoader = TextureLoader()
) : ArSceneController {

    private var engine: Engine? = null
    private var childNodes: MutableList<Node>? = null
    private var materialLoader: MaterialLoader? = null

    private var selectedImage: SelectedImage? = null
    private var preparedImageNode: ImageNode? = null
    private var placedAnchorNode: AnchorNode? = null
    private var placedState: PlacedImageState? = null

    override fun bindScene(
        engine: Engine,
        childNodes: MutableList<Node>
    ) {
        this.engine = engine
        this.childNodes = childNodes
        if (materialLoader == null) {
            materialLoader = MaterialLoader(engine, context)
        }
    }

    override fun prepareSelectedImage(selectedImage: SelectedImage): AppResult<Unit> {
        val sceneMaterialLoader = materialLoader
        val sceneNodes = childNodes
        if (sceneMaterialLoader == null || sceneNodes == null) {
            return AppResult.Failure(AppError.Unexpected("AR scene is not initialized."))
        }

        return try {
            val imageNode = ImageNode(
                materialLoader = sceneMaterialLoader,
                bitmap = textureLoader.bitmapFrom(selectedImage),
                size = imageSizeFor(selectedImage),
                center = Position(x = 0f, y = 0f, z = 0f),
                builderApply = {
                    culling(false)
                }
            ).apply {
                rotation = Rotation(x = 0f, y = 0f, z = 0f)
                scale = Scale(1f)
                isShadowCaster = false
                isShadowReceiver = false
                isVisible = false
            }

            releaseSelectedImageResources()
            sceneNodes += imageNode
            this.selectedImage = selectedImage
            this.preparedImageNode = imageNode
            this.placedState = null
            AppResult.Success(Unit)
        } catch (t: Throwable) {
            logger.e(TAG, "Failed to prepare image.", t)
            AppResult.Failure(AppError.Unexpected("Failed to prepare the selected image."))
        }
    }

    override fun updatePlacementPreview(hitTestResult: HitTestResult?) {
        val imageNode = preparedImageNode ?: return
        if (placedAnchorNode != null) {
            return
        }

        if (hitTestResult == null) {
            imageNode.isVisible = false
            return
        }

        attachPreparedNodeToSceneRoot()
        val translation = hitTestResult.hitPose.translation
        val rotationY = facingUserRotationY(hitTestResult.hitPose, hitTestResult.cameraPose)

        imageNode.position = Position(
            x = translation[0],
            y = translation[1],
            z = translation[2]
        )
        imageNode.rotation = Rotation(x = 0f, y = rotationY, z = 0f)
        imageNode.scale = Scale(1f)
        imageNode.isVisible = true
    }

    override fun hidePlacementPreview() {
        if (placedAnchorNode == null) {
            preparedImageNode?.isVisible = false
        }
    }

    override fun computeCenterHit(
        frame: Frame,
        viewportWidthPx: Int,
        viewportHeightPx: Int
    ): HitTestResult? {
        val centerX = viewportWidthPx * 0.5f
        val centerY = viewportHeightPx * 0.5f

        for (hit in frame.hitTest(centerX, centerY)) {
            val trackable = hit.trackable
            if (trackable is Plane &&
                (trackable.type == Plane.Type.HORIZONTAL_UPWARD_FACING ||
                    trackable.type == Plane.Type.VERTICAL) &&
                trackable.isPoseInPolygon(hit.hitPose)
            ) {
                return HitTestResult(
                    hitPose = hit.hitPose,
                    cameraPose = frame.camera.pose,
                    trackableId = System.identityHashCode(trackable).toString()
                )
            }
        }

        return null
    }

    override fun placeImage(
        session: Session,
        selectedImage: SelectedImage,
        hitTestResult: HitTestResult
    ): AppResult<PlacedImageState> {
        val sceneEngine = engine
        val sceneNodes = childNodes
        val imageNode = preparedImageNode
        val prepared = this.selectedImage
        if (sceneEngine == null || sceneNodes == null || imageNode == null || prepared == null) {
            return AppResult.Failure(AppError.Unexpected("Selected image is not prepared."))
        }
        if (prepared.uri != selectedImage.uri) {
            return AppResult.Failure(AppError.Unexpected("Prepared image does not match the selected image."))
        }

        deleteImage()

        return try {
            val anchor = session.createAnchor(uprightPose(hitTestResult.hitPose))
            val anchorNode = AnchorNode(
                engine = sceneEngine,
                anchor = anchor
            )

            if (sceneNodes.contains(imageNode)) {
                sceneNodes.remove(imageNode)
            }
            imageNode.parent?.removeChildNode(imageNode)
            val initialRotation = imageNode.rotation.y
            imageNode.isVisible = true

            anchorNode.addChildNode(imageNode)
            sceneNodes += anchorNode

            this.placedAnchorNode = anchorNode
            this.placedState = PlacedImageState(
                anchorId = anchor.hashCode().toString(),
                widthMeters = PLACE_IMAGE_DEFAULTS.defaultHeightMeters * selectedImage.aspectRatio,
                heightMeters = PLACE_IMAGE_DEFAULTS.defaultHeightMeters,
                transform = PlacementTransform(scale = imageNode.scale.x, rotationYDegrees = initialRotation)
            )

            AppResult.Success(requireNotNull(placedState))
        } catch (t: Throwable) {
            logger.e(TAG, "Failed to place image.", t)
            AppResult.Failure(AppError.Unexpected("Failed to place image in AR scene."))
        }
    }

    override fun reposition(
        session: Session,
        hitTestResult: HitTestResult
    ): AppResult<PlacedImageState> {
        val currentImage = selectedImage
        val currentState = placedState
        if (currentImage == null || currentState == null) {
            return AppResult.Failure(AppError.Unexpected("No placed image to reposition."))
        }

        return placeImage(session, currentImage, hitTestResult).let { result ->
            when (result) {
                is AppResult.Success -> {
                    val transformed = result.value.copy(transform = currentState.transform)
                    applyTransform(
                        scale = currentState.transform.scale,
                        rotationYDegrees = currentState.transform.rotationYDegrees
                    )
                    placedState = transformed
                    AppResult.Success(transformed)
                }

                is AppResult.Failure -> result
            }
        }
    }

    override fun applyTransform(scale: Float, rotationYDegrees: Float) {
        val node = preparedImageNode ?: return
        if (placedAnchorNode == null) {
            return
        }

        val clampedScale = scale.coerceIn(MIN_SCALE, MAX_SCALE)
        node.scale = Scale(clampedScale)
        node.rotation = Rotation(x = 0f, y = rotationYDegrees, z = 0f)
        placedState = placedState?.copy(
            transform = PlacementTransform(
                scale = clampedScale,
                rotationYDegrees = rotationYDegrees
            )
        )
    }

    override fun deleteImage() {
        val anchorNode = placedAnchorNode ?: return
        val imageNode = preparedImageNode
        val sceneNodes = childNodes

        if (imageNode != null && imageNode.parent == anchorNode) {
            anchorNode.removeChildNode(imageNode)
            attachPreparedNodeToSceneRoot()
            imageNode.isVisible = false
        }

        if (sceneNodes != null) {
            sceneNodes.remove(anchorNode)
        }
        anchorNode.detachAnchor()
        anchorNode.destroy()
        placedAnchorNode = null
        placedState = null
    }

    override fun clear() {
        releaseSelectedImageResources()
        materialLoader?.destroy()
        materialLoader = null
        engine = null
        childNodes = null
    }

    private fun releaseSelectedImageResources() {
        deleteImage()

        val imageNode = preparedImageNode
        if (imageNode != null) {
            childNodes?.remove(imageNode)
            imageNode.parent?.removeChildNode(imageNode)
            imageNode.destroy()
        }
        preparedImageNode = null
        selectedImage = null
        placedState = null
    }

    private fun attachPreparedNodeToSceneRoot() {
        val imageNode = preparedImageNode ?: return
        val sceneNodes = childNodes ?: return
        imageNode.parent?.removeChildNode(imageNode)
        if (!sceneNodes.contains(imageNode)) {
            sceneNodes += imageNode
        }
    }

    private fun imageSizeFor(selectedImage: SelectedImage): Size {
        return Size(
            x = PLACE_IMAGE_DEFAULTS.defaultHeightMeters * selectedImage.aspectRatio,
            y = PLACE_IMAGE_DEFAULTS.defaultHeightMeters,
            z = 0f
        )
    }

    private fun uprightPose(hitPose: Pose): Pose {
        val t = hitPose.translation
        return Pose.makeTranslation(t[0], t[1], t[2])
    }

    private fun facingUserRotationY(hitPose: Pose, cameraPose: Pose): Float {
        val hit = hitPose.translation
        val camera = cameraPose.translation
        val dx = camera[0] - hit[0]
        val dz = camera[2] - hit[2]
        return Math.toDegrees(atan2(dx, dz).toDouble()).toFloat()
    }

    private companion object {
        private const val TAG = "ArSceneController"
        private const val MIN_SCALE = 0.25f
        private const val MAX_SCALE = 4.0f
    }
}

private object PLACE_IMAGE_DEFAULTS {
    const val defaultHeightMeters = 0.30f
}

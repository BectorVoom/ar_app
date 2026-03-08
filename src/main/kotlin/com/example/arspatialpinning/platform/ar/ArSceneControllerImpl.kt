package com.example.arspatialpinning.platform.ar

import android.content.Context
import android.graphics.Bitmap
import com.example.arspatialpinning.common.AppError
import com.example.arspatialpinning.common.AppResult
import com.example.arspatialpinning.common.Logger
import com.example.arspatialpinning.domain.model.DebugRenderStatus
import com.example.arspatialpinning.domain.model.HitTestUiModel
import com.example.arspatialpinning.domain.model.PlacedImageState
import com.example.arspatialpinning.domain.model.PlacementMode
import com.example.arspatialpinning.domain.model.PlacementTransform
import com.example.arspatialpinning.domain.model.PreparedRenderAsset
import com.example.arspatialpinning.domain.model.PreviewRenderState
import com.example.arspatialpinning.domain.model.SelectedImage
import com.example.arspatialpinning.platform.file.ImageUriReader
import com.example.arspatialpinning.platform.file.UriReadPermissionGuard
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.android.filament.Engine
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.math.Size
import io.github.sceneview.node.ImageNode
import io.github.sceneview.node.Node
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * ARCore may return different Java wrapper objects for the same underlying trackable.
 * Stabilization must use logical trackable identity, not wrapper instance identity.
 */
internal fun stableTrackableId(trackable: Any): String {
    if (trackable !is Plane) {
        return trackable.hashCode().toString()
    }

    var canonicalPlane: Plane = trackable
    while (true) {
        val parent = canonicalPlane.subsumedBy ?: break
        if (parent == canonicalPlane) {
            break
        }
        canonicalPlane = parent
    }
    return canonicalPlane.hashCode().toString()
}

internal fun isApproxTranslation(
    first: FloatArray,
    second: FloatArray,
    epsilonMeters: Float
): Boolean {
    if (first.size < 3 || second.size < 3) {
        return false
    }
    val dx = first[0] - second[0]
    val dy = first[1] - second[1]
    val dz = first[2] - second[2]
    val distance = sqrt(dx * dx + dy * dy + dz * dz)
    return distance <= epsilonMeters
}

class ArSceneControllerImpl(
    private val context: Context,
    private val logger: Logger,
    private val imageUriReader: ImageUriReader,
    private val uriReadPermissionGuard: UriReadPermissionGuard
) : ArSceneController {

    private var engine: Engine? = null
    private var childNodes: MutableList<Node>? = null
    private var materialLoader: MaterialLoader? = null

    private val preparedBundleRegistry = LinkedHashMap<String, PreparedRenderBundle>()
    private var activePreparedAsset: PreparedRenderAsset? = null
    private var placedAssetHandleId: String? = null

    private var placedAnchorNode: AnchorNode? = null
    private var placedImageNode: ImageNode? = null
    private var placedState: PlacedImageState? = null

    private var candidateTrackableId: String? = null
    private var candidatePose: Pose? = null
    private var stabilizationFrames: Int = 0
    private var stableHit: HitTestResult? = null

    private val debugStatusTracker = DebugRenderStatusTracker()

    override fun bindScene(engine: Engine, childNodes: MutableList<Node>) {
        this.engine = engine
        this.childNodes = childNodes
        if (materialLoader == null) {
            materialLoader = MaterialLoader(engine, context)
        }
    }

    override fun resume() = Unit

    override fun pause() {
        hideActivePreviewNode()
    }

    override fun prepareSelectedImage(selectedImage: SelectedImage): AppResult<PreparedRenderAsset> {
        val sceneMaterialLoader = materialLoader
        val sceneNodes = childNodes
        if (sceneMaterialLoader == null || sceneNodes == null) {
            return AppResult.Failure(AppError.Unexpected("AR scene is not initialized."))
        }

        return try {
            invalidatePreparedRegistry()

            val decodeResult = uriReadPermissionGuard.withReadPermission(selectedImage.uri) {
                imageUriReader.decodeBitmap(selectedImage)
            }
            val decodedBitmap = when (decodeResult) {
                is AppResult.Success -> decodeResult.value
                is AppResult.Failure -> return decodeResult
            }
            if (decodedBitmap.width <= 0 || decodedBitmap.height <= 0) {
                decodedBitmap.safeRecycle()
                return AppResult.Failure(AppError.DimensionOnlySuccessAttempted())
            }

            val assetHandleId = UUID.randomUUID().toString()
            val asset = PreparedRenderAsset(
                assetHandleId = assetHandleId,
                widthPx = decodedBitmap.width,
                heightPx = decodedBitmap.height,
                aspectRatio = decodedBitmap.width.toFloat() / decodedBitmap.height.toFloat(),
                selectionRevision = selectedImage.selectionRevision
            )
            val previewNode = buildImageNode(
                bitmap = decodedBitmap,
                sceneMaterialLoader = sceneMaterialLoader,
                aspectRatio = asset.aspectRatio
            ).apply {
                isVisible = false
            }
            sceneNodes += previewNode

            val bundle = PreparedRenderBundle(
                selectedImage = selectedImage,
                preparedAsset = asset,
                bitmap = decodedBitmap,
                node = previewNode,
                role = NodeRole.Preview
            )
            preparedBundleRegistry[assetHandleId] = bundle
            activePreparedAsset = asset
            placedAssetHandleId = null
            placedState = null

            debugStatusTracker.onPreparedAssetRegistered(assetHandleId)
            debugStatusTracker.onPreviewNodePrepared(
                assetHandleId = assetHandleId,
                attached = isPreviewAttached(previewNode)
            )

            val registered = preparedBundleRegistry[assetHandleId] != null
            if (!registered) {
                return AppResult.Failure(AppError.MetadataOnlySuccessAttempted())
            }

            AppResult.Success(asset)
        } catch (t: Throwable) {
            logger.e(TAG, "Failed to prepare image render asset.", t)
            invalidatePreparedRegistry()
            AppResult.Failure(AppError.PreviewRenderCreationFailed("Failed to prepare selected image render asset."))
        }
    }

    override fun processFrame(
        frame: Frame,
        viewportWidthPx: Int,
        viewportHeightPx: Int,
        placementMode: PlacementMode
    ): FrameProcessingResult {
        val isTracking = frame.camera.trackingState == TrackingState.TRACKING
        if (!isTracking) {
            clearStabilization()
            hideActivePreviewNode()
            val noTrackingState = if (activePreparedAsset == null) {
                PreviewRenderState.HiddenNoSelection
            } else {
                PreviewRenderState.HiddenNoTracking
            }
            return FrameProcessingResult(
                isCameraTracking = false,
                hitUiModel = HitTestUiModel(),
                previewRenderState = noTrackingState,
                debugRenderStatus = currentDebugRenderStatus()
            )
        }

        val hit = computeCenterHit(frame, viewportWidthPx, viewportHeightPx)
        val hitUiModel = updateStabilization(hit)
        val readyAsset = activePreparedAsset
            ?: return FrameProcessingResult(
                isCameraTracking = true,
                hitUiModel = hitUiModel,
                previewRenderState = PreviewRenderState.HiddenNoSelection,
                debugRenderStatus = currentDebugRenderStatus()
            )

        val bundle = preparedBundleRegistry[readyAsset.assetHandleId]
            ?: return FrameProcessingResult(
                isCameraTracking = true,
                hitUiModel = hitUiModel,
                previewRenderState = PreviewRenderState.Error(AppError.StaleOrMissingPreparedAssetHandle().message),
                debugRenderStatus = currentDebugRenderStatus()
            )

        if (bundle.role != NodeRole.Preview) {
            return FrameProcessingResult(
                isCameraTracking = true,
                hitUiModel = hitUiModel,
                previewRenderState = PreviewRenderState.HiddenNoStableHit,
                debugRenderStatus = currentDebugRenderStatus()
            )
        }

        if (placementMode != PlacementMode.WaitingForPlacement) {
            hidePreviewNode(bundle)
            return FrameProcessingResult(
                isCameraTracking = true,
                hitUiModel = hitUiModel,
                previewRenderState = PreviewRenderState.HiddenNoStableHit,
                debugRenderStatus = currentDebugRenderStatus()
            )
        }

        if (!hitUiModel.hasStableHit) {
            hidePreviewNode(bundle)
            return FrameProcessingResult(
                isCameraTracking = true,
                hitUiModel = hitUiModel,
                previewRenderState = PreviewRenderState.HiddenNoStableHit,
                debugRenderStatus = currentDebugRenderStatus()
            )
        }

        val stable = stableHit
        if (stable == null) {
            hidePreviewNode(bundle)
            return FrameProcessingResult(
                isCameraTracking = true,
                hitUiModel = hitUiModel,
                previewRenderState = PreviewRenderState.HiddenNoStableHit,
                debugRenderStatus = currentDebugRenderStatus()
            )
        }

        val previewResult = updatePreviewPose(bundle, stable)
        val debugStatus = currentDebugRenderStatus()
        val derivedPreviewState = if (previewResult is PreviewRenderState.Error) {
            previewResult
        } else {
            deriveVisiblePreviewState(readyAsset, debugStatus)
        }
        return FrameProcessingResult(
            isCameraTracking = true,
            hitUiModel = hitUiModel,
            previewRenderState = derivedPreviewState,
            debugRenderStatus = debugStatus
        )
    }

    override fun placePreparedImage(
        session: Session,
        preparedAsset: PreparedRenderAsset
    ): AppResult<PlacedImageState> {
        val sceneEngine = engine ?: return AppResult.Failure(AppError.Unexpected("AR engine unavailable."))
        val sceneNodes = childNodes ?: return AppResult.Failure(AppError.Unexpected("AR scene unavailable."))
        val stable = stableHit ?: return AppResult.Failure(AppError.NoValidPlane())

        val currentAsset = activePreparedAsset
        if (currentAsset == null || currentAsset.assetHandleId != preparedAsset.assetHandleId) {
            return AppResult.Failure(AppError.StaleOrMissingPreparedAssetHandle())
        }

        val bundle = preparedBundleRegistry[preparedAsset.assetHandleId]
            ?: return AppResult.Failure(AppError.StaleOrMissingPreparedAssetHandle())
        if (bundle.role != NodeRole.Preview) {
            return AppResult.Failure(AppError.StaleOrMissingPreparedAssetHandle())
        }
        if (!isPreviewAttached(bundle.node)) {
            return AppResult.Failure(AppError.PreviewRenderCreationFailed("Prepared preview node is not attached."))
        }

        return try {
            val placement = computePlacement(stable)
            val anchor = session.createAnchor(
                Pose.makeTranslation(
                    placement.translationX,
                    placement.translationY,
                    placement.translationZ
                )
            )
            val anchorNode = AnchorNode(sceneEngine, anchor)
            val node = bundle.node

            deletePlacedImage()

            sceneNodes.remove(node)
            node.parent?.removeChildNode(node)
            node.position = Position(x = 0f, y = DEFAULT_HEIGHT_METERS * 0.5f, z = 0f)
            node.rotation = Rotation(x = 0f, y = normalizeDegrees(placement.rotationYDegrees), z = 0f)
            node.scale = Scale(1f)
            setNodeAlpha(node, 1f)
            node.isVisible = true

            anchorNode.addChildNode(node)
            sceneNodes += anchorNode

            bundle.role = NodeRole.Placed
            placedAnchorNode = anchorNode
            placedImageNode = node
            placedAssetHandleId = preparedAsset.assetHandleId
            placedState = PlacedImageState(
                anchorId = anchor.hashCode().toString(),
                widthMeters = DEFAULT_HEIGHT_METERS * preparedAsset.aspectRatio,
                heightMeters = DEFAULT_HEIGHT_METERS,
                transform = PlacementTransform(scale = 1f, rotationYDegrees = normalizeDegrees(placement.rotationYDegrees))
            )

            debugStatusTracker.onPlaced(
                assetHandleId = preparedAsset.assetHandleId,
                placedAttached = isPlacedAttached()
            )

            AppResult.Success(requireNotNull(placedState))
        } catch (t: Throwable) {
            logger.e(TAG, "Failed to place prepared image.", t)
            AppResult.Failure(AppError.Unexpected("Failed to place image in AR scene."))
        }
    }

    override fun enterRepositionMode() {
        placedImageNode?.let { setNodeAlpha(it, REPOSITION_ALPHA) }
    }

    override fun confirmReposition(session: Session): AppResult<PlacedImageState> {
        val stable = stableHit ?: return AppResult.Failure(AppError.NoValidPlane())
        val sceneEngine = engine ?: return AppResult.Failure(AppError.Unexpected("AR engine unavailable."))
        val sceneNodes = childNodes ?: return AppResult.Failure(AppError.Unexpected("AR scene unavailable."))
        val node = placedImageNode ?: return AppResult.Failure(AppError.Unexpected("No placed image."))
        val oldAnchorNode = placedAnchorNode ?: return AppResult.Failure(AppError.Unexpected("No placed anchor."))
        val currentState = placedState ?: return AppResult.Failure(AppError.Unexpected("No placed state."))
        val handleId = placedAssetHandleId ?: return AppResult.Failure(AppError.StaleOrMissingPreparedAssetHandle())

        return try {
            val placement = computePlacement(stable)
            val newAnchor = session.createAnchor(
                Pose.makeTranslation(
                    placement.translationX,
                    placement.translationY,
                    placement.translationZ
                )
            )
            val newAnchorNode = AnchorNode(sceneEngine, newAnchor)

            oldAnchorNode.removeChildNode(node)
            sceneNodes.remove(oldAnchorNode)
            oldAnchorNode.detachAnchor()
            oldAnchorNode.destroy()

            node.position = Position(x = 0f, y = DEFAULT_HEIGHT_METERS * 0.5f, z = 0f)
            node.rotation = Rotation(x = 0f, y = currentState.transform.rotationYDegrees, z = 0f)
            node.scale = Scale(currentState.transform.scale)
            setNodeAlpha(node, 1f)

            newAnchorNode.addChildNode(node)
            sceneNodes += newAnchorNode

            placedAnchorNode = newAnchorNode
            placedState = currentState.copy(anchorId = newAnchor.hashCode().toString())

            debugStatusTracker.onPlaced(assetHandleId = handleId, placedAttached = isPlacedAttached())
            AppResult.Success(requireNotNull(placedState))
        } catch (t: Throwable) {
            logger.e(TAG, "Failed to reposition image.", t)
            AppResult.Failure(AppError.Unexpected("Failed to reposition image."))
        }
    }

    override fun cancelReposition() {
        placedImageNode?.let { setNodeAlpha(it, 1f) }
    }

    override fun applyTransform(scale: Float, rotationYDegrees: Float) {
        val node = placedImageNode ?: return
        val clampedScale = scale.coerceIn(MIN_SCALE, MAX_SCALE)
        val normalizedRotation = normalizeDegrees(rotationYDegrees)
        node.scale = Scale(clampedScale)
        node.rotation = Rotation(x = 0f, y = normalizedRotation, z = 0f)
        placedState = placedState?.copy(
            transform = PlacementTransform(
                scale = clampedScale,
                rotationYDegrees = normalizedRotation
            )
        )
    }

    override fun deleteImage() {
        val handleId = placedAssetHandleId
        deletePlacedImage()
        if (handleId != null) {
            val bundle = preparedBundleRegistry[handleId]
            if (bundle != null) {
                bundle.role = NodeRole.Preview
                attachPreviewToSceneRoot(bundle)
                bundle.node.isVisible = false
                debugStatusTracker.onPreviewHidden(
                    assetHandleId = handleId,
                    attached = isPreviewAttached(bundle.node)
                )
            }
        }
    }

    override fun currentDebugRenderStatus(): DebugRenderStatus {
        val tracked = debugStatusTracker.current()
        val activeHandle = activePreparedAsset?.assetHandleId
        val previewBundle = activeHandle
            ?.let { preparedBundleRegistry[it] }
            ?.takeIf { it.role == NodeRole.Preview }
        return tracked.copy(
            previewNodeExists = previewBundle != null,
            previewNodeAttached = previewBundle?.node?.let(::isPreviewAttached) ?: false,
            previewNodeVisible = previewBundle?.node?.isVisible == true,
            placedNodeExists = placedImageNode != null,
            placedNodeAttached = isPlacedAttached(),
            preparedAssetHandleId = activeHandle,
            previewAssetHandleId = previewBundle?.preparedAsset?.assetHandleId,
            placedAssetHandleId = placedAssetHandleId
        )
    }

    override fun release() {
        invalidatePreparedRegistry()
        materialLoader?.destroy()
        materialLoader = null
        engine = null
        childNodes = null
        clearStabilization()
    }

    private fun computeCenterHit(
        frame: Frame,
        viewportWidthPx: Int,
        viewportHeightPx: Int
    ): HitTestResult? {
        val centerX = viewportWidthPx * 0.5f
        val centerY = viewportHeightPx * 0.5f

        var best: HitTestResult? = null
        for (hit in frame.hitTest(centerX, centerY)) {
            val trackable = hit.trackable
            if (trackable is Plane &&
                trackable.trackingState == TrackingState.TRACKING &&
                (trackable.type == Plane.Type.HORIZONTAL_UPWARD_FACING ||
                    trackable.type == Plane.Type.VERTICAL) &&
                trackable.isPoseInPolygon(hit.hitPose)
            ) {
                val candidate = HitTestResult(
                    hitPose = hit.hitPose,
                    cameraPose = frame.camera.pose,
                    trackableId = stableTrackableId(trackable),
                    distanceMeters = hit.distance
                )
                if (best == null || candidate.distanceMeters < best.distanceMeters) {
                    best = candidate
                }
            }
        }
        return best
    }

    private fun updateStabilization(hit: HitTestResult?): HitTestUiModel {
        if (hit == null) {
            clearStabilization()
            return HitTestUiModel()
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

        val hasStableHit = stabilizationFrames >= REQUIRED_STABLE_FRAMES
        stableHit = if (hasStableHit) hit else null

        return HitTestUiModel(
            hasValidHit = true,
            stabilizationFrames = stabilizationFrames,
            hasStableHit = hasStableHit,
            trackableId = hit.trackableId
        )
    }

    private fun clearStabilization() {
        candidateTrackableId = null
        candidatePose = null
        stabilizationFrames = 0
        stableHit = null
    }

    private fun updatePreviewPose(bundle: PreparedRenderBundle, hit: HitTestResult): PreviewRenderState {
        return try {
            attachPreviewToSceneRoot(bundle)
            val placement = computePlacement(hit)
            val node = bundle.node
            node.position = Position(
                x = placement.translationX,
                y = placement.translationY + DEFAULT_HEIGHT_METERS * 0.5f,
                z = placement.translationZ
            )
            node.rotation = Rotation(x = 0f, y = normalizeDegrees(placement.rotationYDegrees), z = 0f)
            node.scale = Scale(1f)
            node.isVisible = true
            debugStatusTracker.onPreviewPoseUpdated(
                assetHandleId = bundle.preparedAsset.assetHandleId,
                attached = isPreviewAttached(node),
                visible = node.isVisible
            )
            PreviewRenderState.HiddenNoStableHit
        } catch (t: Throwable) {
            logger.e(TAG, "Failed to update preview pose.", t)
            bundle.node.isVisible = false
            debugStatusTracker.onPreviewHidden(
                assetHandleId = bundle.preparedAsset.assetHandleId,
                attached = isPreviewAttached(bundle.node)
            )
            PreviewRenderState.Error("Preview pose update failed.")
        }
    }

    private fun deriveVisiblePreviewState(
        readyAsset: PreparedRenderAsset,
        debugStatus: DebugRenderStatus
    ): PreviewRenderState {
        val handleId = readyAsset.assetHandleId
        val valid = debugStatus.preparedAssetHandleId == handleId &&
            debugStatus.previewAssetHandleId == handleId &&
            debugStatus.previewNodeAttached &&
            debugStatus.previewNodeVisible &&
            debugStatus.previewPoseUpdatedForAssetHandleId == handleId &&
            debugStatus.previewPoseUpdateFrameCount > 0L
        return if (valid) {
            PreviewRenderState.Visible(handleId)
        } else {
            PreviewRenderState.Error(AppError.PreviewIdentityMismatch().message)
        }
    }

    private fun hideActivePreviewNode() {
        val activeHandle = activePreparedAsset?.assetHandleId ?: return
        preparedBundleRegistry[activeHandle]
            ?.takeIf { it.role == NodeRole.Preview }
            ?.let(::hidePreviewNode)
    }

    private fun hidePreviewNode(bundle: PreparedRenderBundle) {
        bundle.node.isVisible = false
        debugStatusTracker.onPreviewHidden(
            assetHandleId = bundle.preparedAsset.assetHandleId,
            attached = isPreviewAttached(bundle.node)
        )
    }

    private fun attachPreviewToSceneRoot(bundle: PreparedRenderBundle) {
        if (bundle.role != NodeRole.Preview) {
            return
        }
        val node = bundle.node
        val sceneNodes = childNodes ?: return
        if (node.parent != null) {
            node.parent?.removeChildNode(node)
        }
        if (!sceneNodes.contains(node)) {
            sceneNodes += node
        }
    }

    private fun buildImageNode(
        bitmap: Bitmap,
        sceneMaterialLoader: MaterialLoader,
        aspectRatio: Float
    ): ImageNode {
        return ImageNode(
            materialLoader = sceneMaterialLoader,
            bitmap = bitmap,
            size = Size(
                x = DEFAULT_HEIGHT_METERS * aspectRatio,
                y = DEFAULT_HEIGHT_METERS,
                z = 0f
            ),
            center = Position(x = 0f, y = 0f, z = 0f),
            builderApply = {
                culling(false)
            }
        ).apply {
            isShadowCaster = false
            isShadowReceiver = false
            configureImageMaterialBestEffort(this)
        }
    }

    private fun invalidatePreparedRegistry() {
        deletePlacedImage()
        val sceneNodes = childNodes
        preparedBundleRegistry.values.forEach { bundle ->
            try {
                bundle.node.parent?.removeChildNode(bundle.node)
            } catch (_: Throwable) {
            }
            try {
                sceneNodes?.remove(bundle.node)
            } catch (_: Throwable) {
            }
            try {
                bundle.node.destroy()
            } catch (_: Throwable) {
            }
            bundle.bitmap.safeRecycle()
        }
        preparedBundleRegistry.clear()
        activePreparedAsset = null
        placedAssetHandleId = null
        placedState = null
        debugStatusTracker.onPreparedAssetInvalidated()
    }

    private fun deletePlacedImage() {
        val sceneNodes = childNodes
        val anchorNode = placedAnchorNode
        val node = placedImageNode

        if (node != null && anchorNode != null && node.parent == anchorNode) {
            anchorNode.removeChildNode(node)
        }
        if (anchorNode != null) {
            sceneNodes?.remove(anchorNode)
            anchorNode.detachAnchor()
            anchorNode.destroy()
        }

        placedAnchorNode = null
        placedImageNode = null
        placedState = null
        placedAssetHandleId = null
        debugStatusTracker.onDeleted()
    }

    private fun computePlacement(hit: HitTestResult): PlacementComputation {
        val hitTranslation = hit.hitPose.translation
        val cameraTranslation = hit.cameraPose.translation

        val dx = cameraTranslation[0] - hitTranslation[0]
        val dz = cameraTranslation[2] - hitTranslation[2]
        val horizontalLength = sqrt(dx * dx + dz * dz)
        val offsetX = if (horizontalLength > 0.0001f) {
            (dx / horizontalLength) * PREVIEW_SURFACE_OFFSET_METERS
        } else {
            0f
        }
        val offsetZ = if (horizontalLength > 0.0001f) {
            (dz / horizontalLength) * PREVIEW_SURFACE_OFFSET_METERS
        } else {
            0f
        }

        val rotationY = Math.toDegrees(atan2(dx, dz).toDouble()).toFloat()
        return PlacementComputation(
            translationX = hitTranslation[0] + offsetX,
            translationY = hitTranslation[1],
            translationZ = hitTranslation[2] + offsetZ,
            rotationYDegrees = rotationY
        )
    }

    private fun isPreviewAttached(node: ImageNode): Boolean {
        val sceneNodes = childNodes
        return node.parent != null || (sceneNodes?.contains(node) == true)
    }

    private fun isPlacedAttached(): Boolean {
        val sceneNodes = childNodes
        val anchorNode = placedAnchorNode
        val node = placedImageNode
        return node != null &&
            anchorNode != null &&
            node.parent == anchorNode &&
            (sceneNodes?.contains(anchorNode) == true)
    }

    private fun isApproxSamePose(first: Pose, second: Pose): Boolean {
        return isApproxTranslation(first.translation, second.translation, POSE_EPSILON_METERS)
    }

    private fun normalizeDegrees(value: Float): Float {
        var normalized = value % 360f
        if (normalized < 0f) {
            normalized += 360f
        }
        return normalized
    }

    private fun setNodeAlpha(node: ImageNode, alpha: Float) {
        val clamped = alpha.coerceIn(0f, 1f)
        try {
            val setter = node.javaClass.methods.firstOrNull {
                it.name == "setAlpha" &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == Float::class.javaPrimitiveType
            }
            if (setter != null) {
                setter.invoke(node, clamped)
                return
            }
            val field = node.javaClass.getDeclaredField("alpha")
            field.isAccessible = true
            field.setFloat(node, clamped)
        } catch (_: Throwable) {
        }
    }

    private fun configureImageMaterialBestEffort(node: ImageNode) {
        val booleanFalse = false
        val booleanTrue = true
        val methodCandidates = listOf(
            "setUnlit" to booleanTrue,
            "setLit" to booleanFalse,
            "setDoubleSided" to booleanTrue,
            "setCulling" to booleanFalse
        )
        methodCandidates.forEach { (name, value) ->
            runCatching {
                val method = node.javaClass.methods.firstOrNull {
                    it.name == name &&
                        it.parameterTypes.size == 1 &&
                        it.parameterTypes[0] == Boolean::class.javaPrimitiveType
                }
                method?.invoke(node, value)
            }
        }
    }

    private fun Bitmap.safeRecycle() {
        try {
            if (!isRecycled) {
                recycle()
            }
        } catch (_: Throwable) {
        }
    }

    private data class PlacementComputation(
        val translationX: Float,
        val translationY: Float,
        val translationZ: Float,
        val rotationYDegrees: Float
    )

    private data class PreparedRenderBundle(
        val selectedImage: SelectedImage,
        val preparedAsset: PreparedRenderAsset,
        val bitmap: Bitmap,
        val node: ImageNode,
        var role: NodeRole
    )

    private enum class NodeRole {
        Preview,
        Placed
    }

    private companion object {
        private const val TAG = "ArSceneController"
        private const val REQUIRED_STABLE_FRAMES = 3
        private const val DEFAULT_HEIGHT_METERS = 0.30f
        private const val PREVIEW_SURFACE_OFFSET_METERS = 0.01f
        private const val POSE_EPSILON_METERS = 0.12f
        private const val MIN_SCALE = 0.25f
        private const val MAX_SCALE = 4.0f
        private const val REPOSITION_ALPHA = 0.5f

    }
}

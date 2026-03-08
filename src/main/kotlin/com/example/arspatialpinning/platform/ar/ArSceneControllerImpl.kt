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
import java.lang.reflect.InvocationTargetException
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

        val previewNode = try {
            buildImageNode(
                bitmap = decodedBitmap,
                sceneMaterialLoader = sceneMaterialLoader,
                aspectRatio = asset.aspectRatio
            ).apply {
                isVisible = false
            }
        } catch (error: IllegalArgumentException) {
            decodedBitmap.safeRecycle()
            logger.e(TAG, "Failed to build preview node for selected image.", error)
            return AppResult.Failure(AppError.PreviewRenderCreationFailed("Failed to prepare selected image render asset."))
        } catch (error: IllegalStateException) {
            decodedBitmap.safeRecycle()
            logger.e(TAG, "Failed to build preview node for selected image.", error)
            return AppResult.Failure(AppError.PreviewRenderCreationFailed("Failed to prepare selected image render asset."))
        } catch (error: RuntimeException) {
            decodedBitmap.safeRecycle()
            logger.e(TAG, "Failed to build preview node for selected image.", error)
            return AppResult.Failure(AppError.PreviewRenderCreationFailed("Failed to prepare selected image render asset."))
        }

        try {
            sceneNodes += previewNode
        } catch (error: UnsupportedOperationException) {
            previewNode.safeDestroy(logger)
            decodedBitmap.safeRecycle()
            logger.e(TAG, "Failed to attach preview node to scene.", error)
            return AppResult.Failure(AppError.PreviewRenderCreationFailed("Failed to prepare selected image render asset."))
        } catch (error: RuntimeException) {
            previewNode.safeDestroy(logger)
            decodedBitmap.safeRecycle()
            logger.e(TAG, "Failed to attach preview node to scene.", error)
            return AppResult.Failure(AppError.PreviewRenderCreationFailed("Failed to prepare selected image render asset."))
        }

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

        return AppResult.Success(asset)
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

        val placement = computePlacement(stable)
        val anchor = try {
            session.createAnchor(
                Pose.makeTranslation(
                    placement.translationX,
                    placement.translationY,
                    placement.translationZ
                )
            )
        } catch (error: IllegalArgumentException) {
            logger.e(TAG, "Failed to place prepared image.", error)
            return AppResult.Failure(AppError.ArPlacementFailed())
        } catch (error: IllegalStateException) {
            logger.e(TAG, "Failed to place prepared image.", error)
            return AppResult.Failure(AppError.ArPlacementFailed())
        } catch (error: RuntimeException) {
            logger.e(TAG, "Failed to place prepared image.", error)
            return AppResult.Failure(AppError.ArPlacementFailed())
        }

        val anchorNode = try {
            AnchorNode(sceneEngine, anchor)
        } catch (error: IllegalArgumentException) {
            logger.e(TAG, "Failed to place prepared image.", error)
            safeDetachAnchor(anchor, logger)
            return AppResult.Failure(AppError.ArPlacementFailed())
        } catch (error: IllegalStateException) {
            logger.e(TAG, "Failed to place prepared image.", error)
            safeDetachAnchor(anchor, logger)
            return AppResult.Failure(AppError.ArPlacementFailed())
        } catch (error: RuntimeException) {
            logger.e(TAG, "Failed to place prepared image.", error)
            safeDetachAnchor(anchor, logger)
            return AppResult.Failure(AppError.ArPlacementFailed())
        }

        val node = bundle.node

        try {
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
        } catch (error: IllegalStateException) {
            logger.e(TAG, "Failed to place prepared image.", error)
            anchorNode.safeDestroy(logger)
            safeDetachAnchor(anchor, logger)
            return AppResult.Failure(AppError.ArPlacementFailed())
        } catch (error: UnsupportedOperationException) {
            logger.e(TAG, "Failed to place prepared image.", error)
            anchorNode.safeDestroy(logger)
            safeDetachAnchor(anchor, logger)
            return AppResult.Failure(AppError.ArPlacementFailed())
        } catch (error: RuntimeException) {
            logger.e(TAG, "Failed to place prepared image.", error)
            anchorNode.safeDestroy(logger)
            safeDetachAnchor(anchor, logger)
            return AppResult.Failure(AppError.ArPlacementFailed())
        }

        bundle.role = NodeRole.Placed
        placedAnchorNode = anchorNode
        placedImageNode = node
        placedAssetHandleId = preparedAsset.assetHandleId
        val placed = PlacedImageState(
            anchorId = anchor.hashCode().toString(),
            widthMeters = DEFAULT_HEIGHT_METERS * preparedAsset.aspectRatio,
            heightMeters = DEFAULT_HEIGHT_METERS,
            transform = PlacementTransform(scale = 1f, rotationYDegrees = normalizeDegrees(placement.rotationYDegrees))
        )
        placedState = placed

        debugStatusTracker.onPlaced(
            assetHandleId = preparedAsset.assetHandleId,
            placedAttached = isPlacedAttached()
        )

        return AppResult.Success(placed)
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

        val placement = computePlacement(stable)
        val newAnchor = try {
            session.createAnchor(
                Pose.makeTranslation(
                    placement.translationX,
                    placement.translationY,
                    placement.translationZ
                )
            )
        } catch (error: IllegalArgumentException) {
            logger.e(TAG, "Failed to reposition image.", error)
            return AppResult.Failure(AppError.ArRepositionFailed())
        } catch (error: IllegalStateException) {
            logger.e(TAG, "Failed to reposition image.", error)
            return AppResult.Failure(AppError.ArRepositionFailed())
        } catch (error: RuntimeException) {
            logger.e(TAG, "Failed to reposition image.", error)
            return AppResult.Failure(AppError.ArRepositionFailed())
        }
        val newAnchorNode = try {
            AnchorNode(sceneEngine, newAnchor)
        } catch (error: IllegalArgumentException) {
            logger.e(TAG, "Failed to reposition image.", error)
            safeDetachAnchor(newAnchor, logger)
            return AppResult.Failure(AppError.ArRepositionFailed())
        } catch (error: IllegalStateException) {
            logger.e(TAG, "Failed to reposition image.", error)
            safeDetachAnchor(newAnchor, logger)
            return AppResult.Failure(AppError.ArRepositionFailed())
        } catch (error: RuntimeException) {
            logger.e(TAG, "Failed to reposition image.", error)
            safeDetachAnchor(newAnchor, logger)
            return AppResult.Failure(AppError.ArRepositionFailed())
        }

        try {
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
        } catch (error: IllegalStateException) {
            logger.e(TAG, "Failed to reposition image.", error)
            newAnchorNode.safeDestroy(logger)
            safeDetachAnchor(newAnchor, logger)
            return AppResult.Failure(AppError.ArRepositionFailed())
        } catch (error: UnsupportedOperationException) {
            logger.e(TAG, "Failed to reposition image.", error)
            newAnchorNode.safeDestroy(logger)
            safeDetachAnchor(newAnchor, logger)
            return AppResult.Failure(AppError.ArRepositionFailed())
        } catch (error: RuntimeException) {
            logger.e(TAG, "Failed to reposition image.", error)
            newAnchorNode.safeDestroy(logger)
            safeDetachAnchor(newAnchor, logger)
            return AppResult.Failure(AppError.ArRepositionFailed())
        }

        placedAnchorNode = newAnchorNode
        val placed = currentState.copy(anchorId = newAnchor.hashCode().toString())
        placedState = placed

        debugStatusTracker.onPlaced(assetHandleId = handleId, placedAttached = isPlacedAttached())
        return AppResult.Success(placed)
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
        } catch (error: IllegalStateException) {
            logger.e(TAG, "Failed to update preview pose.", error)
            bundle.node.isVisible = false
            debugStatusTracker.onPreviewHidden(
                assetHandleId = bundle.preparedAsset.assetHandleId,
                attached = isPreviewAttached(bundle.node)
            )
            PreviewRenderState.Error("Preview pose update failed.")
        } catch (error: RuntimeException) {
            logger.e(TAG, "Failed to update preview pose.", error)
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
            tryRemoveFromParent(bundle.node)
            tryRemoveFromScene(sceneNodes, bundle.node)
            bundle.node.safeDestroy(logger)
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
            try {
                anchorNode.removeChildNode(node)
            } catch (error: IllegalStateException) {
                logger.d(TAG, "Failed to remove placed image node from anchor: ${error.message}")
            } catch (error: RuntimeException) {
                logger.d(TAG, "Failed to remove placed image node from anchor: ${error.message}")
            }
        }
        if (anchorNode != null) {
            tryRemoveFromScene(sceneNodes, anchorNode)
            anchorNode.safeDestroy(logger)
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
        } catch (error: NoSuchFieldException) {
            logger.d(TAG, "Alpha field is unavailable: ${error.message}")
        } catch (error: SecurityException) {
            logger.d(TAG, "Alpha reflection blocked: ${error.message}")
        } catch (error: IllegalAccessException) {
            logger.d(TAG, "Alpha reflection inaccessible: ${error.message}")
        } catch (error: IllegalArgumentException) {
            logger.d(TAG, "Alpha reflection call invalid: ${error.message}")
        } catch (error: InvocationTargetException) {
            logger.d(TAG, "Alpha reflection target failed: ${error.targetException?.message ?: error.message}")
        } catch (error: IllegalStateException) {
            logger.d(TAG, "Alpha update failed: ${error.message}")
        } catch (error: RuntimeException) {
            // SceneView node internals may throw runtime exceptions during material updates.
            logger.d(TAG, "Alpha update failed: ${error.message}")
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
            try {
                val method = node.javaClass.methods.firstOrNull {
                    it.name == name &&
                        it.parameterTypes.size == 1 &&
                        it.parameterTypes[0] == Boolean::class.javaPrimitiveType
                }
                method?.invoke(node, value)
            } catch (error: IllegalAccessException) {
                logger.d(TAG, "Image material config failed for $name: ${error.message}")
            } catch (error: IllegalArgumentException) {
                logger.d(TAG, "Image material config failed for $name: ${error.message}")
            } catch (error: InvocationTargetException) {
                logger.d(TAG, "Image material config failed for $name: ${error.targetException?.message ?: error.message}")
            } catch (error: IllegalStateException) {
                logger.d(TAG, "Image material config failed for $name: ${error.message}")
            } catch (error: RuntimeException) {
                // SceneView APIs may throw runtime exceptions depending on renderer state.
                logger.d(TAG, "Image material config failed for $name: ${error.message}")
            }
        }
    }

    private fun Bitmap.safeRecycle() {
        try {
            if (!isRecycled) {
                recycle()
            }
        } catch (error: IllegalStateException) {
            logger.d(TAG, "Bitmap recycle failed: ${error.message}")
        } catch (error: RuntimeException) {
            logger.d(TAG, "Bitmap recycle failed: ${error.message}")
        }
    }

    private fun tryRemoveFromParent(node: Node) {
        try {
            node.parent?.removeChildNode(node)
        } catch (error: IllegalStateException) {
            logger.d(TAG, "Failed to remove node from parent: ${error.message}")
        } catch (error: RuntimeException) {
            logger.d(TAG, "Failed to remove node from parent: ${error.message}")
        }
    }

    private fun tryRemoveFromScene(sceneNodes: MutableList<Node>?, node: Node) {
        if (sceneNodes == null) {
            return
        }
        try {
            sceneNodes.remove(node)
        } catch (error: UnsupportedOperationException) {
            logger.d(TAG, "Failed to remove node from scene: ${error.message}")
        } catch (error: RuntimeException) {
            logger.d(TAG, "Failed to remove node from scene: ${error.message}")
        }
    }

    private fun AnchorNode.safeDestroy(logger: Logger) {
        try {
            detachAnchor()
        } catch (error: IllegalStateException) {
            logger.d(TAG, "Failed to detach anchor node: ${error.message}")
        } catch (error: RuntimeException) {
            logger.d(TAG, "Failed to detach anchor node: ${error.message}")
        }
        try {
            destroy()
        } catch (error: IllegalStateException) {
            logger.d(TAG, "Failed to destroy anchor node: ${error.message}")
        } catch (error: RuntimeException) {
            logger.d(TAG, "Failed to destroy anchor node: ${error.message}")
        }
    }

    private fun Node.safeDestroy(logger: Logger) {
        try {
            destroy()
        } catch (error: IllegalStateException) {
            logger.d(TAG, "Failed to destroy node: ${error.message}")
        } catch (error: RuntimeException) {
            logger.d(TAG, "Failed to destroy node: ${error.message}")
        }
    }

    private fun safeDetachAnchor(anchor: com.google.ar.core.Anchor, logger: Logger) {
        try {
            anchor.detach()
        } catch (error: IllegalStateException) {
            logger.d(TAG, "Failed to detach anchor: ${error.message}")
        } catch (error: RuntimeException) {
            logger.d(TAG, "Failed to detach anchor: ${error.message}")
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

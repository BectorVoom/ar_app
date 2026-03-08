package com.example.arspatialpinning.platform.ar

import com.example.arspatialpinning.domain.model.DebugRenderStatus

internal class DebugRenderStatusTracker {
    private var status: DebugRenderStatus = DebugRenderStatus()

    fun onPreparedAssetRegistered(assetHandleId: String) {
        status = status.copy(
            preparedAssetHandleId = assetHandleId,
            previewAssetHandleId = null,
            placedAssetHandleId = null,
            previewPoseUpdatedForAssetHandleId = null,
            previewPoseUpdateFrameCount = 0L,
            placedNodeExists = false,
            placedNodeAttached = false
        )
    }

    fun onPreviewNodePrepared(assetHandleId: String, attached: Boolean) {
        status = DebugRenderStatus(
            previewNodeExists = true,
            previewNodeAttached = attached,
            previewNodeVisible = false,
            placedNodeExists = false,
            placedNodeAttached = false,
            previewPoseUpdateFrameCount = 0L,
            preparedAssetHandleId = assetHandleId,
            previewAssetHandleId = assetHandleId,
            placedAssetHandleId = null,
            previewPoseUpdatedForAssetHandleId = null
        )
    }

    fun onPreviewHidden(assetHandleId: String?, attached: Boolean) {
        status = status.copy(
            previewNodeExists = assetHandleId != null,
            previewNodeAttached = attached,
            previewNodeVisible = false,
            previewAssetHandleId = assetHandleId
        )
    }

    fun onPreviewPoseUpdated(assetHandleId: String, attached: Boolean, visible: Boolean) {
        val nextCount = if (status.previewPoseUpdatedForAssetHandleId == assetHandleId) {
            status.previewPoseUpdateFrameCount + 1L
        } else {
            1L
        }
        status = status.copy(
            previewNodeExists = true,
            previewNodeAttached = attached,
            previewNodeVisible = visible,
            previewPoseUpdateFrameCount = nextCount,
            preparedAssetHandleId = assetHandleId,
            previewAssetHandleId = assetHandleId,
            previewPoseUpdatedForAssetHandleId = assetHandleId
        )
    }

    fun onPlaced(assetHandleId: String, placedAttached: Boolean) {
        status = status.copy(
            previewNodeExists = false,
            previewNodeAttached = false,
            previewNodeVisible = false,
            placedNodeExists = true,
            placedNodeAttached = placedAttached,
            preparedAssetHandleId = assetHandleId,
            placedAssetHandleId = assetHandleId
        )
    }

    fun onDeleted() {
        status = status.copy(
            placedNodeExists = false,
            placedNodeAttached = false,
            previewNodeVisible = false,
            placedAssetHandleId = null
        )
    }

    fun onPreparedAssetInvalidated() {
        status = DebugRenderStatus(
            previewNodeExists = false,
            previewNodeAttached = false,
            previewNodeVisible = false,
            placedNodeExists = false,
            placedNodeAttached = false,
            previewPoseUpdateFrameCount = 0L
        )
    }

    fun current(): DebugRenderStatus = status
}

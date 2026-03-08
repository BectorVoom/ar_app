package com.example.arspatialpinning.domain.model

data class DebugRenderStatus(
    val previewNodeExists: Boolean = false,
    val previewNodeAttached: Boolean = false,
    val previewNodeVisible: Boolean = false,
    val placedNodeExists: Boolean = false,
    val placedNodeAttached: Boolean = false,
    val previewPoseUpdateFrameCount: Long = 0L,
    val preparedAssetHandleId: String? = null,
    val previewAssetHandleId: String? = null,
    val placedAssetHandleId: String? = null,
    val previewPoseUpdatedForAssetHandleId: String? = null
)

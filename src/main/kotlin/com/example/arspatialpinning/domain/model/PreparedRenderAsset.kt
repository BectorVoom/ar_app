package com.example.arspatialpinning.domain.model

data class PreparedRenderAsset(
    val assetHandleId: String,
    val widthPx: Int,
    val heightPx: Int,
    val aspectRatio: Float,
    val selectionRevision: Long
)

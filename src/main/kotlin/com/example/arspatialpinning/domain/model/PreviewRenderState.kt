package com.example.arspatialpinning.domain.model

sealed interface PreviewRenderState {
    data object HiddenNoSelection : PreviewRenderState
    data object HiddenPreparing : PreviewRenderState
    data object HiddenNoTracking : PreviewRenderState
    data object HiddenNoStableHit : PreviewRenderState
    data class Visible(val assetHandleId: String) : PreviewRenderState
    data class Error(val reason: String) : PreviewRenderState
}

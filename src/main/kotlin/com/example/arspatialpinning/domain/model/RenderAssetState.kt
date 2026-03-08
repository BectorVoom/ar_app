package com.example.arspatialpinning.domain.model

sealed interface RenderAssetState {
    data object None : RenderAssetState
    data object Preparing : RenderAssetState
    data class Ready(val asset: PreparedRenderAsset) : RenderAssetState
    data class Error(val reason: String) : RenderAssetState
}

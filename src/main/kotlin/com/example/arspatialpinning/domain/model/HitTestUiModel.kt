package com.example.arspatialpinning.domain.model

data class HitTestUiModel(
    val hasValidHit: Boolean = false,
    val stabilizationFrames: Int = 0,
    val hasStableHit: Boolean = false,
    val trackableId: String? = null
)

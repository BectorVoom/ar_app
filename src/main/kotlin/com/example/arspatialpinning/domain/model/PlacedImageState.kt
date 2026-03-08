package com.example.arspatialpinning.domain.model

data class PlacedImageState(
    val anchorId: String,
    val widthMeters: Float,
    val heightMeters: Float,
    val transform: PlacementTransform = PlacementTransform()
)

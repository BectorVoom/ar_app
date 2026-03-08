package com.example.arspatialpinning.platform.ar

import com.google.ar.core.Pose

data class HitTestResult(
    val hitPose: Pose,
    val cameraPose: Pose,
    val trackableId: String,
    val distanceMeters: Float
)

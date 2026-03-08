package com.example.arspatialpinning.platform.ar

import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.node.ImageNode

data class PinnedImageNode(
    val anchorNode: AnchorNode,
    val imageNode: ImageNode
)

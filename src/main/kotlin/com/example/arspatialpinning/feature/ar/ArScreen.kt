package com.example.arspatialpinning.feature.ar

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.window.layout.WindowMetricsCalculator
import com.example.arspatialpinning.domain.model.PlacementMode
import com.example.arspatialpinning.feature.ar.component.ArControls
import com.example.arspatialpinning.feature.ar.component.ArToolbar
import com.example.arspatialpinning.feature.ar.component.BlockingPanel
import com.example.arspatialpinning.feature.ar.component.RecordingOverlay
import com.example.arspatialpinning.feature.ar.component.ReticleOverlay
import com.google.ar.core.Config
import io.github.sceneview.ar.ARScene
import io.github.sceneview.rememberCollisionSystem
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberNodes
import io.github.sceneview.rememberView

@Composable
fun ArScreen(
    viewModel: ArViewModel,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val activity = context.findActivity()
    val lifecycleOwner = LocalLifecycleOwner.current
    val containerSize = LocalWindowInfo.current.containerSize

    val openImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        viewModel.onImageSelected(uri)
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.onCameraPermissionResult(granted)
    }

    val recordAudioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.onRecordAudioPermissionResult(granted)
    }

    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.onMediaProjectionConsentResult(
            resultCode = result.resultCode,
            data = result.data
        )
    }

    val engine = rememberEngine()
    val view = rememberView(engine)
    val collisionSystem = rememberCollisionSystem(view)
    val childNodes = rememberNodes()

    var viewportWidthPx by remember { mutableIntStateOf(0) }
    var viewportHeightPx by remember { mutableIntStateOf(0) }

    val transformableState = rememberTransformableState { zoomChange, _, rotationChange ->
        viewModel.onTransformGesture(
            scaleFactor = zoomChange,
            rotationDegreesDelta = rotationChange
        )
    }

    val hasRecordAudioPermission = {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    BackHandler {
        viewModel.onUiEvent(
            event = ArUiEvent.BackClicked,
            hasRecordAudioPermission = hasRecordAudioPermission()
        )
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.onArScreenResumed()
                Lifecycle.Event.ON_PAUSE -> viewModel.onArScreenPaused()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            viewModel.onArScreenResumed()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.onRouteExit()
        }
    }

    LaunchedEffect(engine, childNodes) {
        viewModel.bindScene(
            engine = engine,
            childNodes = childNodes
        )
    }

    LaunchedEffect(Unit) {
        val cameraGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        viewModel.onScreenEntered(cameraGranted)
    }

    LaunchedEffect(containerSize) {
        activity?.let {
            val bounds = WindowMetricsCalculator.getOrCreate()
                .computeMaximumWindowMetrics(it)
                .bounds
            viewModel.onMaximumWindowBoundsChanged(bounds)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.sideEffects.collect { sideEffect ->
            when (sideEffect) {
                ArSideEffect.LaunchImagePicker -> {
                    openImageLauncher.launch(arrayOf("image/png", "image/jpeg"))
                }
                ArSideEffect.RequestCameraPermission -> {
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }

                ArSideEffect.RequestRecordAudioPermission -> {
                    recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }

                is ArSideEffect.RequestMediaProjectionConsent -> {
                    mediaProjectionLauncher.launch(sideEffect.intent)
                }

                is ArSideEffect.ShowSnackbar -> snackbarHostState.showSnackbar(sideEffect.message)
                ArSideEffect.NavigateBack -> onNavigateBack()
            }
        }
    }

    Scaffold(
        topBar = {
            ArToolbar(
                recordingState = uiState.recordingState,
                onBack = {
                    viewModel.onUiEvent(
                        event = ArUiEvent.BackClicked,
                        hasRecordAudioPermission = hasRecordAudioPermission()
                    )
                }
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            ArControls(
                uiState = uiState,
                onEvent = { event ->
                    viewModel.onUiEvent(
                        event = event,
                        hasRecordAudioPermission = hasRecordAudioPermission()
                    )
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .onSizeChanged {
                    viewportWidthPx = it.width
                    viewportHeightPx = it.height
                }
                .transformable(
                    state = transformableState,
                    enabled = uiState.placementMode == PlacementMode.Placed
                )
        ) {
            ARScene(
                modifier = Modifier.fillMaxSize(),
                engine = engine,
                view = view,
                collisionSystem = collisionSystem,
                childNodes = childNodes,
                planeRenderer = true,
                sessionConfiguration = { session, config ->
                    config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                    config.lightEstimationMode = Config.LightEstimationMode.DISABLED
                    config.depthMode = if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                        Config.DepthMode.AUTOMATIC
                    } else {
                        Config.DepthMode.DISABLED
                    }
                    config.instantPlacementMode = Config.InstantPlacementMode.DISABLED
                    config.cloudAnchorMode = Config.CloudAnchorMode.DISABLED
                    config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                },
                onSessionCreated = { session ->
                    viewModel.onSessionCreated(session)
                },
                onSessionUpdated = { session, frame ->
                    viewModel.onSessionCreated(session)
                    if (viewportWidthPx > 0 && viewportHeightPx > 0) {
                        viewModel.onFrameUpdated(
                            frame = frame,
                            viewportWidthPx = viewportWidthPx,
                            viewportHeightPx = viewportHeightPx
                        )
                    }
                }
            )

            ReticleOverlay(reticleState = uiState.reticle)

            val blockingError = uiState.blockingError
            if (!uiState.cameraPermissionGranted) {
                Box(modifier = Modifier.align(Alignment.TopCenter)) {
                    BlockingPanel(message = "Camera permission is required.")
                }
            } else if (blockingError != null) {
                Box(modifier = Modifier.align(Alignment.TopCenter)) {
                    BlockingPanel(message = blockingError.message)
                }
            }

            Box(modifier = Modifier.align(Alignment.TopEnd)) {
                RecordingOverlay(recordingState = uiState.recordingState)
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}

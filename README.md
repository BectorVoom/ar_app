# AR Spatial PNG Pinning App — Index README

## Title Page

**Document Title:** Index README  
**System:** Android AR Spatial PNG Pinning App  
**Document Type:** Module Documentation Index  
**Base Design Reference:** `../ar_spatial_pinning_detailed_design_reviewed_v4.md`  
**Technology Stack:** Kotlin, Jetpack Compose, ARCore, SceneView  

## Table of Contents

1. [Title Page](#title-page)
2. [Table of Contents](#table-of-contents)
3. [Source Tree](#source-tree)
4. [Architecture Overview](#architecture-overview)
5. [Module Document Index](#module-document-index)

## Source Tree

```text
com.example.arspatialpinning
├─ app
│  ├─ MainActivity.kt
│  ├─ App.kt
│  └─ navigation
│     ├─ AppNavHost.kt
│     └─ Routes.kt
├─ feature
│  ├─ start
│  │  ├─ StartScreen.kt
│  │  └─ StartViewModel.kt
│  └─ ar
│     ├─ ArScreen.kt
│     ├─ ArViewModel.kt
│     ├─ ArUiState.kt
│     ├─ ArUiEvent.kt
│     ├─ ArSideEffect.kt
│     └─ component
│        ├─ ArToolbar.kt
│        ├─ ArControls.kt
│        ├─ BlockingPanel.kt
│        ├─ ReticleOverlay.kt
│        └─ RecordingOverlay.kt
├─ domain
│  ├─ model
│  │  ├─ SelectedImage.kt
│  │  ├─ PlacementTransform.kt
│  │  ├─ PlacedImageState.kt
│  │  ├─ PlacementMode.kt
│  │  └─ RecordingState.kt
│  └─ usecase
│     ├─ LoadPngUseCase.kt
│     ├─ PlaceImageUseCase.kt
│     ├─ ReplaceImageUseCase.kt
│     ├─ DeleteImageUseCase.kt
│     ├─ EnterRepositionModeUseCase.kt
│     ├─ ConfirmRepositionUseCase.kt
│     ├─ RequestRecordingUseCase.kt
│     ├─ StartRecordingUseCase.kt
│     └─ StopRecordingUseCase.kt
├─ platform
│  ├─ ar
│  │  ├─ ArSceneController.kt
│  │  ├─ ArSceneControllerImpl.kt
│  │  ├─ ArAvailabilityChecker.kt
│  │  ├─ HitTestResult.kt
│  │  ├─ PinnedImageNode.kt
│  │  └─ TextureLoader.kt
│  ├─ media
│  │  ├─ RecordingController.kt
│  │  ├─ RecordingControllerImpl.kt
│  │  ├─ RecordingService.kt
│  │  ├─ RecordingNotificationFactory.kt
│  │  └─ MediaStoreVideoWriter.kt
│  └─ file
│     ├─ ImageUriReader.kt
│     └─ PngValidator.kt
└─ common
   ├─ AppError.kt
   ├─ Result.kt
   ├─ DispatcherProvider.kt
   └─ Logger.kt

modules/
├─ README.md
├─ app.md
├─ feature-start.md
├─ feature-ar.md
├─ domain-model.md
├─ domain-usecase.md
├─ platform-ar.md
├─ platform-media.md
├─ platform-file.md
└─ common.md
```

## Architecture Overview

The system is organized as a layered, feature-oriented Android application.

- **app** hosts the application entry point, navigation graph, and route-level lifecycle boundaries.
- **feature** contains screen-specific UI and presentation logic for the start flow and AR flow.
- **domain** contains immutable business models and use cases that express application behavior independently of Android UI classes.
- **platform** isolates integrations with ARCore, SceneView, MediaProjection, MediaRecorder, MediaStore, and the Storage Access Framework behind stable interfaces.
- **common** provides shared cross-cutting primitives such as error types, result wrappers, dispatchers, and logging.

This structure keeps UI logic, AR scene control, file handling, recording infrastructure, and domain rules separated so that implementation responsibility remains explicit at module boundaries.

## Module Document Index

| Module | Role / Description | Per-Module Document |
|---|---|---|
| `app` | Application bootstrap, main activity hosting, navigation setup, and top-level lifecycle coordination. | [app.md](./app.md) |
| `feature/start` | Start menu UI and presentation flow that enters the AR feature. | [feature-start.md](./feature-start.md) |
| `feature/ar` | AR screen UI, AR interaction state, overlays, user event handling, and side-effect coordination. | [feature-ar.md](./feature-ar.md) |
| `domain/model` | Immutable domain entities and value objects for selected image data, transforms, placement state, and recording state. | [domain-model.md](./domain-model.md) |
| `domain/usecase` | Use cases that coordinate PNG loading, image placement, replacement, deletion, repositioning, and recording actions. | [domain-usecase.md](./domain-usecase.md) |
| `platform/ar` | ARCore and SceneView integration, availability checks, hit testing, anchor/node ownership, and texture loading. | [platform-ar.md](./platform-ar.md) |
| `platform/media` | MediaProjection, foreground service, recorder configuration, notification handling, and MediaStore output integration. | [platform-media.md](./platform-media.md) |
| `platform/file` | PNG acquisition and validation through the Storage Access Framework and session-scoped URI access rules. | [platform-file.md](./platform-file.md) |
| `common` | Shared application primitives including error handling, result abstractions, dispatchers, and logging. | [common.md](./common.md) |


$env:GRADLE_USER_HOME='C:\Users\echin\.gradle'
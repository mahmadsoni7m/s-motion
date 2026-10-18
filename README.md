# SONI MOTION STUDIO (Android)
Create. Animate. Feel the Beat.

## Stack
- **Kotlin + Jetpack Compose**: UI, preview, project state
- **C++ (Android NDK, JNI)**: per-frame audio band analysis (Goertzel) driving the beat-reactive visuals; Vulkan capability probe
- **Android MediaCodec**: decodes the chosen music to PCM for analysis
- **Media3 Transformer**: renders and encodes the video (H.264 + AAC) with the music mixed in
- **OpenGL ES**: custom GLSL post effect (RGB split, beat glitch, vignette) inside Transformer
- **Vulkan**: only a device-capability probe. Rendering does NOT use Vulkan.

## Styles
Layouts: Player card, Glass cover, Neon timer, Ring logo, Minimal wave, Vinyl, Orbit, Bars wall, Effects only (with card size slider). Any audio or video file as music; image or video as background. 21 procedural backgrounds, 15 effects, 20 presets, sliders, text overlay, projects.

## Build the APK with GitHub Actions
1. Create a repo and upload all files (keep the `.github` folder).
2. Open the Actions tab, run "Build APK" (it also runs on every push).
3. Download the artifact `SONI-MOTION-STUDIO-apk`, unzip, install `app-debug.apk` (allow unknown sources).

## Status and limits
Not yet compiled or run by the author of this scaffold: the first CI run may show compile errors to fix. Needs Android 10+. Exports 720p/1080p H.264; no 4K. Preview approximates the GL effect (glitch/vignette appear only in the exported file). Only use music and images you may use.

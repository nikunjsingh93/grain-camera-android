# Grain Camera (Android)

Android CameraX + OpenGL ES 2.0 camera app that previews and captures with Fuji-like film simulations
(Provia, Velvia, Astia, Classic Chrome, Pro Neg Std/Hi, Eterna, Classic Neg, Nostalgic Neg, Acros B&W)
and tweakable **Halation**, **Bloom**, and **Grain** before capture.

> NOTE: Film simulations here are tasteful approximations using tone curves, saturation and gentle split-toning.
> You can replace/augment them later with LUTs if you have licensed profiles.

## Build
1. Open this folder in Android Studio (Hedgehog+).
2. Let Gradle sync.
3. Run on a physical device (recommended). Grant camera permission.

## Controls
- Film Simulation spinner at bottom.
- Sliders: Halation, Bloom, Grain.
- Capture button saves a processed JPG to **Pictures/GrainCamera** via MediaStore.
- Switch toggles front/back camera.

## Notes
- Preview and capture use the same shader so the saved photo matches the preview.
- Camera preview is constrained to 3:4 aspect ratio (portrait orientation) to match typical camera app behavior.
- For higher quality capture, you can add a high-res ImageCapture pipeline and re-run the shader offscreen.

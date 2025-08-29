# Grain Camera (Android)

Android CameraX + OpenGL ES 2.0 camera app that previews and captures with Fuji-like film simulations
(ProView Neutral, Velora Vivid, Asteria Soft, Soft Chrome, Pro Portrait Std/Hi, Eternis Cine, Retro Negative, Nostalgia Negative, Acrux B&W)
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
- Preview shows filtered image with film simulation effects in real-time.
- Camera preview is constrained to 3:4 aspect ratio (portrait orientation) to match typical camera app behavior.
- Captured photos include applied film simulation effects (filters, saturation adjustments).
- Image rotation is automatically corrected during capture.
- For higher quality capture, you can add a high-res ImageCapture pipeline and re-run the shader offscreen.

# Liquid-glass UI foundation

## What this change does

- **Animated backdrop** (`ui/glass/AuraBackdrop.kt`): a slow colour field of four soft radial blobs drawn behind the whole app. Light and dark palettes come from the existing Aura colours.
- **Glass surface** (`ui/glass/GlassSurface.kt`): a translucent container with a gradient fill, a brighter top edge (specular highlight) and a light shadow. Use it in place of opaque cards.
- **3D tilt** (`ui/glass/Tilt3D.kt`): `Modifier.tilt3D()` tilts an element toward the finger while it is dragged, springs back on release, and scales slightly on press. Opt-in per element.
- **Theme** (`ui/theme/Theme.kt`): screen `background` is slightly translucent so the backdrop shows through. Surfaces and text colours are unchanged.
- **Wiring** (`MainActivity.kt`): `AuraApp` is wrapped in `AuraGlassBackdrop`.

## Constraints

- minSdk is 26. Everything here uses plain Compose APIs and radial gradients, so it runs on every supported device. No `RenderEffect` is used.
- "Glass" is simulated with translucency and gradient edges, not a true backdrop blur. True blur is a possible follow-up for API 31+.
- Compose BOM 2025.10.01 and Material3, as in the current build.

## Not in this change

- **`AuraApp.kt` (about 100 KB) is not modified.** Its cards still use opaque `MaterialTheme.colorScheme.surface`, so they stay solid. Applying `GlassSurface` to each card and splitting the file into per-screen composables is a follow-up that needs its own review.
- No per-screen transitions, and no reduced-motion setting yet.
- No new dependencies (no Haze, no Filament/SceneView for real 3D meshes).

## Verification still needed

This change has not been compiled or run on a device.

1. `./gradlew testDebugUnitTest lintDebug assembleDebug` must pass.
2. Install on the target phone (Vivo 1901, arm64) and check light and dark modes.
3. Confirm that the backdrop shows through the screen background and that text remains readable.
4. Check scrolling smoothness. The backdrop redraws continuously, so watch for jank on low-end devices. If it is a problem, lower the animation rate or pause it when the app is in the background.
5. Confirm that `tilt3D` does not interfere with any scrollable list it is used in.

## Known risks

- The backdrop runs an infinite animation, which adds some GPU/CPU cost while the app is in the foreground.
- `tilt3D` consumes drag events, so it must not be used on items inside scrollable containers.
- Because `background` is now translucent, any screen that draws its own opaque background will not show the backdrop. Only screens using the default `MaterialTheme.colorScheme.background` will.

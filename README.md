# Measure

An AR tape measure for Android, inspired by Apple's Measure app. Aim at a surface, tap **+** to drop
points, and read lengths, totals, and areas right on the camera view. Built with ARCore and Jetpack
Compose using Material 3 Expressive.

## Features

- **Lines:** tap **+** to place a point, aim, and tap **✓** to finish a line.
- **Snapping:** the reticle snaps onto existing points so lines can be chained together.
- **Surface-aligned reticle:** the aiming circle lies flat on whatever it's pointing at.
- **Totals:** hover a point or line to see the combined length of everything connected to it.
- **Area:** close a shape to see its area in the middle.
- **Edit points:** hold **+** to pick up a point and move it. Drag it onto another point and tap
  the link button to weld them together.
- **Delete and undo:** tap delete to remove the point or line under the reticle; hold to clear
  everything. Every change can be undone.
- **Copy:** tap any measurement to copy it.
- **Units:** cm, m, in, or ft (feet and inches). Your choice is remembered.

Tap **?** in the app for a quick guide to the less obvious controls.

## Requirements

- An [ARCore-supported device](https://developers.google.com/ar/devices) running Android 12 or
  newer, with Google Play Services for AR installed.
- Devices that support ARCore's Depth API can measure on surfaces that aren't flat planes too.
  Without depth, measuring works only on detected flat surfaces.
- No LiDAR is used. Expect accuracy within about a centimeter or two up close, decreasing with
  distance.

## Building

You'll need:

- **JDK 21.** Gradle finds it automatically if it's installed in a standard location, such as
  `/usr/lib/jvm`, `~/.jdks`, or `~/.sdkman`. You can also set `JAVA_HOME`.
- **Android SDK** with the **Android 37.2** platform (`platforms;android-37.2`).
- The SDK location in `local.properties` (`sdk.dir=/path/to/Android/Sdk`) or `ANDROID_HOME`.

Then, with a device connected over USB or wireless debugging:

```sh
./gradlew installDebug
```

`./gradlew assembleDebug` builds the APK without installing it
(`app/build/outputs/apk/debug/`).

## Releases

Pushing a tag like `v1.2.3` runs [`.github/workflows/release.yml`](.github/workflows/release.yml).
It builds a signed release APK and attaches it to a new GitHub release. The version name comes from
the tag, and the version code is `major * 10000 + minor * 100 + patch`. Tags with a suffix, such as
`v1.3.0-beta1`, are published as pre-releases.

```sh
git tag v1.0.0 && git push origin v1.0.0
```

You can also run the workflow from the Actions tab to test a build without publishing it.

### Signing key (one-time setup)

APKs are signed with your own key. You don't need a Google Play developer account. Android only
installs an update when it's signed with the same key as the installed app, so create one key and
keep it safe. If you lose it, users have to uninstall the app before they can install a new version.

```sh
keytool -genkeypair -keystore measure-release.jks -alias measure \
  -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 measure-release.jks   # copy the output
```

Add these repository secrets (**Settings → Secrets and variables → Actions**):

| Secret              | Value                                   |
| ------------------- | --------------------------------------- |
| `KEYSTORE_BASE64`   | The base64 output from above            |
| `KEYSTORE_PASSWORD` | The keystore password                   |
| `KEY_ALIAS`         | `measure`, or the alias you chose       |
| `KEY_PASSWORD`      | The key password                        |

Back up `measure-release.jks` and its passwords somewhere outside the repo. `*.jks` is gitignored.
Locally, `./gradlew assembleRelease` signs with the same key if `MEASURE_KEYSTORE_FILE`,
`MEASURE_KEYSTORE_PASSWORD`, `MEASURE_KEY_ALIAS`, and `MEASURE_KEY_PASSWORD` are set. Otherwise it
falls back to your debug key.

## Project layout

```
app/src/main/java/app/measure/ar/
├── MainActivity.kt           Camera permission, ARCore install check, session lifecycle
├── ar/
│   ├── MeasureRenderer.kt    AR frame loop: hit-testing, surface estimation, points, lines,
│   │                         undo history, and projection to screen space
│   ├── BackgroundRenderer.kt Draws the camera feed (OpenGL ES)
│   ├── ArFrameState.kt       Per-frame snapshot the UI draws from
│   └── Vec3.kt               Small 3D vector helpers
└── ui/
    ├── MeasureScreen.kt      Compose UI: overlay, reticle, controls, unit switcher, help
    ├── LengthUnit.kt         Unit formatting and the saved unit preference
    └── Theme.kt              Material 3 Expressive theme with dynamic color
```

## How it works

- **The AR thread owns everything.** `MeasureRenderer` runs on the OpenGL thread and is the only
  code that touches ARCore. Each frame it projects the measurements to screen pixels and publishes
  an `ArFrameState`, which the Compose overlay draws. The UI sends actions back as commands that
  are processed on the next frame.
- **Steady surfaces.** Detected planes are preferred. On depth-only surfaces, the renderer samples
  a small cross of points around the center and fits a plane through them, then smooths the result
  over time. This keeps the reticle steady on shadowed or patterned surfaces.
- **Undo history.** Every edit saves a copy of the line list before it changes, so undo can restore
  it. Anchors (ARCore's fixed points in the world) are released once neither the current
  measurements nor the history refer to them.

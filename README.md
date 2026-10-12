# Dicta — voice-dictation overlay for Android (PoC)

Trigger voice dictation **from anywhere on the device** — any app, the home
screen, the lock shade — then copy the text to the clipboard or send it
somewhere. Proof of concept, built to answer: *is this possible with stock
Android APIs?* **Yes.** See [RESEARCH.md](RESEARCH.md) for the documentation
that backs each piece.

## How it works

Three independent triggers, all opening the same overlay:

1. **Quick Settings tile** — swipe down from any app, tap the Dicta tile.
   Uses `TileService.onClick()` → `startActivityAndCollapse()`.
2. **Floating bubble** — an always-on-top mic button (`TYPE_APPLICATION_OVERLAY`)
   you can drag anywhere. Tap it from any app.
3. **Persistent notification** — "Dicta" notification with a Dictate action.

The overlay is a floating dialog-style activity with:

- One-tap dictation via `SpeechRecognizer` (live partial results as you speak)
- Editable text field (fix anything the recognizer got wrong)
- **Copy** → puts the text on the system clipboard (`ClipboardManager`)
- **Screenshot** → captures the screen *behind* Dicta via `MediaProjection`
  (Dicta's own dialog and bubble are hidden for the frame) and attaches the PNG
  to the next Send. Consent is per-session and cannot be made permanent on
  Android 14+, so the system prompt appears on every capture.
- **Send** → Android share sheet (`ACTION_SEND`) to send it anywhere. With a
  screenshot attached it sends `image/png` with the text alongside; with an
  empty text box it sends just the image.
- Fallback to the system voice-typing UI if mic permission is denied

> **Note on sharing text with an image:** `ACTION_SEND` carries both
> `EXTRA_STREAM` and `EXTRA_TEXT`, but that is a request, not a guarantee —
> many receivers (most chat apps) read only the image and silently drop the
> text. Gmail keeps both. Dicta says so in the card once a screenshot is
> attached; use **Copy** if the text must arrive.

## Permissions

| Permission | Why | How granted |
|---|---|---|
| `RECORD_AUDIO` | Dictation | Runtime prompt |
| `SYSTEM_ALERT_WINDOW` | Floating bubble | Settings → "Display over other apps" (one-time toggle) |
| `FOREGROUND_SERVICE` (+ `specialUse`) | Keep the bubble alive | Normal |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | Screenshot capture: on Android 14+ a `mediaProjection`-typed foreground service must already be running *before* `getMediaProjection()` is called | Normal — but the capture itself still needs the per-session consent dialog, every single time |
| `POST_NOTIFICATIONS` | Trigger notification (Android 13+) | Runtime prompt |

No root, no system app, no accessibility service needed.

## Build

Requires JDK 17 and the Android SDK (compileSdk 34). The Gradle wrapper is
committed (`gradlew` + `gradle/wrapper/`, pinned to Gradle 8.7), so no local
Gradle install is needed:

```sh
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

> Until recently this section documented `./gradlew` while no `gradlew` and no
> `gradle-wrapper.jar` were tracked, so the documented command could not work.
> The wrapper is now in the repo.

## Tests

```sh
./gradlew :app:testDebugUnitTest
python3 scripts/assert-tests-ran.py     # asserts the suite ran AND was green
```

JUnit 4 + Robolectric, `testImplementation` only — the APK stays
dependency-free. `.github/workflows/tests.yml` runs both on every push and pull
request, on JDK 17. The second command exists because a Gradle task can report
success having executed **zero** tests; it reads the JUnit XML and fails on an
empty result set rather than trusting the exit status.

On NixOS, AGP's downloaded `aapt2` is a generic-linux binary the system cannot
exec, so add the override:

```sh
./gradlew :app:testDebugUnitTest \
  -Pandroid.aapt2FromMavenOverride="$ANDROID_HOME/build-tools/34.0.0/aapt2"
```

No Gradle/network at all? `scripts/build-offline.sh` builds the same APK
directly with `aapt2` + `kotlinc` + `d8` + `apksigner`. Output:
`build-offline/dicta-debug.apk`. Note that it builds `app/src/main` only — it
does **not** run the unit tests.

Install with `adb install -r app-debug.apk`, open the app once to grant
permissions and add the Quick Settings tile, then trigger Dicta from anywhere.

## Project layout

```
app/src/main/
  AndroidManifest.xml
  java/dev/zacx/dicta/
    MainActivity.kt       # setup: permissions, bubble/notification toggles, tile prompt
    DictateActivity.kt    # the overlay: dictate → edit → copy / screenshot / share
    DictaTileService.kt   # Quick Settings tile trigger
    BubbleService.kt      # floating overlay bubble trigger
    SpeechHelper.kt       # SpeechRecognizer wrapper (partial + final results)
    ShareIntents.kt       # ACTION_SEND construction (text, image, chooser, URI grant)
    # --- screenshot capture ---
    ProjectionService.kt  # the mediaProjection FGS + ProjectionRequests handoff
    ScreenCapturer.kt     # VirtualDisplay + ImageReader, row-stride handling
    ScreenshotSequencer.kt# hide → settle → capture → restore, restore guaranteed
    ScreenshotStore.kt    # writes the PNG to cacheDir, evicting the previous one
    OverlayVisibility.kt  # in-process registry so the bubble can be hidden
    ChoreographerFrameSettler.kt # waits for vsync frames without the hidden windows
    DictaFileProvider.kt  # hand-rolled provider serving the PNG to the share target
  res/                    # layouts, drawables, strings, themes, dimens
app/src/test/java/dev/zacx/dicta/   # JUnit 4 + Robolectric unit tests
.github/workflows/tests.yml         # CI: unit tests + debug APK on JDK 17
```

## Limitations (PoC)

- `SpeechRecognizer` may stream audio to the recognition service's servers;
  there is an on-device path (`createOnDeviceSpeechRecognizer`) on supported
  devices — not wired up here.
- The bubble is killed if the system reclaims the service; reopen the app to
  restart it.
- Dictation language follows the system recognizer's default.

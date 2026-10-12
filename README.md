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
- **Send** → Android share sheet (`ACTION_SEND`) to send it anywhere
- Fallback to the system voice-typing UI if mic permission is denied

## Permissions

| Permission | Why | How granted |
|---|---|---|
| `RECORD_AUDIO` | Dictation | Runtime prompt |
| `SYSTEM_ALERT_WINDOW` | Floating bubble | Settings → "Display over other apps" (one-time toggle) |
| `FOREGROUND_SERVICE` (+ `specialUse`) | Keep the bubble alive | Normal |
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
    DictateActivity.kt    # the overlay: dictate → edit → copy / share
    DictaTileService.kt   # Quick Settings tile trigger
    BubbleService.kt      # floating overlay bubble trigger
    SpeechHelper.kt       # SpeechRecognizer wrapper (partial + final results)
  res/                    # layouts, drawables, strings, themes
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

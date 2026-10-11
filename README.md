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

Requires JDK 17 and the Android SDK (compileSdk 34). On a normal machine:

```sh
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

No Gradle/network? `scripts/build-offline.sh` builds the same APK directly
with `aapt2` + `kotlinc` + `d8` + `apksigner` (used because this environment's
egress proxy blocks Java's HTTPS tunneling, so Gradle can't fetch the Android
Gradle Plugin). Output: `build-offline/dicta-debug.apk`.

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
```

## Limitations (PoC)

- `SpeechRecognizer` may stream audio to the recognition service's servers;
  there is an on-device path (`createOnDeviceSpeechRecognizer`) on supported
  devices — not wired up here.
- The bubble is killed if the system reclaims the service; reopen the app to
  restart it.
- Dictation language follows the system recognizer's default.

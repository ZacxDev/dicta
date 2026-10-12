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

## Remote transcription (self-hosted ASR)

Dictation is transcribed by a **self-hosted speech service**; the phone's
`SpeechRecognizer` is the fallback and the source of live partials. Configure
it in **Settings** from the main screen: a service URL (default
`http://10.42.0.10:8118`) and a bearer token.

### One microphone, teed three ways

🔴 `AudioRecord` and `SpeechRecognizer` **cannot both capture**. Android
enforces that by silently feeding the loser *exact zeros* — `startRecording()`
succeeds, `read()` returns the full byte count, and no exception is thrown. So
there is exactly one microphone client (`MicCapture`, `VOICE_RECOGNITION`,
16 kHz mono PCM-16) and it tees to:

1. a pipe whose read end is handed to the recogniser via
   `RecognizerIntent.EXTRA_AUDIO_SOURCE` (API 33+) — one mic client, one UID,
   nothing to arbitrate. Closing the **write** end is what ends the session: a
   pipe-fed recogniser does not endpoint on trailing silence.
2. `MediaCodec` Opus → `MediaMuxer` Ogg at ~24 kbps mono (≈3 kB/s, so 15 s of
   dictation is ≈45 kB), AAC-LC/MPEG-4 as fallback — this is what gets
   uploaded. No transcoding beyond compression: the service decodes with
   ffmpeg and resamples to 16 kHz mono itself.
3. RMS for the on-screen level meter — the *only* "it is listening" signal in
   the remote-only tiers, where no partial text ever appears.

### Three degradation tiers — every tier still produces a transcript

| Tier | Trigger | Behaviour |
|---|---|---|
| 1 | `EXTRA_AUDIO_SOURCE` honoured | live local partials, then the server's transcript replaces them |
| 2 | no partial within 1.5 s **while non-zero PCM is flowing** | recognition cancelled, recording continues, level meter only, remote-only transcript |
| 3 | `SDK_INT < 33` | as tier 2, decided up front |

🔴 `EXTRA_AUDIO_SOURCE` support is **optional per recogniser implementation**
and an implementation that ignores it does so *silently* — it just opens the
microphone. There is no capability flag, so the tier is detected at runtime.
The `wroteNonZeroPcm` term is load-bearing: without it, a user who takes more
than 1.5 s to start speaking has recognition cancelled every time.

### Fallback policy

Connect timeout 10 s; read timeout `max(20 s, 2 × audio_seconds)` capped at
90 s; **exactly one upload in flight** — client-side serialisation is the only
backpressure that exists, because the service is a synchronous handler on a
single GPU replica with no admission control.

| Response | Action |
|---|---|
| 400 / 401 / 413 / 422 / 500 | fail fast, no retry, surfaced to the user |
| 503 whose `detail` names `STT_API_TOKEN` | fail fast, **permanent** — and no `/healthz` poll, because `/healthz` is unauthenticated and would answer 200 forever |
| 502 / 503 / 504, connect-refused, timeouts | retry with jittered backoff (±30%) |
| still failing | hand over the on-device transcript **now**, then poll `GET /healthz` every 10 s for ≤120 s and make ONE final attempt on the first 200 |

🔴 **Hard ceiling: 4 uploads of one recording, ever** — there is no
server-side de-duplication or idempotency key, so a retry after a read-timeout
on a request the server actually completed burns a full GPU generation. The
ceiling is enforced by a single `UploadBudget` that every upload path spends
from, including the recovery path, so adding a path cannot quietly raise it.

> **Deliberate deviation from the original spec, stated out loud.** The spec
> asked for both "retry 3× (2 s, 6 s, 15 s)" — 4 uploads by itself — *and* a
> 4-upload hard ceiling with a `/healthz`-gated final attempt on top. Those
> cannot both hold. Resolved in favour of the ceiling: the fast ladder spends
> 3 uploads (waits 2 s, 6 s) and the 4th is **reserved** for the first
> `/healthz` 200. Keeping the full fast ladder instead would have made the
> recovery path unreachable in the exact situation it exists for. The user also
> gets the local transcript at ~8 s instead of ~23 s. See `SttRetryPolicy`.

### `language` must be a bare ISO code

🔴 The service passes `language` **unvalidated** to a processor that raises
`ValueError` *outside* its try/except, so an unaccepted value is an unhandled
**500 with a traceback**, not a 400. `Locale.getDefault().toLanguageTag()`
returns `"en-US"`, which is *not* accepted — so the obvious implementation
500s on every request from a US-English phone. `SttLanguage` maps through an
allowlist (`ar de el en es fr it ja ko nl pl pt vi zh`) and sends the bare
code; anything unmappable degrades to `en` rather than being passed through.

### Token storage

The token is encrypted with an **AES-256/GCM key held in `AndroidKeyStore`**
(non-exportable, no user-auth requirement) and the `IV + ciphertext` is stored
base64 in ordinary `SharedPreferences`.

Deliberately **not** `androidx.security:security-crypto` /
`EncryptedSharedPreferences`: that is a *runtime* AndroidX dependency and this
APK ships none (the hand-rolled `DictaFileProvider` exists for the same
reason; the dex contains zero `androidx` classes). The guarantee asked for —
encryption at rest under a key the app cannot export — is a
`KeyGenParameterSpec` away with framework APIs only. Failure is **closed**: if
the keystore is unavailable the save throws rather than falling back to
plaintext.

The token is never rendered back into the settings field, never logged, and
never put into an exception or a user-facing message.

### Cleartext HTTP, scoped to one host

`res/xml/network_security_config.xml` permits cleartext for `10.42.0.10`
**only**; `usesCleartextTraffic="true"` is deliberately **not** set, because
the blanket attribute would permit an unencrypted request to any host on any
network. The mesh traffic is already inside an encrypted Nebula tunnel, and
the service has no TLS listener a phone could validate.

## Limitations (PoC)

- The on-device recogniser may stream audio to the recognition service's
  servers; there is an on-device path (`createOnDeviceSpeechRecognizer`) on
  supported devices — not wired up here. The **remote** path goes only to the
  self-hosted service you configure.
- The bubble is killed if the system reclaims the service; reopen the app to
  restart it.
- Dictation language follows the system locale, mapped through `SttLanguage`'s
  allowlist.
- Truncation at the service's 256-tokens-per-35 s-chunk limit is **silent** —
  the response carries no finish-reason field, so the client cannot detect it
  and does not claim to.
- `KeystoreCipher` is **not** covered by the unit suite: `AndroidKeyStore` has
  no Robolectric implementation, so the real cipher is device-verified only.
  The `SecretCipher` seam is what the unit tests exercise.

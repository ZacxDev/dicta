# Dicta — voice-dictation overlay for Android (PoC)

Trigger voice dictation **from anywhere on the device** — any app, the home
screen, the lock shade — then copy the text to the clipboard or send it
somewhere. Proof of concept, built to answer: *is this possible with stock
Android APIs?* **Yes.** See [RESEARCH.md](RESEARCH.md) for the documentation
that backs each piece.

## How it works

Three surfaces reach Dicta from anywhere — two triggers, and a tile that
switches the third one on and off:

1. **Floating bubble** — an always-on-top mic button (`TYPE_APPLICATION_OVERLAY`)
   you can drag anywhere. Tap it from any app. `BubbleBootReceiver` asks for it
   again after a reboot when you had it switched on, and if the platform
   refuses that start it offers it back in a notification rather than failing
   silently (device-unverified — see the receiver's kdoc for what the
   documented rules do and do not promise).
2. **Persistent notification** — "Dicta" notification with a Dictate action.
3. **Quick Settings tile** — swipe down from any app and tap the Dicta tile to
   switch the floating bubble **on or off**. It is a stateful toggle, not a
   launcher: it reads `BubbleEnabled`, shows `STATE_ACTIVE`/`STATE_INACTIVE`
   accordingly, and goes `STATE_UNAVAILABLE` when "Display over other apps" is
   not granted.

Whether the bubble is on lives in exactly one place — `BubbleEnabled`, over the
`dicta`/`bubble_enabled` preference — so the switch in the app, the
notification's **Hide bubble** action, the tile and the boot receiver cannot
disagree about it.

The overlay is a floating dialog-style activity, and it is deliberately
**minimal at rest**: the transcript, one mic pill, and a small overflow
control. Nothing else. The four actions below live in a row that is hidden
until there is something to act on.

Three independent routes reach them, so none of them is the single point of
failure:

1. the **⋯ overflow** next to the mic, always visible, with a
   `contentDescription` and a long-press `tooltipText` that both flip between
   "More actions" and "Hide actions";
2. the row **reveals itself** the moment the transcript stops being blank — so
   after the first utterance the actions are simply on screen;
3. the status line names the route in words while the row is hidden.

- One-tap dictation via `SpeechRecognizer` (live partial results as you speak)
- Editable text field (fix anything the recognizer got wrong) — the hero of the
  card, not one row of a form
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

JUnit 4 + Robolectric, `testImplementation` only — never packaged.
`.github/workflows/tests.yml` runs both on every push and pull
request, on JDK 17. The second command exists because a Gradle task can report
success having executed **zero** tests; it reads the JUnit XML and fails on an
empty result set rather than trusting the exit status.

### Runtime dependencies

The APK has **no third-party runtime dependencies**: no AndroidX, no Material
Components. Material Components was explicitly permitted for the 2026-10 design
pass and deliberately declined — the design is a surface ramp in `colors.xml`
plus `<shape>`/`<selector>` drawables, and adopting M3 components would have
meant re-parenting `Theme.Dicta.Overlay`, whose parent is what gives the
floating card its screen margin. The reasoning is in `app/build.gradle.kts`.

This used to read "the APK stays dependency-free", which was slightly
overstated: scanning the debug APK's dex (2026-10-12) the package prefixes
present are `dev/zacx` (the app), `kotlin/*` (stdlib), `java/*` `android/*`
`dalvik/*` (platform), and `org/jetbrains/` `org/intellij/` — the
`@Nullable`/`@NotNull` annotation classes that ship *inside* kotlin-stdlib
rather than as a dependency of their own. "No third-party runtime
dependencies" is the accurate form.

On NixOS, AGP's downloaded `aapt2` is a generic-linux binary the system cannot
exec, so add the override:

```sh
./gradlew :app:testDebugUnitTest \
  -Pandroid.aapt2FromMavenOverride="$ANDROID_HOME/build-tools/34.0.0/aapt2"
```

`android.nix` provides that toolchain (Android SDK 34 + build-tools 34.0.0,
JDK 17, Gradle 8, `android-tools` for `adb`), with **nixpkgs pinned to an exact
revision** so the suite is green against a recorded toolchain rather than
against whatever channel the host happens to track:

```sh
nix-shell android.nix --run './gradlew :app:testDebugUnitTest \
  -Pandroid.aapt2FromMavenOverride="$ANDROID_HOME/build-tools/34.0.0/aapt2"'
```

Bumping the pin is a deliberate two-line edit; the file says how. Note that
`--offline` cannot resolve `androidx.test:*`, so `assembleDebugAndroidTest`
fails on dependency resolution in an offline shell — that is the environment,
not a defect. Count results from `app/build/test-results/**/*.xml`, not from
Gradle's exit code.

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
    DictaTileService.kt   # Quick Settings tile: the bubble's on/off toggle
    BubbleEnabled.kt      # the one owner of "is the bubble enabled" (+ start/stop)
    BubbleBootReceiver.kt # restores the bubble after a reboot
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
- The remote status line names the **engine**, never a verdict on the words.
  It used to read "Transcribed by the server ✓"; the tick was a claim about
  correctness the client has no basis for, and the service does fabricate
  fluent transcripts from near-silent input. There is deliberately **no
  client-side silence/energy gate**: measured against the live service, room
  tone at peak RMS 0.002023 hallucinated while quiet speech at 0.001891 and
  clean speech at 0.001825 transcribed correctly — the input that must be
  rejected is *louder* than the inputs that must be accepted, so no threshold
  separates them.
- Truncation **is** detectable and is surfaced. The client reads exactly one
  field for it — `truncated` — and `truncated: true` shows "cut off at the
  length limit" instead of the plain status. The response also carries
  `finish_reason`, but **nothing in `app/src/main` reads it**; detection
  rests on `truncated` alone. (This bullet previously said the response
  "carries no finish-reason field, so the client cannot detect it", false
  once the service grew the field and exactly the shape of claim that gets a
  working guard deleted as dead code — and it then over-corrected into
  implying both fields are consumed.)
- Fabrication is reported by the service, not judged here: the response's
  `unreliable` boolean is read and shown as "the output may not match what
  you said". The response also carries `unreliable_reasons`, which the client
  **neither reads nor displays** — a string-array reader was deliberately not
  built, because the status line does not name the reason and an unused
  reader is one more thing to keep correct. The threshold lives **server-side
  on purpose** so it can be retuned without shipping an APK, so the client
  reads the boolean and never re-derives it from `avg_logprob` /
  `no_speech_prob`.
- 🔴 Both flags are **three-valued**, and `null` means the server could not
  check — no threshold configured, or the signal a rule needs is itself null.
  The client renders `null` exactly like `false`, i.e. with no warning. So a
  server whose threshold got unset reports `unreliable: null` on every request
  and the app looks precisely as it does when all is well: a detector that
  stops working is currently **unobservable from the client**. That is a
  deliberate trade against warning-fatigue, not an oversight.

  🔴 **Nothing watches for it automatically.** `LiveServiceDeviceTest`
  asserts the live service still answers `unreliable: false` for ordinary
  speech and `true` for a 200 Hz tone, and it is the only check that would
  catch the threshold being unset — but it is an **instrumentation test run
  by hand**. CI compiles it and never runs it (`connectedAndroidTest` appears
  in no workflow; there is no device on a runner), and it needs a token
  pushed to `/data/local/tmp/dicta-stt-token` plus mesh reachability. So this
  is a **manual procedure for whoever is making a release or touching the STT
  path**, not a standing safety net: run `./gradlew :app:connectedDebugAndroidTest`
  against a connected handset before shipping a change here. Until someone
  runs it, nothing has checked the field at all.
- `KeystoreCipher` is **not** covered by the unit suite: `AndroidKeyStore` has
  no Robolectric implementation, so the real cipher is device-verified only.
  The `SecretCipher` seam is what the unit tests exercise.

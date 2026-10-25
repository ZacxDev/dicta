# Dicta — voice-dictation overlay for Android (PoC)

Trigger voice dictation **from anywhere on the device** — any app, the home
screen, the lock shade — then copy the text to the clipboard or send it
somewhere. Proof of concept, built to answer: *is this possible with stock
Android APIs?* **Yes.** See [RESEARCH.md](RESEARCH.md) for the documentation
that backs each piece.

## How it works

**One trigger: the floating bubble.** Tapping it is the only way to start a
recording.

1. **Floating bubble** — an always-on-top mic button (`TYPE_APPLICATION_OVERLAY`)
   you can drag anywhere. Tap it from any app. `BubbleBootReceiver` asks for it
   again after a reboot when you had it switched on — the one start it makes,
   with no fallback, because the platform restrictions that could refuse it
   exempt this path twice over (see the receiver's kdoc for the documented
   rules, and for why the fallback that used to live there was deleted as
   unreachable). The restore is device-unverified: a JVM test can pin the
   receiver's decision table but not Android's permission for the start.

Two surfaces *switch that bubble on and off*; neither starts a recording:

- **the app's own switch** on the setup screen, and
- **a Quick Settings tile** — swipe down from any app and tap the Dicta tile.
  It is a stateful toggle, not a launcher: it reads `BubbleEnabled.isUp` (the
  flag *and* the overlay permission), shows `STATE_ACTIVE`/`STATE_INACTIVE`
  accordingly, and goes `STATE_UNAVAILABLE` when "Display over other apps" is
  not granted. It is deliberately a *standard* tile, not an `ACTIVE_TILE`: an
  active tile is only bound on add/remove, on a tap, or for an explicit
  `requestListeningState()`, so it would show a stale reading the next tap
  would then invert. Standard mode re-renders whenever the panel is visible.
  (How the shade actually renders a tile and what TalkBack announces are
  **device-unverified**.)

Whether the bubble is on lives in exactly one place — `BubbleEnabled`, over the
`dicta`/`bubble_enabled` preference. That takes **two** rules, not one, and the
second was missing while this paragraph claimed the first was enough:

- every **write** goes through `BubbleEnabled`, so the switch in the app, the
  bubble notification's **Hide bubble** action, the tile and the boot
  receiver cannot put different values in the preference;
- everything the user is **shown** derives from `BubbleEnabled.isUp` (the flag
  *and* the overlay permission), never from the raw flag. The app's switch, the
  tile's painted state and the tile's *click direction* each used to compute
  this for themselves, and they disagreed: with the overlay permission revoked
  the tile painted from one predicate and decided its click from another.

The flag is stored **intent**, so it deliberately outlives the bubble across a
reboot or a revoked permission. The two cases where intent and reality would
otherwise come apart with the permission still granted are closed where they
happen: `BubbleService` clears the flag when the foreground promotion of a start
**the user asked for** is refused, and `BubbleBootReceiver` clears it on
`MY_PACKAGE_REPLACED`, because an app update stops the service and Android does
not restart one across a package replacement. Neither restores the bubble — they
only stop the surfaces claiming one, so a single tap brings it back.

A refused *boot* restore is the deliberate exception: it keeps the flag. Clearing
it there would mean the bubble does not return on that boot **or any later one**,
which is exactly the thing the paragraph above says a reboot must not be able to
do, so that one case leaves the flag set with no bubble until the next start.
`BubbleEnabled` owns the distinction — `startService` records whether the user
asked, and `foregroundPromotionRefused` is the only thing that reads it.

> **Removed:** a **persistent trigger notification** with a Dictate action, and
> a **"Try dictation now"** button on the setup screen. Both opened the overlay,
> which auto-starts a capture, so both were ways to start a recording that were
> not the bubble. The notification was `setOngoing(true)` and therefore outlives
> the build that posted it, so `PackageReplacedReceiver` cancels it and deletes
> its channel on the first start of a new build —
> see `TriggerNotificationCleanup`. `StartSurfaceLedgerTest` is the enumerated
> set of places a recording can begin, and it fails if that set grows.

The overlay is a floating dialog-style activity, and it is deliberately
**minimal at rest**: the transcript, one mic pill, and the four actions.
Nothing else.

> **Removed:** the **⋯ overflow** toggle, and with it both routes that existed
> only to make a hidden action row findable — the auto-reveal once the
> transcript went non-empty, and the status line naming the "⋯" route in words.
> The actions are **unconditional** now: `@id/action_row` carries no visibility
> attribute and nothing in `DictateActivity` sets one. The states where an
> action cannot do anything ("Nothing to copy yet…") are handled *by the
> action*, not by hiding it. `OverlayMinimalLayoutTest` pins the row visible in
> every transcript state, and `thereIsNoOverflowToggleLeftOnTheCard` fails if an
> `@id/more_button` is ever declared again.

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
| `POST_NOTIFICATIONS` | The bubble's foreground-service notification (it carries **Hide bubble**) and the boot-restore offer, on Android 13+ | Runtime prompt |

No root, no system app, no accessibility service needed.

## Install and updates

There is no Play Store listing. Updates arrive over the air through
[**Obtainium**](https://github.com/ImranR98/Obtainium), which watches a repo's
GitHub Releases and installs new APKs as they appear.

**Set it up once:** in Obtainium, *Add App* →

```
https://github.com/ZacxDev/dicta
```

Obtainium reads this repo's Releases, offers the newest `dicta-v<semver>.apk`,
and from then on notifies on each new release.

### Updates install in place, and that is a deliberate property

An update **replaces the installed app without uninstalling it**, so everything
in Dicta's private storage — the dictation history, the saved transcription
token, the bubble's enabled flag — survives. That works only because every
release is signed with the **same certificate** as the build already on the
handset. Android will not let a differently-signed APK replace an installed one:
it refuses with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, and the only way through
is an uninstall, which wipes the history.

Cert parity is therefore the whole point of the release pipeline, and it is
checked rather than hoped for. Two independent mechanisms:

1. `app/build.gradle.kts` configures the **debug signing config explicitly**,
   from the `dicta.debugKeystore` Gradle property (default:
   `$HOME/.android/debug.keystore`, the standard debug passwords). Without this
   AGP would **auto-generate** a keystore whenever none is present — which on a
   fresh CI runner means a brand-new key every time.
2. `scripts/assert-apk-signer.sh` verifies the **built APK's** signer
   fingerprint before `.github/workflows/release.yml` publishes anything. A
   mismatch aborts the job. This is the mechanism that matters: an auto-
   generated key produces a release that builds, signs, uploads and publishes
   entirely green, and the failure surfaces nowhere but the handset, at install
   time.

### The published APK is debuggable, on purpose

Releases publish the **debug** build type, not release. Every device-level
verification recipe this project relies on uses `adb run-as
dev.zacx.dicta …` to read the app's private files — the dictation history store,
the encrypted token — and `run-as` only works against a build whose manifest is
`debuggable`. A release-type APK would make the handset unobservable, so the
debuggable artifact is a deliberate trade, taken knowingly: the app holds a
transcription token, so a user other than the operator should weigh that
differently.

### Cutting a release

The **tree is the source of truth for the version**; the tag only has to match
it. The gate exists because the alternative — injecting the version from the tag
— would leave `app/build.gradle.kts` at its unbumped `versionCode` forever, and
Android refuses to install a lower `versionCode` over a higher one. After the
first OTA, every local `adb install` would fail as a downgrade until you
uninstalled, taking the history with it.

1. Bump both values in `app/build.gradle.kts`. `versionCode` follows
   `major*10000 + minor*100 + patch`, so `0.3.0` → `300`:

   ```kotlin
   versionCode = 300
   versionName = "0.3.0"
   ```

2. Commit it, and push to `main`. Pushing the bump publishes nothing — only a
   tag does.
3. Tag it to match, and push the tag:

   ```sh
   git tag v0.3.0 && git push origin v0.3.0
   ```

`.github/workflows/release.yml` then runs, in this order: the version gate
(`scripts/assert-version-matches-tag.py`), the full unit suite plus the
test-count floor it inherits from `tests.yml`, the signed build, the signer gate
(`scripts/assert-apk-signer.sh`), and only then `gh release create`. **Nothing
is published until both gates pass.** If a gate fails the job aborts and no
release exists — fix the tree, delete and re-push the tag.

The keystore reaches CI as the `DEBUG_KEYSTORE_B64` repository secret, decoded
to a path outside the checkout for the build and deleted afterwards. It is never
printed and never committed.

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

### A debug keystore is now required, and its absence fails loudly

`app/build.gradle.kts` declares the `debug` signing config **explicitly**, so
AGP no longer **auto-generates** `~/.android/debug.keystore` when it is missing.
A build without one stops at:

```
> Task :app:validateSigningDebug FAILED
  Keystore file '…/.android/debug.keystore' not found for signing config 'debug'
```

That is deliberate and fail-closed — see [Install and
updates](#install-and-updates). An auto-generated key is exactly how a release
gets signed with a certificate the handset will refuse, and failing at build
time is strictly better than discovering it at install time.

If you have ever built an Android app on the machine you already have the file.
Otherwise either mint one:

```sh
keytool -genkeypair -noprompt -keystore ~/.android/debug.keystore \
  -storepass android -keypass android -alias androiddebugkey \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -dname 'C=US, O=Android, CN=Android Debug'
```

…or point the build at one somewhere else:

```sh
./gradlew :app:assembleDebug -Pdicta.debugKeystore=/path/to/debug.keystore
```

A locally generated key is fine for local work, but an APK signed with it
**cannot be installed over** a build from a GitHub Release. `.github/workflows/tests.yml`
mints a throwaway key for exactly this reason: its APK is a packaging check, never
something anyone installs.

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
permissions, switch the floating bubble on and (optionally) add the Quick
Settings tile, then tap the bubble from anywhere.

## Project layout

```
app/src/main/
  AndroidManifest.xml
  java/dev/zacx/dicta/
    MainActivity.kt       # setup: permission status, the bubble switch, tile prompt, Settings link
    DictateActivity.kt    # the overlay: dictate → edit → copy / screenshot / share
    DictaTileService.kt   # Quick Settings tile: the bubble's on/off toggle
    BubbleEnabled.kt      # the one owner of "is the bubble enabled" (+ start/stop)
    BubbleBootReceiver.kt # restores the bubble after a reboot
    PackageReplacedReceiver.kt   # runs the upgrade cleanup on ACTION_MY_PACKAGE_REPLACED
    TriggerNotificationCleanup.kt # cancels the deleted trigger notification (id 3001) + its channel
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

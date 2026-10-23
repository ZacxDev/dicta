# Research: "trigger from anywhere → dictate → copy/send" on Android

**Verdict: fully possible with stock, public Android APIs. No root, no system
app, no accessibility service required.**

> 🔴 **This document records the up-front research and the PoC it justified. It
> is not a description of the shipped app, and two of its claims about the Quick
> Settings tile have since gone stale** — the tile is no longer a dictation
> launcher; it is the floating bubble's on/off switch
> (`DictaTileService`). Specifically: §2's "launched from the tile via
> `startActivityAndCollapse()`" and §4's "the tile/notification paths work
> without `SYSTEM_ALERT_WINDOW`" are both corrected in place below. The §1 table
> row is left as written: it quotes the platform guide accurately, and what
> changed is Dicta's choice, not the API.

## 1. Triggering an action from anywhere

| Mechanism | API | Notes |
|---|---|---|
| **Quick Settings tile** | `TileService` (API 24+) | Best fit. The official guide describes exactly this pattern: `onClick()` can "launch a dialog or activity" and specifically recommends `showDialog()` — which "collapses the Quick Settings panel and shows a dialog" — for actions needing "additional input or user consent", or `startActivityAndCollapse()` for interactive flows. Reachable by swiping down from any app. |
| **Floating bubble** | `SYSTEM_ALERT_WINDOW` + `WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY` (API 26+) | "Allows an app to create windows using the type `TYPE_APPLICATION_OVERLAY`, shown on top of all other apps." User grants once via Settings → Display over other apps (`Settings.ACTION_MANAGE_OVERLAY_PERMISSION`, check with `Settings.canDrawOverlays()`). Tapping the overlay can launch the dictation UI (apps with a visible window are exempt from background-activity-start restrictions — the classic chat-head pattern). |
| **Persistent notification action** | `Notification` + `PendingIntent` | A `PendingIntent` fired from a notification is an allowed background-activity-launch trigger, so the overlay opens even over other apps. |
| App widget / launcher shortcut | `AppWidgetProvider` | Home-screen only — doesn't cover "from any app", so it's a bonus, not the core. |
| Accessibility service | `AccessibilityService` | Can intercept hardware keys/gestures globally, but it's the most invasive permission and unnecessary here. Not used. |

All three of the first mechanisms were built and the research above still
describes what each API permits. 🔴 **What the PoC uses them FOR has since
narrowed to one trigger:** tapping the floating bubble is the only way to start
a recording. The Quick Settings tile is now the bubble's on/off switch rather
than a launcher, and the persistent notification action — row three, whose
background-activity-launch exemption is still accurately described — was
deleted. The row is kept because the finding is about the API, not about Dicta;
`StartSurfaceLedgerTest` is the enumerated set of places a recording can begin,
and it fails if that set grows back.

## 2. The overlay UI

Two supported approaches; the PoC uses the simpler one:

- **Dialog-themed activity** launched from the bubble via
`startActivity(FLAG_ACTIVITY_NEW_TASK)`, or from the notification's
`PendingIntent`. Feels like a popup, gets full input focus. (🔴 Originally also
"from the tile via `startActivityAndCollapse()`" — the tile no longer opens the
overlay at all; see the note at the top.)
- **Raw overlay view** added to `WindowManager` by a foreground service — more
code, needed only if the UI must stay up while the user keeps interacting
with the app underneath.

## 3. Dictation

`android.speech.SpeechRecognizer`:

- "This class provides access to the speech recognition service",
created via `createSpeechRecognizer(Context)`, "methods must be invoked only
from the main application thread", and "the application must have
`RECORD_AUDIO` permission to use this class".
- Supports **partial results** (`EXTRA_PARTIAL_RESULTS`,
`onPartialResults`) for live transcription while speaking, plus
`isRecognitionAvailable()` to check for a recognizer and
`createOnDeviceSpeechRecognizer()` for on-device recognition where supported.
- Fallback that needs no permission at all: fire
`RecognizerIntent.ACTION_RECOGNIZE_SPEECH` and let the system voice-typing UI
handle capture, reading back `EXTRA_RESULTS`.

## 4. Copy to clipboard / send somewhere

- Copy: `ClipboardManager.setPrimaryClip(ClipData.newPlainText(label, text))`
from a user interaction (button tap) — the app has focus at that point, so
Android 10+ clipboard-access restrictions don't apply.
- Send: `Intent.ACTION_SEND` with `text/plain` through `Intent.createChooser()`
— the standard share sheet reaches every installed app that accepts text.

## 5. What this deliberately does not do

- No keylogging, no reading other apps' screens or clipboards — the overlay only
ever handles text the user dictated or typed into it.
- `SYSTEM_ALERT_WINDOW` is a sensitive permission (overlay-spoofing risk), so
the bubble is strictly opt-in — and **nothing triggers a dictation without it**,
since tapping the bubble is the only way to start one. Granting the permission is
a prerequisite for dictating at all, not a convenience.
🔴 **This paragraph has been wrong twice, in opposite directions, and both errors
were counts of surfaces.** It first read "the tile/notification paths work
without it" — the tile is the bubble's switch, so it *requires* the permission:
`DictaTileService.render()` goes `Tile.STATE_UNAVAILABLE` and turns nothing on
while `canDrawOverlays` is false. It then read "**two** surfaces still work with
the permission ungranted — the notification's *Dictate* action and *Try dictation
now*". Both of those surfaces are now **deleted**, so that count is zero, and
`DictateLaunchTest` no longer lists `MainActivity` as an allowed direct
constructor of an overlay intent. The claim that held through every version is
the one left standing: nothing needing the overlay permission works without it.
The surface counts were the perishable part and are no longer stated.

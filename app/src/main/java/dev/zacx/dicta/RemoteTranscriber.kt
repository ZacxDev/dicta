package dev.zacx.dicta

import android.content.Context
import java.io.File
import java.util.Locale

/**
 * The **upload-only** half of a dictation: take a finished recording that is
 * already on disk and run the retry / fallback ladder over it.
 *
 * ## Why this is its own class and not a mode of [DictationSession]
 *
 * A stored recording has to be re-sent to the service **without ever opening
 * the microphone** (the dictation-history re-transcribe). The obvious reuse —
 * construct a [DictationSession] and simply never call `start()` — is wrong,
 * and wrong in a way that destroys user data:
 *
 *  * `DictationSession.stop()` releases [MicrophoneOwner] as its *first*
 *    statement, i.e. it releases a token it never took. Harmless today only
 *    because nothing else holds one at that moment.
 *  * `DictationSession.destroy()` **unconditionally deletes `audioFile`**. A
 *    never-started session pointed at a stored history entry would delete the
 *    user's recording.
 *
 * Both are one refactor away from mattering, and a class whose entire
 * start/stop/microphone surface must be left carefully unused is a worse seam
 * than a class that has none. So: this type owns **no** microphone — no
 * capture, no recogniser, no ownership token — and it **does not delete** the
 * [File] it is handed, because it did not create it.
 *
 * `RemoteTranscriberTest` pins both properties: behaviourally (a transcribe
 * succeeds while a *foreign* owner holds the microphone) and textually (this
 * file, comments stripped, names no microphone type).
 *
 * ## Threading
 *
 * [transcribe] blocks — it sleeps between retries and polls `/healthz`. Call it
 * on a worker thread. [Events] are delivered on the caller's thread; marshalling
 * to a UI thread (and gating a dead consumer) belongs to the caller, which is
 * what keeps [DictationSession]'s `destroyed` semantics in [DictationSession].
 */
class RemoteTranscriber(
    context: Context,
    private val settings: SttSettings,
    /** Overridable for tests; production uses the real device locale. */
    private val locale: Locale = Locale.getDefault(),
    /**
     * Builds the transport for one call. The default is the production wiring
     * and is the only thing here that touches a socket.
     *
     * Injected for the same reason [TranscriptionCoordinator] injects its own
     * seams: the ladder's behaviour is then exercisable with no network. It is
     * called **per [transcribe] call**, exactly where `SttHttp` used to be
     * constructed.
     */
    private val transportFactory: (baseUrl: String, token: String) -> Transport =
        DEFAULT_TRANSPORT_FACTORY,
    private val sleeper: (Long) -> Unit = DEFAULT_SLEEPER,
    private val unitRandom: () -> Double = { Math.random() },
    private val elapsedMs: () -> Long = { System.currentTimeMillis() },
) {

    private val appContext = context.applicationContext

    /**
     * One upload function plus the readiness probe, as the ladder needs them.
     *
     * A pair rather than an interface over `SttHttp` because the production
     * value is not an `SttHttp`: it is an `SttHttp` *wrapped* in a
     * [SingleFlightUploader], and that wrapping is load-bearing (see
     * [DEFAULT_TRANSPORT_FACTORY]).
     */
    class Transport(
        val upload: (TranscriptionCoordinator.UploadAttempt) -> SttOutcome,
        val health: () -> Boolean,
    )

    /**
     * Where the ladder's outcomes go.
     *
     * 🔴 **Why [onRemoteTranscript] carries the status instead of the status
     * arriving as its own [onStatus] call.** In the code this was extracted
     * from, the handler for a remote transcript wrapped the transcript callback
     * **and** the status callback inside ONE `postToMain { }`, so the UI
     * received the pair atomically — one `Handler` message. Splitting them into
     * two `Events` calls would make them two messages, a different interleaving,
     * and under a `destroy()` racing between them the transcript could land
     * while the status is gated. That is a behaviour change, in an extraction
     * whose whole premise is that there is none. So the unit that was atomic
     * stays one call.
     */
    interface Events {
        /** A status-line update on its own. */
        fun onStatus(text: String)

        /**
         * The server's transcript together with the status that accompanies it.
         * Delivered as ONE unit — see the kdoc above on why this is not two
         * calls.
         */
        fun onRemoteTranscript(text: String, status: String)

        /**
         * The remote path gave up.
         *
         * @param status already localised, and already the right one of
         *   "retrying" / "gave up" — that choice reads `recoveryPending`, which
         *   only exists inside the ladder.
         * @param localTranscript the on-device text to show, or `""` when there
         *   is none to show. The `isNotBlank` decision is made here so it lives
         *   in one place rather than at every consumer.
         */
        fun onFellBack(status: String, localTranscript: String)
    }

    /**
     * Runs the retry / fallback ladder for one finished recording.
     *
     * Blocking. [SingleFlightUploader] wraps the transport so the 🔴
     * exactly-one-in-flight invariant is observable rather than merely implied
     * by the caller's executor having one thread.
     *
     * @param audio a recording this class did **not** create and will **not**
     *   delete. For the history re-transcribe that file is the user's data.
     * @param localTranscript the on-device transcript to fall back to, read
     *   lazily. 🔴 Lazily on purpose: the recogniser's own final result can
     *   land *after* the ladder has started, and before the extraction this
     *   value was read at the moment the fallback was delivered rather than at
     *   the moment the upload began. A snapshot taken here would silently drop
     *   a late local transcript. Callers with none pass `{ "" }`.
     */
    fun transcribe(
        audio: File,
        audioSeconds: Double,
        localTranscript: () -> String,
        events: Events,
    ) {
        if (!settings.isRemoteConfigured) {
            events.onStatus(appContext.getString(R.string.stt_not_configured))
            return
        }
        val token = settings.tokens.token() ?: run {
            events.onStatus(appContext.getString(R.string.stt_not_configured))
            return
        }
        val bytes = try {
            audio.readBytes()
        } catch (_: Exception) {
            events.onStatus(appContext.getString(R.string.stt_no_audio))
            return
        }

        events.onStatus(appContext.getString(R.string.stt_uploading))

        val transport = transportFactory(settings.baseUrl, token)

        TranscriptionCoordinator(
            transport = transport.upload,
            health = transport.health,
            sleeper = sleeper,
            unitRandom = unitRandom,
            elapsedMs = elapsedMs,
            events = object : TranscriptionCoordinator.Events {
                override fun onRemoteTranscript(
                    text: String,
                    truncated: Boolean?,
                    unreliable: Boolean?,
                ) {
                    events.onRemoteTranscript(
                        text,
                        appContext.getString(statusLabel(truncated, unreliable)),
                    )
                }

                override fun onFellBack(reason: String, recoveryPending: Boolean) {
                    // 🔴 The user is told the text on screen came from the
                    // phone, and whether it may still be replaced. A silent
                    // swap is how a less accurate transcript gets trusted.
                    val status = appContext.getString(
                        if (recoveryPending) {
                            R.string.stt_fell_back_retrying
                        } else {
                            R.string.stt_fell_back
                        },
                        reason,
                    )
                    val local = localTranscript()
                    events.onFellBack(status, if (local.isNotBlank()) local else "")
                }

                override fun onStatus(message: String) {
                    events.onStatus(message)
                }
            },
            // 🔴 A fresh budget per call — the four-upload ceiling is
            // per-recording-attempt, and reusing one across calls would starve
            // every call after the first (including the second re-process of
            // one stored recording).
            budget = UploadBudget(),
        ).run(
            audio = bytes,
            audioSeconds = audioSeconds,
            // 🔴 The bare ISO code, never the BCP-47 tag. toLanguageTag()
            // returns "en-US" here, which the service passes unvalidated to a
            // processor that raises ValueError outside its try/except — an
            // unhandled 500.
            //
            // The user's explicit Settings choice wins over the device locale
            // (issue #7): on an es-CO handset the locale alone made English
            // dictation impossible, because the service faithfully transcribed
            // English speech as Spanish — which reads as a broken recogniser
            // rather than as a setting nobody was offered. null still means
            // "follow the device", and is still the default.
            language = SttLanguage.resolve(settings.language, locale),
        )
    }

    internal companion object {

        /**
         * Which status accompanies a remote transcript, given the server's two
         * self-reported flags.
         *
         * 🔴 **Both branches are `== true` and nothing wider.** For either
         * flag, `false` is the server saying it checked and found nothing, and
         * `null` is the server saying **nothing could be checked** — no
         * threshold configured, or the signal a rule needs is itself null.
         * Neither is a reason to warn. `!= null` or `!= false` here would warn
         * on a no-signal response, which is the ✓ defect inverted: a claim
         * about a check that did not happen. And a warning shown on every
         * dictation is one the user learns to scroll past, which costs the
         * real warnings their meaning.
         *
         * 🔴 **Precedence: `unreliable` wins over `truncated`.** A transcript
         * that may be fabricated is a worse problem than one that is merely
         * cut off — and it is the only one of the two that fires on what issue
         * #8 is about (`truncated` was measured **false** for both
         * fabrications; it is a real detector of a different fault).
         *
         * 🟢 **Known blind spot, accepted.** Because `null` and `false` render
         * identically, a server whose threshold got unset answers
         * `unreliable: null` on every request and this app looks exactly as it
         * does when all is well — a detector that stops working produces no
         * observable change anywhere in the client. That is the price of not
         * warning on a no-signal response and it is paid deliberately. The
         * only thing that notices is `LiveServiceDeviceTest`, which asserts
         * the live service still answers `false` for ordinary speech and
         * `true` for a tone. If that ever has to be observable in-app rather
         * than in a device test, this is the comment to come back to.
         *
         * One function rather than an expression inlined at the delivery site:
         * the predicate is the thing under test, and a second copy of it is a
         * second place for it to be wrong. 🔴 The engine badge
         * (`DictateActivity.showEngine`) is **not** such a copy — it answers
         * "which engine", which no flag changes; see its kdoc.
         */
        internal fun statusLabel(truncated: Boolean?, unreliable: Boolean?): Int = when {
            unreliable == true -> R.string.stt_remote_unreliable
            truncated == true -> R.string.stt_remote_truncated
            else -> R.string.stt_remote_done
        }

        /**
         * The production transport: one `SttHttp` per call, wrapped so that
         * concurrency is **observable** rather than assumed.
         */
        val DEFAULT_TRANSPORT_FACTORY: (String, String) -> Transport = { baseUrl, token ->
            val http = SttHttp(baseUrl, token)
            val serialised = SingleFlightUploader { http.transcribe(it) }
            Transport(
                upload = { serialised.upload(it) },
                health = { http.healthz() },
            )
        }

        val DEFAULT_SLEEPER: (Long) -> Unit = { ms ->
            try {
                Thread.sleep(ms)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }
}

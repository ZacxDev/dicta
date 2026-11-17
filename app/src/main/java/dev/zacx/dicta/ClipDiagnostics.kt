package dev.zacx.dicta

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.atomic.AtomicReference

/**
 * One machine-readable answer to *"why is the shared clipboard not working"*.
 *
 * ## 🔴 Why this exists: diagnosis was BLIND, measured rather than supposed
 *
 * Configuring this app end-to-end over `adb` took dozens of turns, and the
 * expensive part was not the typing — it was that **every failure looked the
 * same**. [ClipHttp] collapsed a non-200, a malformed body, an `IOException`
 * and a `RuntimeException` into one `null`, and [ClipPickerActivity] rendered
 * all four as *"usually being off-mesh"*. A refused token and a dead mesh were
 * one sentence. [ClipFetch] closes that at the source; this object is where the
 * result, and everything else that decides whether a capture can leave the
 * phone, can be **read in one call** instead of inferred from pixels.
 *
 * ## 🔴 THE TOKEN IS NEVER IN HERE — not its value, not a prefix, not a length
 *
 * [Readout] reports `tokenDecrypts` and `tokenCiphertextStored` as booleans and
 * nothing else. There is no field that could carry a byte of the credential,
 * which is a property of the TYPE rather than a rule about how it is used — a
 * field that exists is a field something eventually prints. The same holds for
 * what is written to disk: see [Outcome].
 *
 * The two booleans are deliberately separate, and the gap between them is the
 * state that cost the most to diagnose: [KeystoreCipher] fails closed, so
 * removing the lock screen or restoring onto a new handset leaves the
 * ciphertext in the preferences while `TokenStore.token()` returns null. The
 * app then reads as *unconfigured* on a phone that was configured weeks ago.
 * `tokenCiphertextStored=true, tokenDecrypts=false` says exactly that, and
 * `false/false` says "nothing was ever pasted" — one boolean cannot.
 *
 * ## Why an object, and why it also RETAINS the last outcomes
 *
 * [OverlayVisibility]'s and [ClipOutboxHealth]'s shape: the writers and the
 * reader are in one process with no reference to each other. The last flush
 * [ClipFlusher.Report] arrives here from [ClipOutboxHealth.record] — **the one
 * place every drain site already reports to**, so this adds no call site and
 * cannot disagree with the verdict the bubble renders. The last [ClipFetch]
 * arrives from the picker, which is the only surface that fetches.
 *
 * ## 🔴 PERSISTED across process restarts, and that was a correction
 *
 * The first version of this object retained both outcomes for the **process
 * lifetime only**, and said so rather than hiding it. Round 0 of this PR's
 * audit put that to the operator as a premise (Q2) and the answer was to
 * persist: the first `DIAGNOSE` after a reboot or a force-stop otherwise
 * reports `none` **in exactly the situation this surface exists to explain**,
 * and the live investigation that motivated the whole change involved repeated
 * force-stops — each of which would have wiped the evidence it was looking
 * for.
 *
 * So [recordFlush] and [recordFetch] write through to a [Store], and [install]
 * reads them back. What is persisted is **the outcome, its reason and its
 * timestamp, and nothing else** — never a clip body, never the token; see
 * [Outcome], which is the type that makes that true rather than a rule about
 * this file.
 *
 * ⚠ **Most-recent-of-each, not a history.** A ring buffer of several outcomes
 * was considered and deliberately not chosen: one flush and one fetch answer
 * the question being asked, and a log of clip-path outcomes on disk is a
 * surface to defend for a benefit nobody has asked for yet.
 *
 * ⚠ **Without an [install] there is no persistence and no hydration**, which is
 * the in-memory behaviour the tests of the pure rules rely on.
 * `ClipSeamLedgerTest.everySiteThatRecordsOrREADSDiagnosticsINSTALLSTheStore`
 * pins the set of files that must call it, so a new reader or recorder cannot
 * silently join without one.
 */
object ClipDiagnostics {

    /**
     * An outcome as it is kept: **a reason and a time, and nothing else.**
     *
     * 🔴 This type is the guarantee, not a convention. The persisted form is a
     * timestamp and one already-rendered summary string, so there is no field a
     * clip body or a credential could travel in — the same argument [Readout]
     * makes about the token. A [ClipFlusher.Report] has no body field and
     * [ClipFetch.reason] is a status code or an exception `message`, so the
     * summary cannot be made to carry one upstream either.
     *
     * @param summary the already-rendered line — `ClipFetch.reason`, or the
     *   flush counters. Secret-free by construction, asserted by
     *   `ClipDiagnosticsTest`. 🔴 **One line, and bounded** — see [sanitise]:
     *   it is normalised on the way in rather than trusted, because
     *   `ClipFetch.reason` and [ClipFlusher.Report.failure] are both built from
     *   an exception `message` and nothing upstream promises that is a single
     *   short line.
     * @param atMs wall clock at the moment it was recorded. 🔴 Reported as raw
     *   epoch milliseconds: an agent parses this, and a formatted local time
     *   would make the readout depend on the device's locale and zone.
     */
    data class Outcome(val summary: String, val atMs: Long) {

        /** `<atMs>|<summary>` — one line, parsed back by [decode]. */
        fun encode(): String = "$atMs|$summary"

        internal companion object {
            /**
             * The inverse of [encode], or null.
             *
             * 🔴 **Fails closed**: anything that does not parse becomes null,
             * which renders as [NONE] — *"nothing is recorded"*. Returning a
             * partially-read outcome would be a diagnostic reporting a fact it
             * does not have, which is the whole defect this file exists about.
             */
            fun decode(raw: String?): Outcome? {
                val at = raw?.substringBefore('|', missingDelimiterValue = "")
                    ?.toLongOrNull() ?: return null
                return Outcome(raw.substringAfter('|'), at)
            }
        }
    }

    /**
     * Where the two outcomes survive a restart.
     *
     * 🔴 An interface, and the production implementation is [PrefsStore], for
     * [ClipFlusher]'s stated reason: the rules stay assertable without a
     * device, and a test can hand in a map. It is deliberately **narrower than
     * preferences** — read one string, write one string, no delete, no listing
     * — so this file cannot grow into a second settings store.
     */
    interface Store {
        fun read(key: String): String?

        fun write(key: String, value: String)
    }

    /** [Store] over the app's one preferences file. */
    class PrefsStore(private val prefs: SharedPreferences) : Store {
        override fun read(key: String): String? = prefs.getString(key, null)

        /**
         * 🔴 `commit()` and not `apply()`. The recorded reason for a flush is
         * most valuable in exactly the case where the process is about to
         * stop — a crash, a force-stop, the system killing a background
         * service — and `apply()` is allowed to lose a write that has not
         * reached disk. This happens once a minute at most, on the thread that
         * drained the queue, and holds a few dozen bytes.
         *
         * 🔴 **CORRECTED (audit round 1, finding 2): "off the main thread" is
         * a claim about the WRITER and says nothing about the thread this
         * BLOCKS.** This kdoc used to defend `commit()` with that sentence
         * alone. The writer is indeed a worker — but [ClipOutboxHealth.record]
         * forwarded here from **inside its own monitor**, and
         * [ClipOutboxHealth.state] takes that same monitor on the MAIN thread
         * (`BubbleService`'s window build, and the debug receiver's
         * `DIAGNOSE`). So a blocking disk write on a worker could stall the
         * main thread behind it, which is not a cost `commit()` is entitled to
         * impose. `commit()` stays — the reason above is the right one — and
         * the forward now happens OUTSIDE that monitor, which is where the fix
         * belongs: see `ClipOutboxHealth.record`.
         */
        override fun write(key: String, value: String) {
            prefs.edit().putString(key, value).commit()
        }
    }

    /**
     * What is knowable about the clip path right now.
     *
     * 🔴 Flat data, no secret, and every field has a stable name because
     * `ClipDiagnosticsTest` asserts the rendered text and an agent parses it.
     */
    data class Readout(
        /** [ClipSettings.isConfigured]: a token is stored AND it decrypts. */
        val configured: Boolean,
        /** [TokenStore.hasCiphertext] — a blob is stored, decryptable or not. */
        val tokenCiphertextStored: Boolean,
        /** [TokenStore.hasToken] — the blob decrypted. NEVER the token itself. */
        val tokenDecrypts: Boolean,
        /** The configured clip-store base URL, or the default when unset. */
        val baseUrl: String,
        /**
         * Why [baseUrl] is not usable, from [ClipEndpoints.rejectionFor], or
         * null. 🔴 Non-null here is a configuration fault that no amount of
         * mesh debugging will fix.
         */
        val baseUrlRejection: String?,
        /**
         * How many captures are queued, or null when the queue could not be
         * read. 🔴 Null is NOT zero: an unreadable outbox is a failure and a
         * zero would report the opposite of it.
         */
        val queueDepth: Int?,
        /** What a surface would render right now. */
        val health: ClipOutboxHealth.State,
        /**
         * The last flush, or null when none has been recorded — **ever**, on
         * an installed store, rather than merely in this process.
         */
        val lastFlush: Outcome?,
        /** The last fetch, on the same terms as [lastFlush]. */
        val lastFetch: Outcome?,
        /**
         * [BubbleEnabled.isOn] — the user's **stored intent** for the floating
         * bubble, which is the value `CONFIGURE`'s `bubble_enabled` extra sets.
         *
         * 🔴 Stored intent and NOT `BubbleEnabled.isUp`, which is the opposite
         * of the rule for anything the user is *shown*. A caller driving this
         * door has just written the flag and needs to read back the same thing
         * it wrote; folding the overlay permission in here would report `false`
         * for a correctly-stored `true` and send an agent off to debug a write
         * that worked. The permission is a separate fact and is not claimed by
         * this field.
         */
        val bubbleEnabled: Boolean,
    ) {

        /**
         * `key: value` lines, one per fact, in a fixed order.
         *
         * 🔴 Pinned as a WHOLE normalised string by `ClipDiagnosticsTest`, not
         * matched on a word: the artifact under test is prose, and a guard on
         * words is walkable by rewording. A cosmetic edit here is meant to fail
         * that test — that is the price of a machine-readable claim.
         *
         * The two `AtMs` lines are separate keys rather than a suffix on the
         * outcome line so that a reader parsing `lastFetch:` keeps reading one
         * value, which is what the first version of this contract promised.
         */
        fun render(): String = buildString {
            appendLine("clipConfigured: $configured")
            appendLine("tokenCiphertextStored: $tokenCiphertextStored")
            appendLine("tokenDecrypts: $tokenDecrypts")
            appendLine("clipBaseUrl: $baseUrl")
            appendLine("clipBaseUrlRejection: ${baseUrlRejection ?: NONE}")
            appendLine("queueDepth: ${queueDepth?.toString() ?: UNREADABLE}")
            appendLine("outboxHealth: $health")
            appendLine("lastFlush: ${lastFlush?.summary ?: NONE}")
            appendLine("lastFlushAtMs: ${lastFlush?.atMs?.toString() ?: NONE}")
            appendLine("lastFetch: ${lastFetch?.summary ?: NONE}")
            appendLine("lastFetchAtMs: ${lastFetch?.atMs?.toString() ?: NONE}")
            // Last, after the clip block, because it is a different subsystem —
            // and appended rather than inserted so a reader that already parses
            // the lines above keeps working.
            append("bubbleEnabled: $bubbleEnabled")
        }
    }

    /** `lastFlush`/`lastFetch`/a rejection that is simply absent. */
    const val NONE: String = "none"

    /** 🔴 A queue that could not be READ. Deliberately not `0`. */
    const val UNREADABLE: String = "unreadable"

    /**
     * How much of a summary is kept. See [sanitise].
     *
     * 🔴 200 and not 20: every summary this code builds is well under it
     * (`stored=… destroyed=… kept=… cannotDrain=… why=…` with a realistic
     * failure string is about 80), so the bound exists for the pathological
     * case and must not truncate the ordinary one. A bound that fires in normal
     * operation would be a diagnostic quietly discarding the reason it exists
     * to report.
     */
    const val MAX_SUMMARY_CHARS: Int = 200

    /** What a truncated summary ends with, so a reader can see it was cut. */
    const val TRUNCATED: String = "…"

    /** Preference key holding the last flush [Outcome]. */
    const val KEY_LAST_FLUSH: String = "clip_diag_last_flush"

    /** Preference key holding the last fetch [Outcome]. */
    const val KEY_LAST_FETCH: String = "clip_diag_last_fetch"

    /**
     * 🔴 An [AtomicReference] and not a `@Volatile var`, so [install]'s
     * "hydrate only if nothing is recorded yet" is one indivisible step. See
     * [install] for the race this closes (audit round 1, finding 6).
     */
    private val flush = AtomicReference<Outcome?>(null)

    /** [flush]'s reason exactly. */
    private val fetch = AtomicReference<Outcome?>(null)

    /**
     * The last fetch as its TYPE, for the sentence on screen.
     *
     * 🔴 Separate from [fetch], and the difference is the point: a [Readout]
     * needs a reason that survives a restart, while
     * [ClipPickerActivity.emptyUnreadableText] needs to branch on *which*
     * failure it was, which a reason string cannot be turned back into. This
     * one is process-lifetime by nature, and it is never hydrated from disk.
     *
     * 🔴 **CORRECTED (audit round 1, finding 3).** This kdoc used to say *"the
     * picker always fetches before it renders, so it is never read without
     * having just been written"*, and that was FALSE in the one case the
     * sentence on screen matters most. The picker's read can **throw** —
     * `KeystoreCipher.decrypt` catches only `AEADBadTagException` and
     * `GeneralSecurityException`, while `KeyStore.load` raises `IOException`
     * and the keystore daemon raises `ProviderException` (see [SecretCipher])
     * — and `FloatingPickerActivity.load` turns a throw into
     * `loaded = false` **without** any outcome having been recorded. So this
     * field still held the PREVIOUS open's outcome: a 401 followed by a
     * throwing read rendered *"the store answered and refused the token"* for
     * an open that made no request at all.
     *
     * [beginFetch] is the fix, and it is why this field is cleared rather than
     * merely overwritten: a read that classified nothing must report nothing,
     * because an outcome from an earlier open is not evidence about this one.
     */
    @Volatile
    private var fetchOutcome: ClipFetch? = null

    @Volatile
    private var store: Store? = null

    /** Test seam: wall clock, so a recorded timestamp can be asserted. */
    @Volatile
    internal var clock: () -> Long = { System.currentTimeMillis() }

    /**
     * Points this object at the app's preferences, and **hydrates from them**.
     *
     * Idempotent and cheap to repeat; every surface that records or reads
     * diagnostics calls it first, which is what makes a cold readout report the
     * last real outcome instead of `none`.
     *
     * 🔴 Hydration never overwrites something already recorded in this
     * process: an in-memory outcome is newer than anything on disk by
     * construction, and the first thing a reader does after installing is
     * usually to record a fresh one.
     *
     * 🔴 **And that is now ATOMIC rather than merely intended (audit round 1,
     * finding 6).** This was an unsynchronized check-then-assign — `if (flush
     * == null) flush = …` — and it is reachable from three threads:
     * `BubbleService.onCreate` (main), `ClipPickerActivity.readRows` (the
     * picker's worker) and the debug receiver's `DIAGNOSE` (main, in a process
     * the service is also starting). A record landing between the check and the
     * assign was overwritten by the older disk value, which is exactly what the
     * paragraph above promises cannot happen. `compareAndSet(null, …)` makes
     * the promise the mechanism.
     */
    fun install(context: Context) = install(PrefsStore(SttSettings.prefs(context)))

    internal fun install(store: Store) {
        this.store = store
        // 🔴 compareAndSet and NOT set: the decode is allowed to lose the race
        // against a concurrent record, and must. Only `null` may be replaced.
        Outcome.decode(store.read(KEY_LAST_FLUSH))?.let { flush.compareAndSet(null, it) }
        Outcome.decode(store.read(KEY_LAST_FETCH))?.let { fetch.compareAndSet(null, it) }
    }

    /**
     * Retains [report] as the last flush, and persists its summary.
     *
     * 🔴 Called from [ClipOutboxHealth.record] and from nowhere else, so every
     * drain site feeds it without naming it: they already report there, and a
     * second call site here would be a second opinion about what the last flush
     * was. (That is a consequence of where this is called from — it is **not**
     * inherited coverage. `ClipSeamLedgerTest`'s drain-site ledger pins the
     * drain sites, not this function; the ledger that pins this file's own call
     * sites is the install one named in the class kdoc.)
     */
    fun recordFlush(report: ClipFlusher.Report) {
        record(KEY_LAST_FLUSH, flushSummary(report)) { flush.set(it) }
    }

    /** Retains [outcome] as the last fetch. See [ClipPickerActivity.readRows]. */
    fun recordFetch(outcome: ClipFetch) {
        fetchOutcome = outcome
        record(KEY_LAST_FETCH, outcome.reason) { fetch.set(it) }
    }

    /**
     * Forgets the TYPED last-fetch outcome, so the next sentence on screen
     * describes the read that is about to happen.
     *
     * 🔴 **Audit round 1's finding 3, at its source.** Called by
     * [ClipPickerActivity.readRows] before it reads, because that read can
     * throw before classifying anything — and a [fetchOutcome] left over from
     * an earlier open then words THIS open: a 401 followed by a throwing read
     * rendered *"the store answered, so you are on the mesh"* for an open that
     * never reached the socket. An absence is the only honest answer there, and
     * `null` is the branch [ClipPickerActivity.emptyUnreadableText] already has
     * for it.
     *
     * ⚠ Deliberately does NOT touch [fetch] or the persisted value. That one is
     * *"the last fetch that was ever classified"*, which is a different and
     * still-true claim — the readout says when it happened, so it cannot be
     * mistaken for this open.
     */
    fun beginFetch() {
        fetchOutcome = null
    }

    private inline fun record(key: String, summary: String, assign: (Outcome) -> Unit) {
        val outcome = Outcome(sanitise(summary), clock())
        assign(outcome)
        // 🔴 A store that throws must not take the capture path down with it.
        // A diagnostic is worth less than a flush, and a full disk or a
        // preferences file the system has locked is a real state: the outcome
        // is still held in memory, and the readout is then exactly as good as
        // it was before this was persisted at all.
        runCatching { store?.write(key, outcome.encode()) }
    }

    /**
     * The flush as one line: what was stored, destroyed, left queued, and why
     * it stopped.
     *
     * 🔴 `dropped` is spelled **destroyed** because that is what it is — the
     * outbox was the only copy, so a dropped row is text the user was told had
     * been taken and which no longer exists anywhere. The word `dropped` reads
     * like a tidy-up.
     *
     * 🔴 Rendered at RECORD time, not at read time, because this string is what
     * gets persisted. Only counters, booleans and [ClipFlusher.Report.failure]
     * reach it — there is no clip text in a `Report`.
     */
    /**
     * One line, at most [MAX_SUMMARY_CHARS] of it.
     *
     * 🔴 **Audit round 1's finding 5.** [Readout.render] promises an agent a
     * `key: value` line format and `ClipDiagnosticsTest` pins the whole
     * rendered string — but the summary reaching it was whatever an exception
     * `message` happened to be. A `message` carrying a newline (a wrapped
     * `IOException`, a stack-trace-shaped detail, a server body echoed into a
     * failure string) split `lastFlush:` into two lines, and the second one
     * parses as a key that does not exist. [Outcome.encode] has the same
     * problem one layer down: a newline in the persisted value survives the
     * restart and breaks the readout of a LATER process.
     *
     * 🔴 Bounded as well as flattened, and the bound is not hygiene: the
     * summary is persisted, so an unbounded one is an unbounded write to the
     * app's preferences file driven by a remote error string.
     *
     * ⚠ Tabs and carriage returns as well as `\n` — a guard on the one
     * character that was noticed is walkable by the next one. The existing
     * summaries are single-line and single-spaced, so this is the identity on
     * every one of them, which is what keeps the whole-string pins green.
     */
    private fun sanitise(summary: String): String {
        val flat = summary.replace(Regex("[\\r\\n\\t]+"), " ").trim()
        return if (flat.length <= MAX_SUMMARY_CHARS) {
            flat
        } else {
            flat.take(MAX_SUMMARY_CHARS - TRUNCATED.length) + TRUNCATED
        }
    }

    private fun flushSummary(r: ClipFlusher.Report): String {
        val why = r.failure ?: if (r.unreachable) "unreachable" else NONE
        return "stored=${r.stored} destroyed=${r.dropped} kept=${r.kept} " +
            "cannotDrain=${r.cannotDrain} why=$why"
    }

    fun lastFlush(): Outcome? = flush.get()

    fun lastFetch(): Outcome? = fetch.get()

    /** The last fetch's type, for the picker's sentence. See [fetchOutcome]. */
    fun lastFetchOutcome(): ClipFetch? = fetchOutcome

    /**
     * Assembles the readout.
     *
     * 🔴 **The RULES are pure of Android**, taking the settings and the queue
     * rather than a `Context`, for [ClipFlusher]'s stated reason: they are then
     * assertable without a device. [install] does take one, because the
     * persisted outcomes have to live somewhere — but it is the only thing here
     * that does, and the [Store] seam means every rule below is still testable
     * with a map. It is also why this file does **not** appear on
     * `ClipSeamLedgerTest.onlyTheDeclaredFilesTouchTheCaptureQueue` — it
     * constructs no `ClipOutboxStore`, it is handed one.
     *
     * @param outbox read with `pending()` only. 🔴 Nothing here appends or
     *   removes: a diagnostic that mutated the queue holding the only copy of
     *   a capture would be a diagnostic nobody could afford to run. A throw
     *   from the read is `queueDepth = null`, which is [UNREADABLE] and is not
     *   zero.
     * @param bubbleEnabled [BubbleEnabled.isOn], passed in rather than read
     *   here for the same reason as everything else on this signature. 🔴 **No
     *   default**, deliberately: a `false` default would make every caller that
     *   forgot it — and every test written against it — assert that the bubble
     *   is off, which is the fail-silent direction.
     */
    fun readout(
        settings: ClipSettings,
        outbox: ClipOutbox?,
        bubbleEnabled: Boolean,
    ): Readout = Readout(
        configured = settings.isConfigured,
        tokenCiphertextStored = settings.tokens.hasCiphertext,
        tokenDecrypts = settings.tokens.hasToken,
        baseUrl = settings.baseUrl,
        baseUrlRejection = ClipEndpoints.rejectionFor(settings.baseUrl),
        queueDepth = outbox?.let { runCatching { it.pending().size }.getOrNull() },
        health = ClipOutboxHealth.state(),
        lastFlush = flush.get(),
        lastFetch = fetch.get(),
        bubbleEnabled = bubbleEnabled,
    )

    /** Test seam only. */
    fun clearForTest() {
        flush.set(null)
        fetch.set(null)
        fetchOutcome = null
        store = null
        clock = { System.currentTimeMillis() }
    }
}

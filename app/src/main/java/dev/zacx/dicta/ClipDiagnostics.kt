package dev.zacx.dicta

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
 * field that exists is a field something eventually prints.
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
 * ⚠ **Process-lifetime only, and that is stated rather than discovered.**
 * Nothing here is persisted. A readout taken after a cold start reports
 * `lastFlush=none` / `lastFetch=none`, which means *"nothing has been attempted
 * since this process started"* and never *"nothing has ever worked"*. The two
 * are different facts and only the first is being claimed.
 */
object ClipDiagnostics {

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
        /** The last flush, or null when none has run in this process. */
        val lastFlush: ClipFlusher.Report?,
        /** The last fetch, or null when none has run in this process. */
        val lastFetch: ClipFetch?,
    ) {

        /**
         * `key: value` lines, one per fact, in a fixed order.
         *
         * 🔴 Pinned as a WHOLE normalised string by `ClipDiagnosticsTest`, not
         * matched on a word: the artifact under test is prose, and a guard on
         * words is walkable by rewording. A cosmetic edit here is meant to fail
         * that test — that is the price of a machine-readable claim.
         */
        fun render(): String = buildString {
            appendLine("clipConfigured: $configured")
            appendLine("tokenCiphertextStored: $tokenCiphertextStored")
            appendLine("tokenDecrypts: $tokenDecrypts")
            appendLine("clipBaseUrl: $baseUrl")
            appendLine("clipBaseUrlRejection: ${baseUrlRejection ?: NONE}")
            appendLine("queueDepth: ${queueDepth?.toString() ?: UNREADABLE}")
            appendLine("outboxHealth: $health")
            appendLine("lastFlush: ${flushSummary()}")
            append("lastFetch: ${lastFetch?.reason ?: NONE}")
        }

        /**
         * The flush as one line: what was stored, destroyed, left queued, and
         * why it stopped.
         *
         * 🔴 `dropped` is spelled **destroyed** because that is what it is —
         * the outbox was the only copy, so a dropped row is text the user was
         * told had been taken and which no longer exists anywhere. The word
         * `dropped` reads like a tidy-up.
         */
        private fun flushSummary(): String {
            val r = lastFlush ?: return NONE
            val why = r.failure ?: if (r.unreachable) "unreachable" else NONE
            return "stored=${r.stored} destroyed=${r.dropped} kept=${r.kept} " +
                "cannotDrain=${r.cannotDrain} why=$why"
        }
    }

    /** `lastFlush`/`lastFetch`/a rejection that is simply absent. */
    const val NONE: String = "none"

    /** 🔴 A queue that could not be READ. Deliberately not `0`. */
    const val UNREADABLE: String = "unreadable"

    @Volatile
    private var flush: ClipFlusher.Report? = null

    @Volatile
    private var fetch: ClipFetch? = null

    /**
     * Retains [report] as the last flush.
     *
     * 🔴 Called from [ClipOutboxHealth.record] and from nowhere else, so this
     * inherits `ClipSeamLedgerTest.everySiteThatDrainsTheOutboxCONSUMESItsReport`
     * for free: every drain site already reports there. Adding a second call
     * site here would be a second opinion about what the last flush was.
     */
    fun recordFlush(report: ClipFlusher.Report) {
        flush = report
    }

    /** Retains [outcome] as the last fetch. See [ClipPickerActivity.readRows]. */
    fun recordFetch(outcome: ClipFetch) {
        fetch = outcome
    }

    fun lastFlush(): ClipFlusher.Report? = flush

    fun lastFetch(): ClipFetch? = fetch

    /**
     * Assembles the readout.
     *
     * 🔴 **Pure of Android**, taking the settings and the queue rather than a
     * `Context`, for [ClipFlusher]'s stated reason: the rules are then
     * assertable without a device. It is also why this file does **not**
     * appear on `ClipSeamLedgerTest.onlyTheDeclaredFilesTouchTheCaptureQueue` —
     * it constructs no `ClipOutboxStore`, it is handed one.
     *
     * @param outbox read with `pending()` only. 🔴 Nothing here appends or
     *   removes: a diagnostic that mutated the queue holding the only copy of
     *   a capture would be a diagnostic nobody could afford to run. A throw
     *   from the read is `queueDepth = null`, which is [UNREADABLE] and is not
     *   zero.
     */
    fun readout(settings: ClipSettings, outbox: ClipOutbox?): Readout = Readout(
        configured = settings.isConfigured,
        tokenCiphertextStored = settings.tokens.hasCiphertext,
        tokenDecrypts = settings.tokens.hasToken,
        baseUrl = settings.baseUrl,
        baseUrlRejection = ClipEndpoints.rejectionFor(settings.baseUrl),
        queueDepth = outbox?.let { runCatching { it.pending().size }.getOrNull() },
        health = ClipOutboxHealth.state(),
        lastFlush = flush,
        lastFetch = fetch,
    )

    /** Test seam only. */
    fun clearForTest() {
        flush = null
        fetch = null
    }
}

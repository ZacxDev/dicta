package dev.zacx.dicta

/**
 * Setting the app's configuration **programmatically**: parse, validate, apply.
 *
 * 🔴 It began as the shared clipboard's door and the name still says so. It now
 * also carries [KEY_BUBBLE_ENABLED] — the floating bubble — because the cost
 * this file exists to remove is *driving pixels to set a value*, and that cost
 * is identical for a toggle on the same settings screen. The alternative was a
 * second door with its own parse shape and its own result strings, i.e. the
 * open-coded-predicate failure this repository keeps paying for.
 *
 * ## 🔴 Why this exists, measured rather than supposed
 *
 * `SettingsActivity` is `exported="false"` — correctly, since the token field
 * must not be reachable by another app — so the only way to configure this app
 * was to drive pixels: launch, scroll, tap, `input text`, scroll back, find
 * Save, tap. Three things made that cost dozens of turns, all observed:
 *
 *  * the Save button sat in the STT group far ABOVE these fields, so pressing
 *    Back or moving focus **silently discarded the typed token** (fixed on the
 *    screen itself: `@id/clip_save` and `imeOptions=actionDone`);
 *  * a blind coordinate tap during one recovery hit *"Clear clipboard token"*
 *    and deleted it;
 *  * the IME eats scroll gestures and `uiautomator`'s window search returns
 *    dead window ids, so coordinate-driven automation is unreliable by
 *    construction.
 *
 * ## 🔴 A plain Kotlin seam, with only a thin wrapper over it
 *
 * Everything decidable is decided here, in a class with no `Context`, no
 * `Intent` and no Android component — because **CI never runs a device test**
 * (`.github/workflows/tests.yml` compiles `androidTest` and runs no emulator by
 * explicit decision). A rule that lives in a `BroadcastReceiver` is a rule the
 * gate cannot see. The wrapper is `src/debug`'s receiver and does nothing but
 * turn extras into a [Request] and the [Outcome] into a result string.
 *
 * ## 🔴 It is an ENTRY point and never a STORAGE path
 *
 * The token is written through [TokenStore.putToken] — the one encrypted-at-
 * rest path — and nothing here touches [android.content.SharedPreferences]
 * directly. `SttSettings` pins *"the plaintext token is never written to the
 * preferences file under any key"* as an invariant, and
 * `ClipConfigIngressTest` re-measures it through this door — by reading the
 * on-disk preferences XML back and grepping it — rather than trusting that the
 * invariant transfers to a new entry point.
 *
 * ## 🔴 It validates through [ClipEndpoints.rejectionFor] and grows no second rule
 *
 * The settings screen rejects a base URL with that function; so does this. A
 * predicate open-coded at two sites is wrong at one of them, and here the
 * consequence is specific: the capture-loss defect of PR #49 was reached
 * through exactly this field, so a door that accepted what the screen refuses
 * would reopen it for anything driving the door.
 */
object ClipConfigIngress {

    /**
     * What the caller asked for. A `null` field means **leave it alone** —
     * which is why these are nullable rather than defaulted: "set the URL and
     * do not touch the token" has to be expressible, since the token cannot be
     * read back to re-supply it.
     */
    data class Request(
        val clipBaseUrl: String? = null,
        val clipToken: String? = null,
        val sttToken: String? = null,
        /**
         * The floating bubble, **as the caller spelled it** — see
         * [parseBubbleEnabled].
         *
         * 🔴 Kept raw rather than parsed at [parse] time, which is exactly what
         * [clipBaseUrl] does and for the same reason: [parse] has no way to
         * report a refusal, so a value it could not understand would have to be
         * either silently dropped or collapsed into `null` — "leave it alone" —
         * and an ignored instruction is the failure this whole door exists to
         * avoid. [apply] turns it into an [Outcome.Rejected] instead.
         */
        val bubbleEnabled: String? = null,
    ) {
        /** Whether this asks for anything at all. */
        val isEmpty: Boolean
            get() = clipBaseUrl == null && clipToken == null && sttToken == null &&
                bubbleEnabled == null
    }

    /** What the ingress did. Never carries a token. */
    sealed interface Outcome {

        /**
         * A machine-readable line for the caller.
         *
         * 🔴 Pinned as whole strings by `ClipConfigIngressTest`: an agent
         * branches on this, and a guard on a word would be walkable by
         * rewording it.
         */
        val message: String

        /**
         * @param applied the names of the fields that were written — the
         *   KEYS, never the values. 🔴 `clipToken` appearing here says a token
         *   was stored and says nothing about what it was.
         */
        data class Applied(val applied: List<String>) : Outcome {
            override val message: String get() = "applied: ${applied.joinToString(",")}"
        }

        /** Nothing was asked for, so nothing was written. */
        data object NothingRequested : Outcome {
            override val message: String get() = "rejected: nothing to set"
        }

        /**
         * 🔴 **Nothing at all was written.** The URL is validated before the
         * first write for `SettingsActivity.save()`'s stated reason: a refusal
         * must leave the configuration exactly as it was, so the message can
         * truthfully say nothing was saved.
         */
        data class Rejected(val reason: String) : Outcome {
            override val message: String get() = "rejected: $reason"
        }

        /**
         * The cipher threw, so the token was NOT stored.
         *
         * 🔴 Reported rather than swallowed, which is [TokenStore.putToken]'s
         * own rule: a caller told "saved" when nothing was encrypted would send
         * no token and see an unexplained 401 for ever. The exception's CLASS
         * NAME only — a cipher message could in principle quote its input.
         */
        data class EncryptionFailed(
            val exception: String,
            /**
             * 🔴 What HAD already been written when the cipher threw. A
             * partial apply is a real state — the URL is written before the
             * tokens — and reporting only the failure would tell the caller
             * nothing changed when something did.
             */
            val applied: List<String> = emptyList(),
        ) : Outcome {
            override val message: String
                get() = "failed: token not encrypted ($exception); " +
                    "applied: ${applied.joinToString(",").ifEmpty { "nothing" }}"
        }
    }

    /** Field names, so the caller, the receiver and the tests spell them once. */
    const val KEY_CLIP_BASE_URL: String = "clip_base_url"
    const val KEY_CLIP_TOKEN: String = "clip_token"
    const val KEY_STT_TOKEN: String = "stt_token"

    /**
     * 🔴 Deliberately the SAME string as `BubbleEnabled`'s preference key, so
     * the operator spells one name for one thing — the extra, the `DIAGNOSE`
     * line and the preference all read `bubble_enabled`.
     *
     * 🔴 That makes this file the second place in `app/src/main` that spells
     * the literal, which `BubbleEnabledWritersSeamTest.onlyTheOwnerTouchesTheRawPreferenceKey`
     * notices on purpose — this file is **declared** there, with the reason
     * that it names the key and cannot reach the store: `BubbleEnabled`'s
     * `PREF_FILE` / `PREF_KEY` are `private`, nothing here opens
     * `SharedPreferences` (pinned by that class's
     * `theIngressNamesTheKeyWithoutBeingAbleToReachThePreferences`), and every
     * write goes through [BubbleEnabled.Control].
     */
    const val KEY_BUBBLE_ENABLED: String = "bubble_enabled"

    /**
     * The spellings of [KEY_BUBBLE_ENABLED] that mean `true`, lower-cased.
     *
     * 🔴 An enumerated set and not `String.toBoolean()`, which maps **every**
     * unrecognised string to `false` — so `--es bubble_enabled ture` would turn
     * the bubble OFF and report success. An ingress that silently does the
     * opposite of what it was told is worse than one that refuses.
     */
    val BUBBLE_TRUE: Set<String> = setOf("true", "1", "on", "yes")

    /** [BUBBLE_TRUE]'s mirror. */
    val BUBBLE_FALSE: Set<String> = setOf("false", "0", "off", "no")

    /**
     * [raw] as a boolean, or `null` when it is not one of the accepted
     * spellings.
     *
     * Trimmed and case-insensitive because `am broadcast --es` hands over
     * whatever the shell quoted. **Blank is not accepted** — for a token a
     * blank means "clear it", which is a meaningful instruction; for a boolean
     * there is no third state to clear to, so it can only be a quoting mistake.
     */
    fun parseBubbleEnabled(raw: String): Boolean? = when (raw.trim().lowercase()) {
        in BUBBLE_TRUE -> true
        in BUBBLE_FALSE -> false
        else -> null
    }

    /** What [apply] says when [parseBubbleEnabled] could not read the value. */
    fun bubbleRejection(raw: String): String =
        "$KEY_BUBBLE_ENABLED must be one of " +
            "${(BUBBLE_TRUE + BUBBLE_FALSE).joinToString(",")}; got <$raw>"

    /** What [apply] says when the bubble cannot be turned on at all. */
    const val BUBBLE_NO_OVERLAY: String =
        "$KEY_BUBBLE_ENABLED needs the \"display over other apps\" permission"

    /**
     * Builds a [Request] from string arguments, e.g. an intent's extras.
     *
     * 🔴 A **blank** value is not a null one. `null` means "leave it alone";
     * an empty string means "clear it", which is what [TokenStore.putToken]
     * already does with a blank token and what [ClipSettings.baseUrl] already
     * does with a blank URL. Collapsing the two would make clearing the token
     * impossible through this door — and a door with no way back to the
     * default is how an operator ends up re-installing the app.
     *
     * 🔴 The STT **base URL is deliberately absent**, and the absence is a
     * decision rather than an omission: **nobody asked for programmatic STT
     * base-URL configuration.** It is typed once per install, it was not part
     * of what cost dozens of turns to set up over `adb` — that was the clip
     * pair — and every field on this door is another field an exported
     * component on a debug build can set. The STT *token* is supported because
     * it is the other half of the credential that was actually expensive.
     *
     * ⚠ **A previous version of this paragraph argued it from validation and
     * that argument was WRONG** — round 0 of this PR's audit caught it as a
     * false dichotomy, and it is corrected here rather than quietly dropped.
     * It claimed the only options were an unvalidated write or a second copy of
     * [ClipEndpoints.rejectionFor] whose message names `/v1/clips`. There is a
     * third and cheaper one: that predicate is service-agnostic except for a
     * single message naming the appended path, so **hoisting it with that path
     * as a parameter** validates both services from one rule — which is the
     * direction this file's reuse of it already argues for. So if the field is
     * ever wanted, that is the way in, and the cost is small. It is left out
     * because it is not wanted, not because it would be expensive.
     */
    fun parse(args: (String) -> String?): Request = Request(
        clipBaseUrl = args(KEY_CLIP_BASE_URL),
        clipToken = args(KEY_CLIP_TOKEN),
        sttToken = args(KEY_STT_TOKEN),
        bubbleEnabled = args(KEY_BUBBLE_ENABLED),
    )

    /**
     * Applies [request], or refuses it and writes nothing.
     *
     * Order is load-bearing and is `SettingsActivity.save()`'s, for its
     * reasons: validate first so a refusal can honestly claim nothing was
     * saved, then the URL, then the tokens — and a cipher failure on one token
     * must not roll back the other, since the two reach different services.
     *
     * 🔴 The bubble is applied **before** the URL and the tokens, and that
     * position is argued rather than incidental. It is the one other write that
     * can be *declined* at write time (the overlay permission is not knowable
     * from the request), so it has to happen while [Outcome.Rejected]'s promise
     * — "nothing at all was written" — is still true. Put last, a declined
     * bubble would have to report `Rejected` after the URL had already been
     * stored, which is the one thing that arm is documented not to do.
     *
     * @param bubble the owner of the bubble flag. 🔴 An interface and not a
     *   `Context`: the rules here stay free of Android because **CI runs no
     *   emulator**, which is this file's own founding constraint.
     */
    fun apply(
        request: Request,
        clip: ClipSettings,
        stt: SttSettings,
        bubble: BubbleEnabled.Control,
    ): Outcome {
        if (request.isEmpty) return Outcome.NothingRequested
        request.clipBaseUrl?.let { url ->
            ClipEndpoints.rejectionFor(url)?.let { return Outcome.Rejected(it) }
        }
        // 🔴 Parsed up here with the URL's validation and NOT at the write
        // below, so an unreadable value is refused before anything is stored.
        val wantsBubble = request.bubbleEnabled?.let { raw ->
            parseBubbleEnabled(raw) ?: return Outcome.Rejected(bubbleRejection(raw))
        }
        val applied = mutableListOf<String>()
        if (wantsBubble != null) {
            // 🔴 Still nothing written at this point, so this refusal keeps
            // Outcome.Rejected's "nothing at all was written" promise.
            if (!bubble.turn(wantsBubble)) return Outcome.Rejected(BUBBLE_NO_OVERLAY)
            applied += KEY_BUBBLE_ENABLED
        }
        request.clipBaseUrl?.let {
            clip.baseUrl = it
            applied += KEY_CLIP_BASE_URL
        }
        request.clipToken?.let {
            // 🔴 putToken, so the value is encrypted at rest under the
            // AndroidKeyStore key. There is deliberately no other way to get a
            // token into this app.
            try {
                clip.tokens.putToken(it)
            } catch (e: Exception) {
                return Outcome.EncryptionFailed(e.javaClass.simpleName, applied.toList())
            }
            applied += KEY_CLIP_TOKEN
        }
        request.sttToken?.let {
            try {
                stt.tokens.putToken(it)
            } catch (e: Exception) {
                return Outcome.EncryptionFailed(e.javaClass.simpleName, applied.toList())
            }
            applied += KEY_STT_TOKEN
        }
        return Outcome.Applied(applied)
    }
}

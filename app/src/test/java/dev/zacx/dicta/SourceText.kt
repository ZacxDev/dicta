package dev.zacx.dicta

/**
 * The one spelling of "read this source as CODE and not as prose".
 *
 * ## 🔴 Why this file exists: it was the same rule in three places
 *
 * `ClipSeamLedgerTest`, `SttReachabilityLedgerTest` and `TestCountFloorTest`
 * each carried their own copy of strip-raw-strings, then strip-string-literals,
 * then strip-block-comments, then strip-line-comments. Every scan in this
 * project depends on it, because **every one of those files documents, in
 * prose, the very pattern it is scanning for** — so a scan that reads comments
 * finds the word inside the paragraph explaining why it must not be there.
 *
 * The two ledger copies were not merely similar, they were the same rule spelled
 * two ways: one wrote the "not a newline" in its character class as a literal
 * line break in the Kotlin source and the other as `\n`. That is exactly the
 * shape the repository's rule is about — a predicate open-coded at N sites is
 * typically wrong at N−1 of them *in the same direction*, and unifying them is
 * what makes a disagreement audible. Round 0 of this PR's audit named the
 * triplication (D5); `ClipSeamLedgerTest` itself named it and declined to fix
 * it, which is the moment the rule says to stop and consolidate.
 *
 * ## 🔴 ORDER IS LOAD-BEARING: raw strings, then literals, then comments
 *
 * Not reasoning — measured. Comments-first, a string containing `//` (this
 * tree has several) has its own closing quote eaten by the line-comment regex;
 * the literal regex then pairs that orphaned quote with the next one it finds
 * and silently deletes real code between them. `SttReachabilityLedgerTest`'s
 * own history records that assertion being written comments-first, watched to
 * produce the wrong answer, and reordered. Raw strings go first because they
 * can contain comment delimiters.
 *
 * Whitespace is collapsed LAST in [normalise] so a scan's patterns can be
 * written with `\s*` tolerance and a pure `ktfmt` reformat cannot move any
 * count — a spurious red this project has already had once from a line-based
 * scan.
 *
 * ## ⚠ What this is NOT
 *
 * It is not a Kotlin parser. It cannot see a call reached through a wrapper, a
 * lambda, a factory or a second type, and it does not understand nesting — a
 * block-comment opener inside a line comment will confuse it. Every ledger
 * built on it says so about itself; this is the floor those ledgers stand on,
 * not a ceiling.
 *
 * 🔴 Note what this kdoc does NOT contain: a literal block-comment delimiter.
 * Kotlin block comments NEST, so writing one here would open a nested comment
 * whose close would terminate this kdoc instead, breaking the parse of
 * everything below. `TestCountFloorTest` documents that trap, and it bit this
 * file on its first compile — measured, not imagined.
 */
object SourceText {

    /** Raw strings first, then ordinary literals. Both become an empty `""`. */
    fun withoutLiterals(src: String): String = src
        .replace(Regex("\"\"\".*?\"\"\"", RegexOption.DOT_MATCHES_ALL), "\"\"")
        .replace(Regex("\"(?:\\\\.|[^\"\\\\\\n])*\""), "\"\"")

    /**
     * Block comments, then line comments. Both become a SPACE.
     *
     * 🔴 A space and not an empty string: removing a comment must not
     * juxtapose the text on either side of it into a token that was never
     * written. `TestCountFloorTest`'s copy replaced with an empty string, which
     * on this tree counts the same — but an annotation split in half by a block
     * comment (`@Te`, comment, `st`) becomes `@Test` under one and not the
     * other, and a counter that can invent the thing it counts is the failure
     * mode that file exists to prevent.
     */
    fun withoutComments(src: String): String = src
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("//.*"), " ")

    /** Code only, on one line: [withoutLiterals], [withoutComments], collapse. */
    fun normalise(src: String): String =
        withoutComments(withoutLiterals(src))
            .replace(Regex("\\s+"), " ")
            .trim()
}

package dev.zacx.dicta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A ledger over `DictateActivity.SessionCallbacks`: **every** override must
 * carry the staleness gate.
 *
 * ## Why a ledger and not six behavioural tests
 *
 * The gate is six call sites sharing one predicate, because
 * `DictationSession.Callbacks` offers no single choke point. Round 2 of `#17`
 * added it and its own comment claimed "ONE gate for every callback"; round 3
 * deleted the gate from `onClientSilenced` and **all 568 tests stayed green**
 * — the coverage the sentence claimed did not exist, and a seventh interface
 * method would have been ungated silently.
 *
 * So this asserts the RELATIONSHIP — the set of overrides equals the set of
 * gated overrides — rather than any one method's behaviour. It fails when the
 * set GROWS (a new callback with no gate) and when it SHRINKS (a gate
 * removed), which is the property that actually protects the card.
 *
 * ⚠ **What it does NOT prove:** that the gate's predicate is correct, or that
 * suppressing a callback is the right response. Those are
 * `DictateHistoryCardTest`'s job. This file only pins that no override escapes
 * the gate.
 */
class SessionCallbacksGateTest {

    private fun source(): String {
        val f = File("src/main/java/dev/zacx/dicta/DictateActivity.kt")
        assertTrue("cannot read ${f.absolutePath}", f.isFile)
        return f.readText()
    }

    /**
     * The body of `private inner class SessionCallbacks`.
     *
     * ⚠ Bounded by BRACE DEPTH, not by scanning for the next KDoc opener.
     * The earlier version matched on a four-space-indented doc-comment start
     * and held only because the following activity member happened to carry
     * one — a member added without a doc comment would have silently
     * extended the parsed body and pulled unrelated overrides into both
     * assertions. Round 4 of `#17`.
     *
     * (Kotlin nests block comments, so the marker cannot be written
     * literally in a doc comment anyway — it opens one that never closes.)
     */
    private fun callbacksBody(): String {
        val s = source()
        val start = s.indexOf("private inner class SessionCallbacks")
        assertTrue("SessionCallbacks not found — was it renamed?", start >= 0)
        val open = s.indexOf('{', start)
        var depth = 0
        var i = open
        while (i < s.length) {
            when (s[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return s.substring(start, i + 1) }
            }
            i++
        }
        throw AssertionError("unbalanced braces parsing SessionCallbacks")
    }

    @Test
    fun everyCallbackOverrideCarriesTheStalenessGate() {
        val body = callbacksBody()
        val overrides = Regex("""override fun (\w+)\(""").findAll(body)
            .map { it.groupValues[1] }
            .toList()

        assertTrue(
            "no overrides found — the parse is wrong, not the code",
            overrides.isNotEmpty(),
        )

        // An override is gated iff `if (stale()) return` is its first
        // STATEMENT.
        //
        // 🔴 Comments are stripped before matching, and that is the whole
        // point of this predicate. Round 4 of `#17` measured the naive
        // version: prefixing `// ` to the gate in `onClientSilenced` — the
        // method round 3 had just proved is behaviourally uncovered — left
        // the entire suite green, because the check matched the gate's TEXT
        // rather than its execution. Deleting the line failed; commenting it
        // out did not. A three-character edit walked the guard.
        val ungated = overrides.filter { name ->
            val at = body.indexOf("override fun $name(")
            val afterSig = body.indexOf('\n', body.indexOf('{', at))
            val firstStatement = body.substring(afterSig + 1)
                .lineSequence()
                .map { it.substringBefore("//").trim() }   // strip line comments
                .firstOrNull { it.isNotEmpty() }
                .orEmpty()
            firstStatement != "if (stale()) return"
        }

        assertEquals(
            "these SessionCallbacks overrides do not begin with the " +
                "staleness gate, so a late callback from an abandoned " +
                "session can still write the card's views: $ungated",
            emptyList<String>(),
            ungated,
        )
    }

    @Test
    fun theGatedSetMatchesTheCallbacksInterface() {
        // 🔴 SCOPE, measured rather than assumed — and NARROWER than this
        // test's name suggests. Adding an ABSTRACT method to the interface
        // does not reach this assertion at all: the build fails first,
        // because Kotlin already requires every abstract member to be
        // overridden. That direction was never this ledger's to catch.
        //
        // What it does catch is the case the compiler cannot see: a member
        // with a DEFAULT body that SessionCallbacks overrides and then stops
        // overriding, so the silent default takes over on a card the gate was
        // protecting. The gate-presence test above goes vacuous there —
        // nothing ungated, because nothing is there.
        val iface = File("src/main/java/dev/zacx/dicta/DictationSession.kt").readText()
        val ifaceBody = iface.substring(iface.indexOf("interface Callbacks"))
            .substringBefore("\n    }")

        // 🔴 ABSTRACT members only. A method with a body does not have to be
        // overridden, so counting it made this fail and blame the maintainer
        // for "not gating" something optional — measured in round 4 of `#17`
        // by adding a default method and watching the message accuse it.
        // A declaration is abstract iff its line carries no body — no `{`
        // ANYWHERE on it (a one-line default body `fun f() {}` ends with
        // `}`, so an `endsWith('{')` test misses it; that was this guard's
        // own first attempt at the fix) and no `=` expression body.
        val declared = ifaceBody.lineSequence()
            .mapNotNull { Regex("""^\s*fun (\w+)\(""").find(it)?.groupValues?.get(1)?.to(it) }
            .filterNot { (_, line) -> line.contains('=') || line.contains('{') }
            .map { (name, _) -> name }
            .toSet()

        // ⚠ The MIRROR case is still open and is stated rather than papered
        // over: a `val` added to this interface is matched by neither regex,
        // so a property-backed member would pass this ledger unnoticed. The
        // interface has only functions today; if that changes, this needs to
        // widen with it.
        assertTrue(
            "DictationSession.Callbacks has gained a property — this ledger " +
                "only tracks abstract functions and is now blind to part of " +
                "the interface",
            !Regex("""^\s+va[lr] \w+""", RegexOption.MULTILINE).containsMatchIn(ifaceBody),
        )

        val implemented = Regex("""override fun (\w+)\(""")
            .findAll(callbacksBody()).map { it.groupValues[1] }.toSet()

        assertEquals(
            "the set of gated overrides no longer matches " +
                "DictationSession.Callbacks — a method was added to the " +
                "interface and not gated here, or an override was dropped",
            declared,
            implemented,
        )
    }
}

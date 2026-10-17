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

    /** The body of `private inner class SessionCallbacks`. */
    private fun callbacksBody(): String {
        val s = source()
        val start = s.indexOf("private inner class SessionCallbacks")
        assertTrue("SessionCallbacks not found — was it renamed?", start >= 0)
        // Ends at the next top-level member of the activity.
        val end = s.indexOf("\n    /**", start).let { if (it < 0) s.length else it }
        return s.substring(start, end)
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

        // An override is gated iff `if (stale()) return` is the first
        // statement after its signature.
        val ungated = overrides.filter { name ->
            val at = body.indexOf("override fun $name(")
            val afterSig = body.indexOf('\n', body.indexOf('{', at))
            val firstLine = body.substring(afterSig + 1, body.indexOf('\n', afterSig + 1))
            !firstLine.contains("if (stale()) return")
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
        // 🔴 The SHRINK direction, and the reason this is a ledger rather
        // than a loop: if someone deletes an override entirely, the test
        // above passes vacuously (nothing ungated) while the interface's
        // default — or the next maintainer's re-added copy — goes unguarded.
        val iface = File("src/main/java/dev/zacx/dicta/DictationSession.kt").readText()
        val ifaceBody = iface.substring(iface.indexOf("interface Callbacks"))
            .substringBefore("\n    }")
        val declared = Regex("""fun (\w+)\(""").findAll(ifaceBody)
            .map { it.groupValues[1] }.toSet()

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

package dev.zacx.dicta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `{{clip}}` resolution — [SnipClip].
 *
 * The defect these pin is not hypothetical: a real catalog's last row has
 * `{{clip}}` as its ENTIRE body, so a picker that copied bodies verbatim would
 * put seven literal characters on the clipboard and the user would paste them
 * into a message and send it.
 */
class SnipClipTest {

    @Test
    fun aBodyCarryingTheVariableIsExpandedFromTheClipboard() {
        assertEquals(
            "Reply to: quince jam, please",
            SnipClip.resolve("Reply to: {{clip}}, please", "quince jam"),
        )
    }

    @Test
    fun aBodyThatIsExactlyTheVariablePassesTheClipboardThrough() {
        // The real catalog's pass-through row. Its correct behaviour is a
        // no-op on the clipboard's own contents — which is also why it is easy
        // to get wrong and never notice.
        assertEquals("zebra crossing", SnipClip.resolve(SnipClip.CLIP_VAR, "zebra crossing"))
    }

    @Test
    fun aBodyWithNoVariableIsUntouched() {
        val body = "nothing to substitute here { } {{ }} {clip}"
        assertEquals(body, SnipClip.resolve(body, "quince jam"))
    }

    @Test
    fun anEmptyClipboardDoesNotLeaveTheLiteralToken() {
        // 🔴 The direction that matters. "The clipboard was empty" must not
        // surface as `{{clip}}` in the text the user pastes, which is what a
        // `if (clip.isEmpty()) return body` short-circuit would produce.
        val resolved = SnipClip.resolve("before {{clip}} after", "")
        assertEquals("before  after", resolved)
        assertFalse(
            "the literal token survived an empty clipboard",
            resolved.contains(SnipClip.CLIP_VAR),
        )
    }

    @Test
    fun everyOccurrenceIsResolvedAndNotJustTheFirst() {
        // Upstream uses strings.ReplaceAll. A `replaceFirst` here leaves the
        // token in the middle of the pasted text, which reads as corruption.
        assertEquals(
            "a/b and a/b",
            SnipClip.resolve("{{clip}} and {{clip}}", "a/b"),
        )
    }

    @Test
    fun needsClipIsTrueExactlyWhenTheTokenIsPresent() {
        // The predicate the picker branches on so the common row costs no
        // clipboard access. It lives here so the token's spelling is in one
        // place; a call site spelling it again is the duplicate this prevents.
        assertTrue(SnipClip.needsClip("x {{clip}} y"))
        assertTrue(SnipClip.needsClip(SnipClip.CLIP_VAR))
        assertFalse(SnipClip.needsClip("x {clip} y"))
        assertFalse(SnipClip.needsClip(""))
    }
}

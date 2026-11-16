package dev.zacx.dicta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How this handset names itself in the shared clipboard.
 *
 * 🔴 **Invariant guards, not regression coverage** — [ClipDevice] is a new
 * file. Every expectation is a literal, never derived from the implementation.
 *
 * ⚠ **What this file cannot speak for:** [ClipDevice.of], which is the one
 * place `android.os.Build` is read. Under Robolectric both of those fields are
 * whatever the shadow was configured with, so a test of `of()` would assert
 * facts about the test configuration. The rules all live in [ClipDevice.name],
 * which is where the tests are.
 */
class ClipDeviceTest {

    @Test
    fun theRealDevicesInThisOperatorsFleetNameThemselvesLegibly() {
        // The Galaxy S21 is the target handset (named in AndroidManifest.xml's
        // own icon comment), so its real Build values are the case that
        // matters most.
        assertEquals("samsung-sm-g991b", ClipDevice.name("samsung", "SM-G991B"))
        assertEquals("google-pixel-7", ClipDevice.name("Google", "Pixel 7"))
    }

    @Test
    fun theBrandIsNotRepeatedWhenTheModelAlreadyCarriesIt() {
        // 🔴 Vendors differ about whether the model repeats the brand, and the
        // duplicate is the thing a human reading a clip list on another machine
        // notices first.
        assertEquals("xiaomi-13", ClipDevice.name("Xiaomi", "Xiaomi 13"))
        assertEquals("oneplus", ClipDevice.name("OnePlus", "OnePlus"))
        // …and it IS repeated when the model merely starts with the same
        // letters, because that is a different word.
        assertEquals("one-oneiric", ClipDevice.name("One", "Oneiric"))
    }

    @Test
    fun anythingOutsideLowerCaseAlphanumericsBecomesASingleHyphen() {
        // The value goes into a JSON body and is rendered on two other
        // platforms, and a model string can carry spaces, slashes, plus signs
        // and non-ASCII. Normalising once here is cheaper than three consumers
        // each deciding.
        assertEquals("acme-x1-pro", ClipDevice.name("ACME", "X1 / Pro"))
        assertEquals("acme-x-1", ClipDevice.name("acme", "++X__1++"))
        // 🔴 NON-ASCII IS DROPPED, NOT TRANSLITERATED — and the expectation
        // here is written from what the function DOES, not from what the name
        // of this test suggests it ought to do. "Äcme" becomes "cme", because
        // the rule is [a-z0-9] after lowercasing and `Ä` is neither. The first
        // draft of this test asserted "acme-13" from the intent and went red;
        // transliteration is a table this app has no business carrying, and a
        // dropped accent in a device label is legible where a dependency is
        // not. Recorded rather than silently fixed.
        assertEquals("cme-13", ClipDevice.name("Äcme", "13"))
    }

    @Test
    fun anEmptyOrUnusableBuildFallsBackRatherThanProducingAnEmptyAttribution() {
        // 🔴 The fallback is not cosmetic: an empty `device` would render as a
        // blank first line on every other device's picker, and the row would
        // read as damaged rather than as unattributed.
        assertEquals(ClipDevice.UNKNOWN, ClipDevice.name("", ""))
        assertEquals(ClipDevice.UNKNOWN, ClipDevice.name("///", "   "))
        assertEquals("android", ClipDevice.UNKNOWN)
    }

    @Test
    fun oneSideMissingStillYieldsTheOther() {
        assertEquals("pixel-7", ClipDevice.name("", "Pixel 7"))
        assertEquals("samsung", ClipDevice.name("samsung", ""))
    }

    @Test
    fun anAbsurdlyLongModelIsCutAndNeverEndsOnAHyphen() {
        // 🔴 A trailing hyphen is the reachable ugly case, and the fixture is
        // chosen so the cut lands exactly ON one. The joined string is
        // "acme-" (5) + a 29-character model = 34 characters, which OVERSHOOTS
        // the 32 budget rather than landing on it — a fixture that came out
        // exactly 32 long would never execute the truncation at all and its
        // assertion would be unreachable while production's values executed
        // it. Character 32 of the joined string is a hyphen, so this also
        // exercises the trim rather than only the take.
        val name = ClipDevice.name("ACME", "superlative-handset-mkiiii-xx")
        assertTrue(
            "the attribution is ${name.length} characters, past the " +
                "${ClipDevice.MAX_LENGTH} budget",
            name.length <= ClipDevice.MAX_LENGTH,
        )
        assertEquals("acme-superlative-handset-mkiiii", name)
    }
}

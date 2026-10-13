package dev.zacx.dicta

import android.content.Context
import android.widget.Spinner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Issue #7: the dictation language followed the device locale and nothing else.
 *
 * ## The bug, stated as behaviour
 *
 * The operator's handset is `es-CO`. `DictationSession` sent
 * `SttLanguage.fromLocale(Locale.getDefault())`, so every recording was
 * transcribed as Spanish — correctly, by a service doing exactly what it was
 * asked. There was no control anywhere in the app to say otherwise, so English
 * dictation was simply impossible on that phone, and the symptom looked like a
 * broken recogniser rather than a missing setting.
 *
 * ## What is covered here
 *
 * The whole chain, because every link was capable of being individually right
 * while the feature stayed dead:
 *
 *  1. [SttLanguage.resolve] — the override wins, and an invalid one does not;
 *  2. [SttLanguage.DISPLAY_NAMES] — a ledger against the allowlist, so a code
 *     can never reach the picker unlabelled or a label outlive its code;
 *  3. [SttSettings.language] — round-trip, and the degradation of a stale value;
 *  4. `SettingsActivity` — the picker actually **writes** the preference. A
 *     spinner that renders and persists nothing is the shape of defect this
 *     repo has already shipped twice.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DictationLanguageTest {

    private val ctx get() = RuntimeEnvironment.getApplication()

    private fun freshSettings(): SttSettings {
        val prefs = ctx.getSharedPreferences(
            "dicta_lang_test_${counter++}", Context.MODE_PRIVATE
        )
        return SttSettings(prefs, TokenStore(prefs, ReversibleCipher()))
    }

    /** Enough of a cipher to construct a [TokenStore]; tokens are not under test here. */
    private class ReversibleCipher : SecretCipher {
        override fun encrypt(plaintext: String): String = plaintext.reversed()
        override fun decrypt(blob: String): String? = blob.reversed()
    }

    // --- 1. resolve() -------------------------------------------------------

    @Test
    fun anExplicitChoiceBeatsTheDeviceLocale() {
        // The bug, as a regression test: es-CO device, user chose English.
        assertEquals(
            "the user's explicit choice must win, or issue #7 is not fixed",
            "en",
            SttLanguage.resolve("en", Locale.forLanguageTag("es-CO")),
        )
    }

    @Test
    fun noChoiceStillFollowsTheDeviceLocale() {
        // The default must not change: following the device is right for the
        // overwhelming majority, and is the picker's position 0.
        assertEquals(
            "es",
            SttLanguage.resolve(null, Locale.forLanguageTag("es-CO")),
        )
        assertEquals(
            "en",
            SttLanguage.resolve(null, Locale.forLanguageTag("en-CA")),
        )
    }

    @Test
    fun anUnsupportedOverrideDegradesToTheDeviceRatherThanReachingTheWire() {
        // 🔴 The allowlist is a hard boundary: an unknown code is an unhandled
        // 500 on the service, not a 400. A stale preference from an older build
        // must not be able to break every request.
        assertEquals(
            "hi is not in SUPPORTED, so it must not be passed through",
            "es",
            SttLanguage.resolve("hi", Locale.forLanguageTag("es-CO")),
        )
        assertEquals(
            "a BCP-47 tag is not a bare code and must not be passed through",
            "es",
            SttLanguage.resolve("en-US", Locale.forLanguageTag("es-CO")),
        )
    }

    @Test
    fun resolveNormalisesCasingAndWhitespace() {
        assertEquals("de", SttLanguage.resolve(" DE ", Locale.forLanguageTag("es-CO")))
    }

    @Test
    fun resolveNeverReturnsSomethingOutsideTheAllowlist() {
        // The property, not a sample: whatever goes in, what comes out is
        // sendable. Includes the junk a corrupt preference could hold.
        val inputs = listOf(
            null, "", "   ", "en", "EN", "en-US", "hi", "zz", "!!", "es_CO", "123",
        )
        for (input in inputs) {
            val out = SttLanguage.resolve(input, Locale.forLanguageTag("es-CO"))
            assertTrue(
                "resolve(${input?.let { "\"$it\"" }}) = \"$out\", which is not " +
                    "in SUPPORTED — that is an unhandled 500 on the service",
                out in SttLanguage.SUPPORTED,
            )
        }
    }

    // --- 2. the label ledger ------------------------------------------------

    @Test
    fun everySupportedLanguageHasExactlyOneLabelAndViceVersa() {
        // 🔴 A ledger pinning a RELATIONSHIP between two lists, failing when
        // either grows or shrinks. Adding a code to SUPPORTED without a label
        // would put a blank row in the picker; a label with no code would offer
        // a choice that cannot be sent.
        assertEquals(
            "SttLanguage.DISPLAY_NAMES and SttLanguage.SUPPORTED have diverged",
            SttLanguage.SUPPORTED,
            SttLanguage.DISPLAY_NAMES.keys,
        )
    }

    @Test
    fun noLabelIsBlankOrADuplicate() {
        for ((code, label) in SttLanguage.DISPLAY_NAMES) {
            assertTrue("the label for \"$code\" is blank", label.isNotBlank())
        }
        assertEquals(
            "two languages share a label, so the picker cannot be read",
            SttLanguage.DISPLAY_NAMES.size,
            SttLanguage.DISPLAY_NAMES.values.toSet().size,
        )
    }

    // --- 3. persistence -----------------------------------------------------

    @Test
    fun theLanguageRoundTripsAndDefaultsToFollowingTheDevice() {
        val settings = freshSettings()
        assertNull(
            "the default must be null — 'follow the device' — not a frozen code",
            settings.language,
        )

        settings.language = "en"
        assertEquals("en", settings.language)

        settings.language = null
        assertNull("setting null must return to following the device", settings.language)
    }

    @Test
    fun aStoredValueOutsideTheAllowlistReadsBackAsFollowTheDevice() {
        val settings = freshSettings()
        settings.language = "hi"
        assertNull(
            "an unsupported code must not survive a write; it would be a 500",
            settings.language,
        )
    }

    @Test
    fun aStaleValueWrittenDirectlyIntoThePreferencesIsIgnored() {
        // The setter guards the write, but a preference file written by an
        // older build (or edited) bypasses it entirely. The GETTER must filter
        // too — this is the half that a setter-only guard misses.
        val prefs = ctx.getSharedPreferences(
            "dicta_lang_stale_${counter++}", Context.MODE_PRIVATE
        )
        prefs.edit().putString(SttSettings.KEY_LANGUAGE, "klingon").commit()
        val settings = SttSettings(prefs, TokenStore(prefs, ReversibleCipher()))
        assertNull(
            "a stale/hand-written preference reached the getter unfiltered",
            settings.language,
        )
    }

    // --- 4. the picker actually writes --------------------------------------

    /**
     * 🔴 The seam that makes the feature real.
     *
     * Every piece above can be correct while the screen writes nothing — that
     * is precisely the "a field exists but no consumer branches on it" defect.
     * This drives the real `SettingsActivity`, moves the real spinner, taps the
     * real Save button, and reads the value back out of a fresh [SttSettings]
     * built over the app's own preferences file.
     */
    @Test
    fun choosingALanguageInSettingsAndSavingPersistsIt() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get()
        val spinner = activity.findViewById<Spinner>(R.id.stt_language)

        assertTrue(
            "the picker offers ${spinner.adapter.count} rows; it must offer " +
                "'follow the device' plus every supported language",
            spinner.adapter.count == SttLanguage.SUPPORTED.size + 1,
        )
        assertEquals(
            "position 0 must be 'follow the device', which is the default",
            0,
            spinner.selectedItemPosition,
        )

        // Pick a language that is NOT the device's, so a pass cannot come from
        // the locale coincidentally matching.
        val deviceCode = SttLanguage.fromLocale(Locale.getDefault())
        val target = SttLanguage.SUPPORTED.first { it != deviceCode }
        val targetLabel = SttLanguage.DISPLAY_NAMES.getValue(target)
        val position = (0 until spinner.adapter.count)
            .first { spinner.adapter.getItem(it) == targetLabel }
        spinner.setSelection(position)

        activity.findViewById<android.widget.Button>(R.id.stt_save).performClick()

        val reread = SttSettings.create(activity)
        assertEquals(
            "the language picker did not persist — the control renders and " +
                "moves but changes nothing",
            target,
            reread.language,
        )
        assertNotEquals(
            "the test picked the device's own language, so it could not have " +
                "distinguished a working picker from an inert one",
            deviceCode,
            target,
        )

        // Leave the shared preferences file as we found it: SttSettings.create
        // uses one fixed name, so a leftover value would leak into other tests.
        reread.language = null
        controller.pause().stop().destroy()
    }

    private companion object {
        var counter = 0
    }
}

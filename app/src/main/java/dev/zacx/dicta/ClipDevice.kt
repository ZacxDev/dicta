package dev.zacx.dicta

/**
 * How this handset names itself in the shared clipboard.
 *
 * Device attribution per row is requirement 1 of the design, and the desktop
 * renders it beside the body — so this string is read by a human on another
 * machine and has to be short and recognisable, not exhaustive.
 *
 * ## 🔴 Pure, with the platform values passed in
 *
 * `android.os.Build`'s fields are static and Robolectric's shadow of them is
 * configured per-test-class, so a function reading them directly could only be
 * asserted against whatever that configuration happened to be. Taking them as
 * parameters makes the normalisation — which is the part with rules in it —
 * assertable against literal expected strings. [ClipDevice.of] is the one place
 * the `Build` fields are read, and it is three lines with no branches.
 */
object ClipDevice {

    /** The longest attribution this will emit. */
    const val MAX_LENGTH: Int = 32

    /** Used when the platform reports nothing usable. */
    const val UNKNOWN: String = "android"

    /**
     * A short, lower-case, hyphenated name from a manufacturer and a model.
     *
     * Examples, which is what the test pins:
     *
     * | manufacturer | model | result |
     * |---|---|---|
     * | `samsung` | `SM-G991B` | `samsung-sm-g991b` |
     * | `Google` | `Pixel 7` | `google-pixel-7` |
     * | `Xiaomi` | `Xiaomi 13` | `xiaomi-13` |
     * | `` | `` | `android` |
     *
     * 🔴 The manufacturer is dropped when the model already starts with it, so
     * `Xiaomi`/`Xiaomi 13` is not `xiaomi-xiaomi-13`. Vendors differ about
     * whether the model repeats the brand, and the duplicate is the thing a
     * human reading a clip list notices first.
     *
     * 🔴 Lower-cased and reduced to `[a-z0-9-]`. The value goes into a JSON
     * body and is rendered on two other platforms; a model string can carry
     * spaces, slashes, plus signs and non-ASCII, and normalising once here is
     * cheaper than three consumers each deciding what to do with them.
     */
    fun name(manufacturer: String, model: String): String {
        val brand = slug(manufacturer)
        val device = slug(model)
        val joined = when {
            device.isEmpty() -> brand
            brand.isEmpty() -> device
            device.startsWith("$brand-") || device == brand -> device
            else -> "$brand-$device"
        }
        if (joined.isEmpty()) return UNKNOWN
        return joined.take(MAX_LENGTH).trim('-')
    }

    /**
     * The one place `android.os.Build` is read.
     *
     * Not covered by a unit test and deliberately so: it asserts nothing this
     * project can speak for, because under Robolectric both fields are
     * whatever the shadow was configured with. [name] is where the rules are
     * and where the tests are.
     */
    fun of(): String = name(android.os.Build.MANUFACTURER ?: "", android.os.Build.MODEL ?: "")

    private fun slug(raw: String): String =
        raw.lowercase()
            .map { if (it in 'a'..'z' || it in '0'..'9') it else '-' }
            .joinToString("")
            .split('-')
            .filter { it.isNotEmpty() }
            .joinToString("-")
}

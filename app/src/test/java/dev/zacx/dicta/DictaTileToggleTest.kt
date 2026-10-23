package dev.zacx.dicta

import android.content.Context
import android.service.quicksettings.Tile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

/**
 * The Quick Settings tile as the bubble's **toggle**.
 *
 * Three things the old tile got wrong and this pins:
 *
 *  1. Its state was the constant [Tile.STATE_INACTIVE], assigned in
 *     `onStartListening` regardless of anything. A tile whose state is a
 *     literal cannot be wrong in a way a test notices — which is why
 *     [onStartListeningReflectsThePersistedState] below was mutation-tested by
 *     re-hardcoding that constant.
 *  2. It opened the dictation overlay, which is no longer a surface a recording
 *     starts from.
 *  3. Nothing stopped it starting an overlay service with no overlay
 *     permission.
 *
 * ## Where the [Tile] under test comes from
 *
 * `TileService.qsTile` is normally handed over by the system. Robolectric's
 * `ShadowTileService` creates one itself and `getQsTile` is shadowed to return
 * *that* object, so the tile has to be read back from the service rather than
 * injected — a `Tile` constructed here and written into the real `mTile` field
 * is simply never seen, which is how the first version of this test went green
 * on an untouched object of its own. `ShadowTile.updateTile()` is a no-op, so
 * the assertions read the fields the service set.
 *
 * [theFixtureActuallyGivesTheServiceATile] is the positive control: with a null
 * `qsTile`, `render()` returns early and every state assertion here would be
 * reading a default, which is indistinguishable from a tile the service
 * refused to change.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DictaTileToggleTest {

    private lateinit var service: DictaTileService

    /** The tile the service itself will see — see this class's kdoc. */
    private val tile: Tile get() = service.qsTile

    @Before
    fun setUp() {
        service = Robolectric.buildService(DictaTileService::class.java).create().get()
    }

    /**
     * Takes the tile away, as the system does once the shade closes.
     *
     * Reaches into `ShadowTileService`'s own field because that is where the
     * shadowed `getQsTile` reads from; nulling the real `mTile` would have no
     * effect at all.
     */
    private fun removeTile() {
        val shadow: Any = org.robolectric.shadow.api.Shadow.extract(service)
        val field = shadow.javaClass.getDeclaredField("tile")
        field.isAccessible = true
        field.set(shadow, null)
    }

    private fun prefs() = RuntimeEnvironment.getApplication()
        .getSharedPreferences("dicta", Context.MODE_PRIVATE)

    private fun setFlag(on: Boolean) = prefs().edit().putBoolean("bubble_enabled", on).commit()

    // Read off the APPLICATION shadow, not the service's: Robolectric attaches
    // the application as a built service's base context, so that is where a
    // startService/stopService from inside the TileService is recorded.
    private fun startedService() = shadowOf(RuntimeEnvironment.getApplication())
        .nextStartedService

    private fun stoppedService() = shadowOf(RuntimeEnvironment.getApplication())
        .nextStoppedService

    @Test
    fun theFixtureActuallyGivesTheServiceATile() {
        // 🔴 Positive control for every assertion below, which all read a Tile
        // the fixture injected. If qsTile were null, render() would return
        // early and the state assertions would be reading an untouched object
        // — indistinguishable from a tile the service refused to change.
        ShadowSettings.setCanDrawOverlays(true)
        setFlag(false)
        service.onStartListening()
        assertNotNull("the fixture produced no Tile at all", service.qsTile)
        assertEquals(
            "the service never wrote the tile's label, so render() did not run " +
                "and nothing below is evidence about the tile",
            RuntimeEnvironment.getApplication().getString(R.string.tile_label),
            tile.label,
        )
    }

    @Test
    fun onStartListeningReflectsThePersistedState() {
        ShadowSettings.setCanDrawOverlays(true)

        setFlag(true)
        service.onStartListening()
        assertEquals(
            "the tile read INACTIVE with the bubble enabled. onStartListening " +
                "used to assign Tile.STATE_INACTIVE unconditionally; it must " +
                "report what BubbleEnabled actually holds.",
            Tile.STATE_ACTIVE,
            tile.state,
        )

        setFlag(false)
        service.onStartListening()
        assertEquals(
            "the tile read ACTIVE with the bubble disabled",
            Tile.STATE_INACTIVE,
            tile.state,
        )
    }

    @Test
    fun clickingWithTheBubbleOffStartsItAndTheTileGoesActive() {
        ShadowSettings.setCanDrawOverlays(true)
        setFlag(false)

        service.onClick()

        val started = startedService()
        assertNotNull("the tile started no service with the bubble off", started)
        assertEquals(
            "the tile started something other than BubbleService — it used to " +
                "open DictateActivity, which is no longer a start surface",
            BubbleService::class.java.name,
            started.component?.className,
        )
        assertTrue("the tile did not persist the bubble as on", BubbleEnabled.isOn(service))
        assertEquals(
            "the tile did not repaint itself ACTIVE after turning the bubble on",
            Tile.STATE_ACTIVE,
            tile.state,
        )
    }

    @Test
    fun clickingWithTheBubbleOnStopsItAndTheTileGoesInactive() {
        ShadowSettings.setCanDrawOverlays(true)
        setFlag(true)

        service.onClick()

        val stopped = stoppedService()
        assertNotNull("the tile stopped no service with the bubble on", stopped)
        assertEquals(
            "the tile stopped something other than BubbleService",
            BubbleService::class.java.name,
            stopped.component?.className,
        )
        assertNull(
            "the tile STARTED a service while turning the bubble off",
            startedService(),
        )
        assertFalse("the tile did not persist the bubble as off", BubbleEnabled.isOn(service))
        assertEquals(
            "the tile did not repaint itself INACTIVE after turning the bubble off",
            Tile.STATE_INACTIVE,
            tile.state,
        )
    }

    @Test
    fun withoutTheOverlayPermissionTheTileIsUnavailableAndStartsNothing() {
        ShadowSettings.setCanDrawOverlays(false)
        setFlag(false)

        service.onStartListening()
        assertEquals(
            "with no overlay permission the bubble cannot exist, so the tile " +
                "must be UNAVAILABLE rather than offer a toggle that fails",
            Tile.STATE_UNAVAILABLE,
            tile.state,
        )

        service.onClick()
        assertNull(
            "the tile started a service with no overlay permission. " +
                "BubbleService stops itself immediately in that state, so the " +
                "user gets a foreground-service notification that flickers and " +
                "no bubble.",
            startedService(),
        )
        assertFalse(
            "the tile persisted the bubble as on despite having no overlay " +
                "permission, so the app's switch would read ON with nothing " +
                "running — exactly the disagreement issue #14 reported",
            BubbleEnabled.isOn(service),
        )
        assertEquals(
            "the tile left itself clickable-looking after a refused click",
            Tile.STATE_UNAVAILABLE,
            tile.state,
        )
    }

    @Test
    fun theUnavailableTileSaysWhyInTheOnlyTextATileHas() {
        // A TileService has no screen to toast on, so the reason has to live on
        // the tile. Asserting the whole normalised string, not a keyword:
        // another state's wording could satisfy a word-match.
        ShadowSettings.setCanDrawOverlays(false)
        service.onStartListening()
        val expected = RuntimeEnvironment.getApplication()
            .getString(R.string.tile_needs_overlay)
        assertEquals(
            "the unavailable tile's subtitle did not say why it is unavailable",
            expected,
            tile.subtitle?.toString(),
        )
        assertEquals(
            "the unavailable tile's content description did not say why — this " +
                "is what TalkBack reads, and below API 29 it is the only text",
            expected,
            tile.contentDescription?.toString(),
        )
    }

    @Test
    fun aMissingTileIsNotACrash() {
        // The system can take qsTile away between callbacks; render() must
        // tolerate it rather than throw inside a system callback.
        ShadowSettings.setCanDrawOverlays(true)
        setFlag(false)
        removeTile()
        service.onStartListening()
        service.onClick()
        assertTrue(
            "the click with no tile still has to do the work the user asked for",
            BubbleEnabled.isOn(service),
        )
    }
}

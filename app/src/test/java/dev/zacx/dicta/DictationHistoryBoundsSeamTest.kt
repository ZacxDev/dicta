package dev.zacx.dicta

import android.content.Context
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 🔴 The seam guard: the store the APP builds enforces the REAL policy.
 *
 * [DictationHistoryStore]'s retention bounds are injectable, because driving
 * eviction through [DictationHistoryStore.add] at the production
 * [HistoryRetention.MAX_ITEMS] would mean ~1001 real file copies and ~1001
 * full-index rewrites per test. Everything in [DictationHistoryStoreTest]
 * therefore exercises the eviction *mechanism* at five items.
 *
 * That leaves a seam no other test owns. [HistoryRetentionTest] pins the
 * policy numbers; [DictationHistoryStoreTest] pins that the mechanism works at
 * whatever bounds it is handed. Neither can see the default the store actually
 * ships with — so the shipped app could retain 5 items, or 5 bytes, or a day,
 * and the whole suite would stay green. This class is the only thing standing
 * between "the mechanism is tested" and "the product is correct", which is
 * exactly the shape of defect that lives in a seam: both sides hermetically
 * tested, the relationship between them owned by nobody.
 *
 * ## Read, never restated
 *
 * The expectations below are [HistoryRetention]'s own constants, not a second
 * copy of the literals. A guard that restated `1000` would have to be edited
 * in lockstep with any policy change and would fail for the wrong reason when
 * it was not — and the thing worth pinning is the *relationship* (store
 * default == policy), not the number. The literals themselves are pinned once,
 * in [HistoryRetentionTest.theBoundsAreTheBriefsNumbers], so a policy change
 * is still a visible decision in exactly one place.
 *
 * ## Through the production constructor
 *
 * Built via `DictationHistoryStore(Context)` — the call
 * [DictationSession] actually makes — so this reads the bounds production gets
 * rather than the bounds a test-only constructor defaults to. The two are the
 * same expression today; asserting the production path is what keeps them so.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DictationHistoryBoundsSeamTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    /** The bounds the app's own store gets, with no test ever having a say. */
    private val production: HistoryRetention.Bounds
        get() = DictationHistoryStore(ctx).bounds

    @Test
    fun theProductionStoresItemBoundIsTheRealPolicy() {
        assertEquals(
            "the shipped store's maxItems has DRIFTED from " +
                "HistoryRetention.MAX_ITEMS. Every eviction test runs at " +
                "injected small bounds, so nothing else in this suite can see " +
                "it: the app would silently keep a different number of " +
                "recordings than the policy says, and stay green.",
            HistoryRetention.MAX_ITEMS,
            production.maxItems,
        )
    }

    @Test
    fun theProductionStoresByteBoundIsTheRealPolicy() {
        assertEquals(
            "the shipped store's maxBytes has DRIFTED from " +
                "HistoryRetention.MAX_BYTES. This is the bound that became " +
                "REACHABLE at 2 GiB, so the drift is a production code path, " +
                "not a theoretical one: too low and long dictations are " +
                "deleted the user cannot explain, too high and history grows " +
                "past what the phone has free.",
            HistoryRetention.MAX_BYTES,
            production.maxBytes,
        )
    }

    @Test
    fun theProductionStoresAgeBoundIsTheRealPolicy() {
        assertEquals(
            "the shipped store's maxAgeMs has DRIFTED from " +
                "HistoryRetention.MAX_AGE_MS. Age is the absolute bound — the " +
                "one the user cannot see working — so a drift here either " +
                "deletes recordings early with no explanation or holds the " +
                "user's voice longer than the policy admits to.",
            HistoryRetention.MAX_AGE_MS,
            production.maxAgeMs,
        )
    }

    @Test
    fun allThreeBoundsAreTheRealPolicyAsOneValue() {
        // The three above name which bound drifted, which is what a failure
        // needs to say. This one pins the whole value, so a Bounds that is
        // somehow unequal while every component compares equal cannot slip
        // through, and so the guard survives someone reworking the accessors.
        assertEquals(
            "the shipped store's bounds are not HistoryRetention's policy",
            HistoryRetention.Bounds(
                HistoryRetention.MAX_ITEMS,
                HistoryRetention.MAX_BYTES,
                HistoryRetention.MAX_AGE_MS,
            ),
            production,
        )
    }

    @Test
    fun boundsHoldsExactlyTheThreeBoundsThisGuardChecks() {
        // 🔴 The ledger. The four assertions above cover maxItems, maxBytes
        // and maxAgeMs BY NAME; a fourth bound added to Bounds would be
        // defaulted by its author and checked by nobody — the guard would read
        // as full coverage of "the store's bounds" while covering 3 of 4.
        // Fails when the set grows OR shrinks, so it cannot be satisfied by
        // renaming either.
        val declared = HistoryRetention.Bounds::class.java.declaredFields
            .filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .sorted()

        assertEquals(
            "HistoryRetention.Bounds no longer holds exactly the three bounds " +
                "this class asserts by name. If a bound was ADDED, add an " +
                "assertion for it here — an unchecked default is invisible to " +
                "the whole suite. If one was REMOVED or RENAMED, the " +
                "corresponding assertion above is now pinning nothing.",
            listOf("maxAgeMs", "maxBytes", "maxItems"),
            declared,
        )
    }
}

package dev.zacx.dicta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * [SnipUsageStore]'s persistence — the stamp, the prune and the failure
 * posture.
 *
 * All **invariant guards**, not regression coverage: the class arrives with this
 * change, so there is no tree on which they could be watched going red. Red-at-
 * base for the feature itself is [SnipUsageOrderTest] and
 * `SnipPickerActivityTest`; the mutant batch in
 * `scripts/mutants/snip-mru.tsv` is what shows these can go red at all.
 *
 * A real temp directory rather than a mocked filesystem, for the reason
 * `SnipStoreTest` gives: the write is a temp-file-then-rename and the thing most
 * likely to be wrong about it is the rename, which a mock cannot see.
 *
 * 🔴 Robolectric is REQUIRED here, not stylistic, and the way this file first
 * failed is the argument. `org.json.JSONObject` is a `throw new
 * RuntimeException("Stub!")` in the plain JVM unit-test classpath, and
 * [SnipUsageStore.recordAndPrune] swallows every exception by design — so
 * without the runner each write threw, was caught, wrote nothing, and the tests
 * failed with `expected:<{ssh-prod=4242}> but was:<{}>`: an empty map that looks
 * exactly like a logic bug in the prune. The failure posture that makes the
 * production path safe is the same thing that hides a misconfigured test, so
 * the runner is load-bearing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SnipUsageStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(now: () -> Long = { 1_000L }) =
        SnipUsageStore(tmp.root, now)

    private fun usageFile() = File(tmp.root, SnipUsageStore.FILE)

    @Test
    fun aFreshStoreReportsNoUsage() {
        assertEquals(
            "with no file on disk the store must report no usage, which is " +
                "what makes a fresh install browse in catalog order",
            emptyMap<String, Long>(),
            store().lastUsed(),
        )
    }

    @Test
    fun aPickIsStampedWithTheInjectedClockAndReadsBack() {
        store { 4_242L }.recordAndPrune("ssh-prod", setOf("ssh-prod", "kubeconfig"))
        assertEquals(
            "the stamp must be the clock's value, not the file mtime",
            mapOf("ssh-prod" to 4_242L),
            store().lastUsed(),
        )
    }

    @Test
    fun aSecondPickOfTheSameLabelMovesItsStampForward() {
        val live = setOf("ssh-prod")
        store { 1_000L }.recordAndPrune("ssh-prod", live)
        store { 5_000L }.recordAndPrune("ssh-prod", live)
        assertEquals(
            "re-picking a snippet must overwrite its stamp, or the ordering " +
                "would freeze at whenever it was FIRST used",
            mapOf("ssh-prod" to 5_000L),
            store().lastUsed(),
        )
    }

    @Test
    fun aPickKeepsTheStampsOfOtherLiveLabels() {
        val live = setOf("a", "b")
        store { 1_000L }.recordAndPrune("a", live)
        store { 2_000L }.recordAndPrune("b", live)
        assertEquals(
            "recording one pick must merge into the existing map, not replace " +
                "it — a store that overwrote would only ever remember the last " +
                "snippet used",
            mapOf("a" to 1_000L, "b" to 2_000L),
            store().lastUsed(),
        )
    }

    @Test
    fun aLabelAbsentFromTheLiveCatalogIsPrunedOnTheNextPick() {
        store { 1_000L }.recordAndPrune("departed", setOf("departed"))
        assertEquals(
            "precondition: the departed label is stored",
            setOf("departed"),
            store().lastUsed().keys,
        )

        // The catalog was re-imported and no longer contains `departed`.
        store { 2_000L }.recordAndPrune("kept", setOf("kept"))

        assertEquals(
            "a label the catalog no longer carries must be dropped, which is " +
                "what bounds this file by the catalog rather than by time",
            mapOf("kept" to 2_000L),
            store().lastUsed(),
        )
    }

    @Test
    fun theStoreSurvivesACorruptFileByReportingNoUsage() {
        usageFile().writeText("{ this is not json")
        assertEquals(
            "a damaged usage file must degrade to catalog-order browse, never " +
                "make the picker look broken — this file is an optimisation, " +
                "not a gate",
            emptyMap<String, Long>(),
            store().lastUsed(),
        )
    }

    @Test
    fun theStoreSurvivesAFileWhoseShapeIsWrong() {
        // Valid JSON, wrong structure — the key holds a number, not an object.
        usageFile().writeText("""{"last_used_ms": 7}""")
        assertEquals(
            "valid JSON of the wrong shape must degrade the same way a corrupt " +
                "file does",
            emptyMap<String, Long>(),
            store().lastUsed(),
        )
    }

    @Test
    fun aNonPositiveStampIsTreatedAsNeverUsedRatherThanAsNineteenSeventy() {
        // 🔴 Reachable from a truncated write or a hand-edited file. Keeping a
        // zero would sort that row AHEAD of the never-used tail, which is a
        // wrong order rather than a missing one.
        usageFile().writeText("""{"last_used_ms": {"zero": 0, "neg": -5, "ok": 90}}""")
        assertEquals(
            "a non-positive stamp must be dropped on read so the row falls " +
                "into the never-used tail",
            mapOf("ok" to 90L),
            store().lastUsed(),
        )
    }

    @Test
    fun anEmptyLabelIsNotRecorded() {
        store { 1_000L }.recordAndPrune("", setOf("a"))
        assertFalse(
            "an empty label is not a snippet and must not reach the file; " +
                "SnipCatalog already refuses one, so this is defence in depth",
            usageFile().isFile && store().lastUsed().containsKey(""),
        )
    }

    @Test
    fun theWriteLeavesNoTemporaryFileBehind() {
        store { 1_000L }.recordAndPrune("a", setOf("a"))
        assertTrue("the usage file must exist after a pick", usageFile().isFile)
        assertFalse(
            "the scratch file must be renamed away, not left beside the real " +
                "one — a leftover .tmp is how a half-written file gets read next time",
            File(tmp.root, "${SnipUsageStore.FILE}.tmp").exists(),
        )
    }

    @Test
    fun theStoreCreatesItsDirectoryRatherThanFailingOnTheFirstPick() {
        val nested = File(tmp.root, "not/created/yet")
        SnipUsageStore(nested) { 1_000L }.recordAndPrune("a", setOf("a"))
        assertEquals(
            "the first pick on a fresh install must create the directory; " +
                "noBackupFilesDir exists but the snippets subdirectory may not " +
                "if nothing has been imported through SnipStore yet",
            mapOf("a" to 1_000L),
            SnipUsageStore(nested).lastUsed(),
        )
    }
}

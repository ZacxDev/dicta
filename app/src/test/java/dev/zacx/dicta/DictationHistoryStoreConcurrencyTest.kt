package dev.zacx.dicta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 🔴 TWO stores, ONE directory — the shape production actually builds.
 *
 * [DictationSession] constructs `DictationHistoryStore(context)` **per
 * session**, and `SettingsActivity` constructs its own. They address the same
 * directory and the same `index.json`, so an instance monitor (`@Synchronized`,
 * what this class shipped with) bought mutual exclusion between a session and
 * its own upload worker and between nothing else. Nothing in the rest of the
 * suite touched a thread, so the whole class of defect was invisible.
 *
 * ## What is lost, and why it is permanent
 *
 * Every mutator is a read-modify-write of the index. Two of them overlapping
 * means the second write is computed from a snapshot taken before the first,
 * so the first's row is dropped — while its audio file stays on disk. Nothing
 * ever collects that file: `visible()` prunes rows whose file is missing, never
 * files that have no row, and `sweepAndReset()` runs only on a missing or
 * corrupt index. So the orphan counts against no retention bound and lives on
 * the device until the user taps "Delete all history".
 *
 * ## Deterministic in BOTH directions, and not by sleeping
 *
 * The interleaving is driven through the injected clock, which `add` calls
 * after `load()` and before the copy — i.e. from inside the critical section.
 * Thread A's clock announces it is in, waits for B's whole `add` to finish,
 * and only then lets A's write proceed:
 *
 *  - **Per-instance lock:** B is not excluded, completes, and A's stale write
 *    clobbers B's row. One row where two were added. Red, every run, no sleep.
 *  - **Process-wide lock:** B cannot enter while A holds the monitor, so A's
 *    wait is the one thing here that has a timeout, and it expires. A writes,
 *    releases, B then runs serialised and sees A's row. Two rows. Green.
 *
 * The timeout is therefore paid only on the passing path, and it can only ever
 * make this test *more* likely to pass — which is why the assertion below is
 * on the row count and not on whether the latch fired.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DictationHistoryStoreConcurrencyTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val historyDir: File by lazy { temp.newFolder("history") }
    private val sourceDir: File by lazy { temp.newFolder("sources") }

    private fun recording(name: String): File =
        File(sourceDir, name).apply { writeBytes(ByteArray(64) { 7 }) }

    @Test
    fun twoStoresOverOneDirectoryDoNotLoseAnIndexRow() {
        val aInside = CountDownLatch(1)
        val bFinished = CountDownLatch(1)

        // A's clock blocks inside add(), after the index has been read.
        val storeA = DictationHistoryStore(
            historyDir,
            {
                aInside.countDown()
                // Only elapses when the monitor is shared, in which case B is
                // waiting on it and the result is correct anyway.
                bFinished.await(2, TimeUnit.SECONDS)
                1_700_000_000_001L
            },
        )
        val storeB = DictationHistoryStore(historyDir, { 1_700_000_000_002L })

        var failureA: Throwable? = null
        var failureB: Throwable? = null

        val threadA = Thread {
            runCatching {
                storeA.add(recording("a.ogg"), "from A", fromRemote = false, audioSeconds = 1.0)
            }.onFailure { failureA = it }
        }
        val threadB = Thread {
            runCatching { aInside.await(5, TimeUnit.SECONDS) }
            runCatching {
                storeB.add(recording("b.ogg"), "from B", fromRemote = false, audioSeconds = 1.0)
            }.onFailure { failureB = it }
            bFinished.countDown()
        }

        threadA.start()
        threadB.start()
        threadA.join(20_000)
        threadB.join(20_000)

        assertEquals("thread A threw: $failureA", null, failureA)
        assertEquals("thread B threw: $failureB", null, failureB)
        assertTrue("thread A never finished", !threadA.isAlive)
        assertTrue("thread B never finished", !threadB.isAlive)

        val texts = storeA.recent(10).map { it.text }.sorted()
        assertEquals(
            "an index row was LOST: two stores over one directory each did a " +
                "read-modify-write of index.json and one clobbered the other. " +
                "The monitor must be process-wide, not per-instance — " +
                "production builds one store per session plus one in " +
                "SettingsActivity. The dropped row's audio is still on disk " +
                "with no entry pointing at it, which no sweep ever collects, " +
                "so it counts against no retention bound and stays on the " +
                "phone forever. Rows found: $texts",
            listOf("from A", "from B"),
            texts,
        )

        // Positive control: the harness really did write both files, so a
        // green above cannot come from a run where neither add happened.
        assertEquals(
            "the harness wrote $texts rows but not two audio files, so the " +
                "assertion above is not measuring two concurrent adds",
            2,
            (historyDir.listFiles() ?: emptyArray())
                .count { !it.name.startsWith(DictationHistoryStore.INDEX) },
        )
    }
}

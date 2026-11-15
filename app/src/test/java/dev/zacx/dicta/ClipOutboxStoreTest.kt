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
 * The on-disk capture queue.
 *
 * 🔴 **Robolectric for `org.json`**, as in [ClipJsonTest] — without the runner
 * every write produces an empty file and every read an empty list, which reads
 * exactly like a queue that is dropping the user's captures.
 *
 * 🔴 **Invariant guards, not regression coverage.** [ClipOutboxStore] is a new
 * file.
 *
 * ## 🔴 What this file is really guarding
 *
 * The outbox holds the **only copy** of a capture between the moment
 * `CaptureActivity` takes it and the moment the service accepts it. Every
 * assertion below is about not losing one, and that is why the store's failure
 * posture is the **opposite** of `SnipUsageStore`'s: that one swallows a failed
 * write because it holds a re-derivable count, this one throws because it holds
 * the user's text.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClipOutboxStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun store(dir: File = folder.root) = ClipOutboxStore(dir)

    @Test
    fun anAppendedCaptureComesBackWithItsBodyAndItsOwnCaptureTime() {
        val outbox = store()
        val id = outbox.append("the thing I copied", 1_700_000_000_123L)
        assertEquals(
            listOf(ClipOutbox.Pending(id, "the thing I copied", 1_700_000_000_123L)),
            outbox.pending(),
        )
    }

    @Test
    fun theCaptureSurvivesAFreshStoreOverTheSameDirectory() {
        // 🔴 The actual requirement: `CaptureActivity` finishes immediately, so
        // the process that wrote the row is routinely gone before anything
        // reads it back. An in-memory queue would pass every other test here.
        store().append("survives", 11L)
        assertEquals(listOf("survives"), store().pending().map { it.body })
    }

    @Test
    fun capturesComeBackOLDESTFirstAndNotInWhateverOrderTheFileHappensToHold() {
        // 🔴 Order is a correctness requirement, not a nicety. The server
        // assigns `created_at` at POST time, so flushing out of order would
        // make a clip the user copied first appear newer than one they copied
        // later — on every other device. ClipFlusher relies on this.
        val outbox = store()
        outbox.append("first", 1L)
        outbox.append("second", 2L)
        outbox.append("third", 3L)
        assertEquals(listOf("first", "second", "third"), store().pending().map { it.body })
    }

    @Test
    fun idsAreNEVERREUSEDAfterTheOldestRowIsRemoved() {
        // 🔴 This is the one that would be silently wrong if `nextId` were
        // derived from the list's SIZE, which is the obvious implementation.
        // Remove the middle of three and the next append would collide with a
        // live row, and `remove` would then delete the WRONG capture — the
        // user's text, gone, with no error anywhere.
        val outbox = store()
        val a = outbox.append("a", 1L)
        val b = outbox.append("b", 2L)
        val c = outbox.append("c", 3L)
        outbox.remove(b)
        val d = outbox.append("d", 4L)

        assertEquals(listOf(a, c, d), store().pending().map { it.id })
        assertTrue("the new id $d collides with a live row", d != a && d != c)
        assertEquals(listOf("a", "c", "d"), store().pending().map { it.body })
    }

    @Test
    fun idsSurviveAFileThatLostItsCounterAndStillDoNotCollide() {
        // A hand-edited or truncated-then-rewritten file with no `next_id` must
        // not restart at 1 on top of surviving rows. The floor is max(id)+1.
        val outbox = store()
        outbox.append("a", 1L)
        val second = outbox.append("b", 2L)
        File(folder.root, ClipOutboxStore.FILE).writeText(
            """{"clips":[{"id":$second,"body":"b","created_at":2}]}"""
        )
        val next = store().append("c", 3L)
        assertTrue("id $next collides with the surviving row $second", next > second)
    }

    @Test
    fun removingAnIdThatIsNotThereChangesNothing() {
        // The flusher removes by id after a POST it may retry, so a
        // double-remove is an ordinary event rather than a defect.
        val outbox = store()
        val id = outbox.append("keep me", 1L)
        outbox.remove(id + 404L)
        assertEquals(listOf("keep me"), outbox.pending().map { it.body })
        outbox.remove(id)
        outbox.remove(id)
        assertEquals(emptyList<ClipOutbox.Pending>(), outbox.pending())
    }

    @Test
    fun anEmptyBodyIsREFUSEDRatherThanQueued() {
        // The service answers 400 `empty_body` for it (measured), so queueing
        // one would put an item in the outbox that can never leave except by
        // being dropped — and would block nothing else only because
        // ClipFlusher drops permanent refusals.
        val thrown = runCatching { store().append("", 1L) }.exceptionOrNull()
        assertTrue(
            "an empty capture was queued (got ${thrown?.javaClass?.simpleName})",
            thrown is ClipOutbox.Failed,
        )
        assertEquals(emptyList<ClipOutbox.Pending>(), store().pending())
    }

    @Test
    fun aWriteThatCannotHappenTHROWSRatherThanLookingLikeASuccess() {
        // 🔴 THE POSTURE TEST. `append` returning quietly on a failed write is
        // the defect this whole file exists to prevent: CaptureActivity would
        // toast "queued" over a capture that is simply gone.
        //
        // The directory is made unusable by putting a FILE where the store
        // needs a DIRECTORY, which is a condition `mkdirs` cannot fix — rather
        // than by chmod, which is unreliable under a test JVM running as a user
        // that may ignore it.
        val blocked = File(folder.root, "blocked")
        blocked.writeText("I am a file, not a directory")
        val thrown = runCatching { store(blocked).append("doomed", 1L) }.exceptionOrNull()
        assertTrue(
            "a failed write was reported as a success (got ${thrown?.javaClass?.simpleName})",
            thrown is ClipOutbox.Failed,
        )
        assertTrue(
            "the failure named nothing actionable: <${thrown?.message}>",
            thrown?.message.orEmpty().contains("saved"),
        )
    }

    @Test
    fun anUnreadableOutboxFileTHROWSRatherThanDegradingToAnEmptyQueue() {
        // 🔴 The read half of the same posture. Returning `[]` for a corrupt
        // file would make the flusher believe the queue had drained and the
        // picker show "nothing queued" — and nothing would ever retry. A throw
        // keeps the file, and the flusher reports the failure.
        File(folder.root, ClipOutboxStore.FILE).writeText("}{ not json")
        val thrown = runCatching { store().pending() }.exceptionOrNull()
        assertTrue(
            "a corrupt outbox read as empty (got ${thrown?.javaClass?.simpleName})",
            thrown is ClipOutbox.Failed,
        )
    }

    @Test
    fun amissingFileIsAnEmptyQueueAndNotAFailure() {
        // The ordinary state of a fresh install, and the one case where empty
        // is the honest answer.
        assertFalse(File(folder.root, ClipOutboxStore.FILE).exists())
        assertEquals(emptyList<ClipOutbox.Pending>(), store().pending())
    }

    @Test
    fun noTemporaryFileIsLeftBehindForTheNextReadToTripOver() {
        val outbox = store()
        outbox.append("a", 1L)
        outbox.append("b", 2L)
        outbox.remove(outbox.pending().first().id)
        val leftovers = folder.root.listFiles().orEmpty().map { it.name }.sorted()
        assertEquals(listOf(ClipOutboxStore.FILE), leftovers)
    }

    @Test
    fun theStoreLivesUnderNoBackupFilesDirSoACaptureIsNotSyncedOffTheDevice() {
        // 🔴 A privacy requirement, not housekeeping. `filesDir` is included in
        // Android's automatic cloud backup, which would put arbitrary clipboard
        // contents — passwords among them — into the user's Google account
        // without their having asked for that. The Context constructor is the
        // only thing that decides this, so it is the thing asserted.
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val dir = ClipOutboxStore(context).dir
        assertEquals(ClipOutboxStore.DIR, dir.name)
        assertEquals(context.noBackupFilesDir, dir.parentFile)
        assertFalse(
            "the outbox is inside filesDir, which IS backed up: $dir",
            dir.absolutePath.startsWith(context.filesDir.absolutePath + File.separator),
        )
    }
}

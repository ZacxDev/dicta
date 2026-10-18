package dev.zacx.dicta

import android.content.Context
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 🔴 The **third** half of the cancel button.
 *
 * [BubbleDictationTest] proves the state machine decides not to upload.
 * [DictationSessionUploadGateTest] proves the session then does not upload. Both
 * are worth nothing if the session *files the recording into a browsable
 * history* instead — the user pressed cancel, and "nothing was uploaded" and
 * "nothing was kept" are the same promise to them. The comment on
 * `suppressUpload()` says the first out loud; this test is what makes the
 * second true rather than incidental.
 *
 * So the capture hook sits BELOW both gates in `onFinished`, and that placement
 * is pinned behaviourally here — by counting what is on disk afterwards, not by
 * reading the order of the lines.
 *
 * [aFinishedRecordingIsFiledInHistory] is the positive control: a zero from a
 * harness that never reaches the hook at all is indistinguishable from a zero
 * caused by the suppression under test, so the pair is what carries the claim.
 *
 * ⚠ **Still not covered HERE, but no longer uncovered.** The `updateText` calls
 * wired into the `RemoteTranscriber.Events` handlers are not exercised by this
 * file: the sessions built here have no token, so nothing reaches
 * `onRemoteTranscript`. That gap used to be unclosable, because
 * `RemoteTranscriber` was constructed inside `transcribeRemotely` rather than
 * injected. It now is injected (`transcriberFactory`), and
 * [DictationSessionRemoteHistoryWiringTest] drives both handlers with a
 * scripted transport and asserts the STORE's resulting state. The store side
 * alone remains pinned by
 * `DictationHistoryStoreTest.updateTextReplacesTheStoredTranscript`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DictationSessionHistoryCaptureTest {

    @get:Rule
    val temp = TemporaryFolder()

    private class Silent : DictationSession.Callbacks {
        override fun onTranscript(text: String, fromRemote: Boolean, isFinal: Boolean) = Unit
        override fun onStatus(text: String) = Unit
        override fun onLevel(level: Double) = Unit
        override fun onTier(tier: AudioCaptureLogic.Tier) = Unit
        override fun onClientSilenced(silenced: Boolean) = Unit
        override fun onRecordingStopped() = Unit
    }

    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    /** History rooted in a temp dir, with a fixed clock. */
    private fun store(): DictationHistoryStore =
        DictationHistoryStore(temp.newFolder("history"), { 1_700_000_000_000L })

    /** As in [DictationSessionUploadGateTest]: no usable token, no socket. */
    private fun settings(): SttSettings {
        val prefs = ctx.getSharedPreferences("history-capture-test", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        val cipher = object : SecretCipher {
            override fun encrypt(plaintext: String): String = plaintext
            override fun decrypt(blob: String): String? = null
        }
        return SttSettings(prefs, TokenStore(prefs, cipher))
    }

    private fun session(history: DictationHistoryStore) =
        DictationSession(ctx, settings(), Silent(), history = history)

    private fun recordingFile(): File =
        File(temp.newFolder("src-${counter++}"), "dictation-9.ogg")
            .apply { writeBytes(ByteArray(4096)) }

    private var counter = 0

    /**
     * Calls the session's own `MicCapture.Listener.onFinished` — the real entry
     * point — by reflection, because the listener is a private inner class and
     * `MicCapture` cannot be started here. Same mechanism as
     * [DictationSessionUploadGateTest].
     */
    private fun finishRecording(session: DictationSession, file: File) {
        val listenerClass = Class.forName("dev.zacx.dicta.DictationSession\$MicListener")
        val ctor = listenerClass.getDeclaredConstructor(DictationSession::class.java)
            .apply { isAccessible = true }
        (ctor.newInstance(session) as MicCapture.Listener).onFinished(file, 3.0)
    }

    /**
     * Lets the worker thread finish whatever it started and drains the looper.
     *
     * ⚠ NOT a synchronisation primitive, and specifically not one for
     * `destroy()`'s history cleanup, which since r2 runs on an un-joined daemon
     * thread. An assertion about whether that cleanup has *happened* must wait
     * on [cleanupLatch] instead — a sleep there passes almost always and flakes
     * opaquely in CI.
     */
    private fun settle() {
        Thread.sleep(100)
        shadowOf(Looper.getMainLooper()).idle()
    }

    /**
     * The real [DictationSession.runHistoryCleanup], wrapped so a test can wait
     * on the cleanup block's **completion** rather than on wall time.
     *
     * Still a real off-thread run: the production function is what starts the
     * thread, so this changes when the test observes the work, not where it
     * happens.
     */
    private fun cleanupLatch(done: CountDownLatch): (() -> Unit) -> Unit = { block ->
        DictationSession.runHistoryCleanup {
            try {
                block()
            } finally {
                done.countDown()
            }
        }
    }

    @Test
    fun aFinishedRecordingIsFiledInHistory() {
        // 🔴 Positive control. Without it, the empty store in the suppression
        // test below could just as easily mean this harness never reaches the
        // capture hook at all.
        val history = store()
        val session = session(history)
        val source = recordingFile()

        finishRecording(session, source)
        settle()

        val items = history.recent(10)
        assertEquals(
            "a normally finished recording was NOT filed in history, so this " +
                "harness cannot observe the capture hook and the suppression " +
                "test below proves nothing. Items: $items",
            1,
            items.size,
        )
        assertEquals(4096L, items.single().audioBytes)
        assertTrue(
            "the capture hook MOVED the recording instead of copying it — " +
                "DictationSession still owns that file and deletes it in " +
                "destroy()",
            source.isFile,
        )
        session.destroy()
    }

    @Test
    fun aSuppressedRecordingFilesNothingInHistory() {
        val history = store()
        val session = session(history)
        session.suppressUpload()

        finishRecording(session, recordingFile())
        settle()

        assertEquals(
            "a cancelled recording was filed into history. uploadSuppressed " +
                "IS the cancel button: a cancel that still leaves the audio " +
                "sitting in a browsable list is not a cancel, and 'nothing " +
                "was kept' is the same promise to the user as 'nothing was " +
                "uploaded' — which the comment on suppressUpload() already " +
                "makes. The capture hook must stay BELOW that gate.",
            emptyList<DictationHistoryStore.Item>(),
            history.recent(10),
        )
        assertEquals(
            "the directory is not empty, so audio was written even though no " +
                "index entry was: ${history.dir.list()?.toList()}",
            DictationHistoryStore.Usage(0, 0L),
            history.usage(),
        )
        assertEquals(
            "a cancelled recording left files in the history directory",
            emptyList<String>(),
            (history.dir.listFiles() ?: emptyArray()).map { it.name }.sorted(),
        )
        session.destroy()
    }

    @Test
    fun aDestroyedSessionFilesNothingInHistory() {
        // destroy() stops the capture, which lands in onFinished. A dead
        // session deletes its recording rather than keeping it, so the hook
        // must be below this gate too.
        //
        // ⚠ The SEQUENTIAL case only: destroy() has fully returned before
        // onFinished runs, so this exercises the gate at the top of the method
        // and nothing else. The interleaved case — destroy() landing *during*
        // the hook's disk IO — is a different code path and is
        // [aSessionDestroyedWhileFilingKeepsNothing] below.
        val history = store()
        val session = session(history)
        session.destroy()

        finishRecording(session, recordingFile())
        settle()

        assertEquals(
            "a destroyed session filed its recording into history",
            emptyList<DictationHistoryStore.Item>(),
            history.recent(10),
        )
        assertEquals(
            emptyList<String>(),
            (history.dir.listFiles() ?: emptyArray()).map { it.name }.sorted(),
        )
    }

    /**
     * 🔴 The interleaved case, which the sequential test above cannot see.
     *
     * `onFinished` reads `destroyed` and *then* copies up to ~2.29 MiB (AAC,
     * 32 kbps; ~1.72 MiB on Opus) and re-serialises the index, so `destroy()`
     * has a real window to land between the two — a window ~5× wider than when
     * this test was written, which sized it at ~352–469 KiB under the old
     * 120 s recording cap. What used to happen then: the add succeeded, and `worker.execute`
     * on the executor `destroy()` had just `shutdownNow()`n threw an uncaught
     * `RejectedExecutionException` **on the capture thread** — while the
     * recording the user had torn the session down to be rid of sat in a
     * browsable history.
     *
     * The window is opened here by the store's injected clock, which `add`
     * calls on the capture thread between the gate and the write. No threads
     * and no sleeping: the destroy happens at a point in the call stack rather
     * than at a point in time, so the interleaving is the same on every run.
     */
    @Test
    fun aSessionDestroyedWhileFilingKeepsNothing() {
        val dir = temp.newFolder("history-interleaved")
        var victim: DictationSession? = null
        val history = DictationHistoryStore(
            dir,
            {
                // Inside history.add(), past the destroyed gate in onFinished
                // and before the index is rewritten.
                victim?.destroy()
                1_700_000_000_000L
            },
        )
        val session = session(history).also { victim = it }

        // Must not throw: the enqueue below the add is what catches the
        // rejection, and it runs on the capture thread.
        finishRecording(session, recordingFile())
        settle()

        assertEquals(
            "a session destroyed WHILE the capture hook was filing its " +
                "recording left the entry in history. The gate at the top of " +
                "onFinished cannot see this — it ran before destroy() did — so " +
                "the entry has to be taken back out once the add returns.",
            emptyList<DictationHistoryStore.Item>(),
            history.recent(10),
        )
        assertEquals(
            "the audio copy survived in the history directory, so a destroyed " +
                "session kept a recording of the user's voice that no index row " +
                "points at — nothing ever sweeps that: ${dir.list()?.toList()}",
            emptyList<String>(),
            (dir.listFiles() ?: emptyArray())
                .map { it.name }
                .filterNot { it.startsWith(DictationHistoryStore.INDEX) }
                .sorted(),
        )
    }

    // --- destroy() landing AFTER the enqueue ---------------------------------

    /**
     * Settings whose token decrypts, so the upload ladder gets past
     * `isRemoteConfigured` and actually calls the transport. Same seam
     * [DictationSessionRemoteHistoryWiringTest] uses.
     */
    private fun configuredSettings(): SttSettings {
        val prefs = ctx.getSharedPreferences(
            "history-enqueue-test-${counter++}", Context.MODE_PRIVATE
        )
        prefs.edit().clear().apply()
        val cipher = object : SecretCipher {
            override fun encrypt(plaintext: String): String = plaintext
            override fun decrypt(blob: String): String? = blob
        }
        val tokens = TokenStore(prefs, cipher)
        tokens.putToken("a-bearer-token")
        return SttSettings(prefs, tokens)
    }

    /**
     * The real ladder, over a transport that parks inside `upload` until
     * [release] — so the single worker thread is provably occupied, and
     * "started" is a point in the call stack rather than a point in time.
     *
     * @param occupied counted down once the worker is inside the transport.
     */
    private fun blockingTranscriber(
        occupied: CountDownLatch,
        release: CountDownLatch,
    ): (Context, SttSettings, Locale) -> RemoteTranscriber = { _, _, _ ->
        RemoteTranscriber(
            ctx,
            configuredSettings(),
            locale = Locale("en", "US"),
            transportFactory = { _, _ ->
                RemoteTranscriber.Transport(
                    upload = {
                        occupied.countDown()
                        release.await(10, TimeUnit.SECONDS)
                        SttOutcome.Success("remote text")
                    },
                    health = { true },
                )
            },
            sleeper = { },
            unitRandom = { 0.5 },
            elapsedMs = { 0L },
        )
    }

    /**
     * 🔴 The direction that matters most in production, and the one the
     * two-recording test below is structurally blind to.
     *
     * A session whose upload has **started** must keep its entry when
     * `destroy()` lands. That is not an edge case: `BubbleService` parks every
     * submitted session in `submitted` and destroys it when the next recording
     * starts, so "destroy() deletes whatever this session filed" would delete
     * the history entry of every dictation the user actually completed — the
     * feature, not the window.
     *
     * ⚠ Why this needs its OWN test rather than the pair asserted below:
     * `pendingHistoryId` is a single slot, so with two recordings in one
     * session the second `set` overwrites the first id and destroy() cannot
     * reach the running entry even with the claim removed. A mutation deleting
     * the task's `compareAndSet` therefore SURVIVED the two-recording test,
     * measured — one recording is the only shape that can see it.
     */
    @Test
    fun aRecordingWhoseUploadHasStartedSurvivesDestroy() {
        val occupied = CountDownLatch(1)
        val release = CountDownLatch(1)
        val history = store()
        val session = DictationSession(
            ctx,
            configuredSettings(),
            Silent(),
            history = history,
            transcriberFactory = blockingTranscriber(occupied, release),
        )

        finishRecording(session, recordingFile())
        assertTrue(
            "fixture: the worker never reached the transport, so the task " +
                "cannot be said to have STARTED and this test observes nothing",
            occupied.await(10, TimeUnit.SECONDS),
        )
        val id = history.recent(10).single().id

        session.destroy()
        release.countDown()
        settle()
        settle()

        assertEquals(
            "destroy() deleted the entry of a recording whose upload had " +
                "already started. BubbleService destroys every submitted " +
                "session when the next recording begins, so this deletes the " +
                "history of every completed dictation.",
            listOf(id),
            history.recent(10).map { it.id },
        )
    }

    /**
     * 🔴 The third interleaving, and the one the
     * `RejectedExecutionException` catch structurally cannot see.
     *
     * `destroy()` landing *before* the add is the gate at the top of
     * `onFinished`; *during* the add is the rejected enqueue
     * ([aSessionDestroyedWhileFilingKeepsNothing]). This is `destroy()` landing
     * after the enqueue was **accepted**: `shutdownNow()` drains the queue, the
     * task never runs, nothing throws — and the entry plus its audio copy
     * stayed in a browsable history forever. The user tore the session down and
     * a recording of their voice was kept.
     *
     * The interleaving is made deterministic rather than slept at: the single
     * worker thread is occupied inside the first recording's transport, so the
     * second recording's task can only queue behind it.
     *
     * 🔴 And the first recording is not scenery — it is the discriminating
     * half. A `destroy()` that simply deleted the last filed id would delete it
     * too, and `BubbleService` destroys every *successfully completed* session
     * when the next recording starts. So the assertion pair is "the queued one
     * is gone AND the running one is not".
     */
    @Test
    fun aSessionDestroyedAfterTheEnqueueKeepsOnlyTheRecordingAlreadyUploading() {
        val occupied = CountDownLatch(1)
        val release = CountDownLatch(1)
        // 🔴 Waited on below instead of sleeping. destroy()'s delete runs on a
        // daemon thread nobody joins, so `queued in left` would otherwise race
        // it — passing almost always and flaking opaquely under CI load.
        val cleaned = CountDownLatch(1)
        // 🔴 An ADVANCING clock, not this file's fixed one: both recordings go
        // into one store, and with equal `createdAtMs` the two ids differ only
        // by their random suffix — so "which of these two is the queued one"
        // would be decided by `Random`, and the first version of this test
        // labelled them backwards half the time.
        var clock = 1_700_000_000_000L
        val history = DictationHistoryStore(temp.newFolder("history-enqueue"), { clock })
        val session = DictationSession(
            ctx,
            configuredSettings(),
            Silent(),
            history = history,
            transcriberFactory = blockingTranscriber(occupied, release),
            historyCleanup = cleanupLatch(cleaned),
        )

        // Recording 1: its task starts and parks inside the transport, so it
        // owns the single worker thread for the rest of the test.
        finishRecording(session, recordingFile())
        assertTrue(
            "fixture: the worker never reached the transport, so nothing is " +
                "occupying it and recording 2's task would have RUN rather " +
                "than queued — this test would then be measuring the " +
                "already-covered interleaving.",
            occupied.await(10, TimeUnit.SECONDS),
        )

        // Recording 2: accepted by the executor, queued behind recording 1.
        clock += 1_000
        finishRecording(session, recordingFile())
        // recent() is newest-first, so with the clock advanced this is
        // [recording 2, recording 1] by construction rather than by luck.
        val filed = history.recent(10)
        assertEquals(
            "fixture: both recordings should be filed by now, so the delete " +
                "below is observable. Filed: ${filed.map { it.id }}",
            2,
            filed.size,
        )
        val queued = filed.first().id
        val running = filed.last().id
        assertEquals(
            "fixture: the two entries are not in creation order, so the " +
                "labels below are guesses",
            1_700_000_001_000L,
            filed.first().createdAtMs,
        )

        session.destroy()
        // 🔴 The actual completion of destroy()'s cleanup, not a sleep. Also a
        // positive control on the assertions below: a zero from a cleanup that
        // never ran is indistinguishable from one that ran and collected
        // nothing, so without this a broken destroy() could still read green
        // on the `running in left` half alone.
        assertTrue(
            "destroy()'s history cleanup never ran, so nothing could have " +
                "collected the queued entry and the assertions below are " +
                "measuring the harness rather than destroy()",
            cleaned.await(10, TimeUnit.SECONDS),
        )
        release.countDown()
        settle()

        val left = history.recent(10).map { it.id }
        assertFalse(
            "the queued recording survived destroy(). shutdownNow() drained " +
                "its task, so nothing ever ran to collect it and no " +
                "RejectedExecutionException was thrown — the session was torn " +
                "down and a recording of the user's voice stayed in a " +
                "browsable history. Left: $left",
            queued in left,
        )
        assertTrue(
            "destroy() deleted the recording whose upload was already " +
                "RUNNING. That is every successfully completed dictation: " +
                "BubbleService parks a submitted session and destroys it when " +
                "the next recording starts, so this would delete the feature " +
                "rather than close the window. Left: $left",
            running in left,
        )
    }

    /**
     * 🔴 `destroy()` runs on the main thread (Service/Activity `onDestroy`) and
     * `DictationHistoryStore.delete` takes the store's process-wide monitor and
     * then reads, rewrites and `fsync`s the index — a block of unbounded length
     * if it were inlined, because the monitor can be held by an `add` copying
     * up to ~2.29 MiB (AAC, 32 kbps; ~1.72 MiB on Opus) at the current 600 s
     * recording cap — ~5× the ~352–469 KiB this was written against under the
     * old 120 s cap, so the ANR risk it describes got worse, not better.
     *
     * The *outcome* of an inline delete is identical, so no behavioural test
     * can see the difference. This pins the seam that makes it off-thread.
     */
    @Test
    fun historyCleanupNeverRunsOnTheCallersThread() {
        val caller = Thread.currentThread()
        val ran = AtomicReference<Thread?>(null)
        val done = CountDownLatch(1)

        DictationSession.runHistoryCleanup {
            ran.set(Thread.currentThread())
            done.countDown()
        }

        assertTrue("the cleanup block never ran", done.await(10, TimeUnit.SECONDS))
        assertNotEquals(
            "destroy()'s history cleanup ran on its caller's thread, which in " +
                "production is the main thread — a store delete there is an " +
                "ANR on a teardown path, not jank",
            caller,
            ran.get(),
        )
    }
}

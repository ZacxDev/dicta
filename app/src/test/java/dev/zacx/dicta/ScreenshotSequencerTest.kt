package dev.zacx.dicta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.Executor

/**
 * Pins the hide -> capture -> restore sequencing of [ScreenshotSequencer].
 *
 * Plain JUnit — no Robolectric needed, which is the point of having extracted
 * the sequence out of the Activity's callback soup.
 *
 * The expectations are literal event logs, not values read back out of the
 * implementation.
 */
class ScreenshotSequencerTest {

    /** Runs submitted work inline, so the whole sequence is deterministic. */
    private val direct = Executor { it.run() }

    private class RecordingUi(val log: MutableList<String>) : HideableUi {
        override fun hideForCapture() {
            log.add("hide")
        }

        override fun restoreAfterCapture() {
            log.add("restore")
        }
    }

    /** A settler that fires immediately. */
    private val immediate = FrameSettler { it() }

    @Test
    fun happyPathHidesThenCapturesThenRestores() {
        val log = mutableListOf<String>()
        var delivered: Result<String>? = null

        ScreenshotSequencer(
            ui = RecordingUi(log),
            settler = immediate,
            worker = direct,
            main = direct,
            capture = { log.add("capture"); "file://shot.png" },
            onDone = { log.add("done"); delivered = it },
        ).start()

        assertEquals(listOf("hide", "capture", "restore", "done"), log)
        assertEquals("file://shot.png", delivered!!.getOrNull())
    }

    /**
     * THE case that matters: a capture failure must not strand the user with an
     * invisible dialog and a vanished bubble.
     */
    @Test
    fun restoresEvenWhenCaptureThrows() {
        val log = mutableListOf<String>()
        var delivered: Result<String>? = null
        val boom = IOException("virtual display produced no frame")

        ScreenshotSequencer(
            ui = RecordingUi(log),
            settler = immediate,
            worker = direct,
            main = direct,
            capture = { log.add("capture"); throw boom },
            onDone = { log.add("done"); delivered = it },
        ).start()

        assertEquals(listOf("hide", "capture", "restore", "done"), log)
        assertEquals(boom, delivered!!.exceptionOrNull())
    }

    /** A settler that never fires must still not leave the UI hidden. */
    @Test
    fun restoresWhenTheSettlerThrows() {
        val log = mutableListOf<String>()
        var delivered: Result<String>? = null
        val boom = IllegalStateException("no choreographer")

        ScreenshotSequencer(
            ui = RecordingUi(log),
            settler = FrameSettler { throw boom },
            worker = direct,
            main = direct,
            capture = { log.add("capture"); "unreachable" },
            onDone = { log.add("done"); delivered = it },
        ).start()

        assertEquals(listOf("hide", "restore", "done"), log)
        assertEquals(boom, delivered!!.exceptionOrNull())
    }

    /** A dead worker executor is still a restore path. */
    @Test
    fun restoresWhenTheWorkerRejectsTheCapture() {
        val log = mutableListOf<String>()
        val boom = java.util.concurrent.RejectedExecutionException("shut down")

        ScreenshotSequencer(
            ui = RecordingUi(log),
            settler = immediate,
            worker = Executor { throw boom },
            main = direct,
            capture = { log.add("capture"); "unreachable" },
            onDone = { log.add("done") },
        ).start()

        assertEquals(listOf("hide", "restore", "done"), log)
    }

    /**
     * A dead main executor (activity torn down between hide and capture) must
     * still restore — off-thread if necessary — rather than never.
     */
    @Test
    fun restoresWhenTheMainExecutorIsGone() {
        val log = mutableListOf<String>()

        ScreenshotSequencer(
            ui = RecordingUi(log),
            settler = immediate,
            worker = direct,
            main = Executor { throw IllegalStateException("activity destroyed") },
            capture = { log.add("capture"); "file://shot.png" },
            onDone = { log.add("done") },
        ).start()

        assertEquals(listOf("hide", "capture", "restore", "done"), log)
    }

    /** A double tap must not hide twice nor restore twice. */
    @Test
    fun startIsIdempotent() {
        val log = mutableListOf<String>()
        val sequencer = ScreenshotSequencer(
            ui = RecordingUi(log),
            settler = immediate,
            worker = direct,
            main = direct,
            capture = { log.add("capture"); "file://shot.png" },
            onDone = { log.add("done") },
        )
        sequencer.start()
        sequencer.start()
        sequencer.start()

        assertEquals(listOf("hide", "capture", "restore", "done"), log)
        assertEquals(1, log.count { it == "hide" })
        assertEquals(1, log.count { it == "restore" })
        assertEquals(1, log.count { it == "done" })
    }

    /**
     * Ordering, stated as a relationship rather than as an index: the capture
     * may never run before the hide, nor after the restore.
     */
    @Test
    fun captureHappensStrictlyBetweenHideAndRestore() {
        val log = mutableListOf<String>()
        ScreenshotSequencer(
            ui = RecordingUi(log),
            settler = immediate,
            worker = direct,
            main = direct,
            capture = { log.add("capture"); Unit },
            onDone = { },
        ).start()

        val hide = log.indexOf("hide")
        val capture = log.indexOf("capture")
        val restore = log.indexOf("restore")
        assertTrue("hide missing", hide >= 0)
        assertTrue("capture missing", capture >= 0)
        assertTrue("restore missing", restore >= 0)
        assertTrue("capture ran before hide", hide < capture)
        assertTrue("restore ran before capture finished", capture < restore)
    }
}

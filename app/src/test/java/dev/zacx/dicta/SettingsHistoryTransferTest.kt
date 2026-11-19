package dev.zacx.dicta

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * History export and import driven through the real `SettingsActivity`: the
 * buttons, the SAF intents they start, and the result delivered back through
 * the framework — over the SAME store the app keeps its recordings in
 * (`noBackupFilesDir`), for [SettingsHistoryControlsTest]'s reason.
 *
 * What this cannot observe: a real document provider. The SAF picker's own UI
 * and a provider's actual storage are device-only checks (see the PR).
 *
 * Fixtures are synthetic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS")
class SettingsHistoryTransferTest {

    @get:Rule
    val temp = TemporaryFolder()

    private var controller: ActivityController<SettingsActivity>? = null
    private val app get() = RuntimeEnvironment.getApplication()
    private val appStore get() = DictationHistoryStore(app)
    private var counter = 0

    @Before
    fun emptyHistory() = appStore.clearAll()

    @After
    fun tearDown() {
        controller?.pause()?.stop()?.destroy()
    }

    private fun start(): SettingsActivity =
        Robolectric.buildActivity(SettingsActivity::class.java).also { controller = it }.setup().get()

    private fun line(a: SettingsActivity) =
        a.findViewById<TextView>(R.id.stt_history_transfer_state).text?.toString() ?: ""

    /** Drains the main looper until the transfer line leaves "Working…" (or 5 s). */
    private fun settled(a: SettingsActivity): String {
        val working = app.getString(R.string.stt_history_transfer_working)
        val deadline = System.currentTimeMillis() + 5_000
        var now = line(a)
        while ((now == working || now.isEmpty()) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idle()
            now = line(a)
        }
        return now
    }

    private fun tap(a: SettingsActivity, button: Int): Intent {
        a.findViewById<Button>(button).performClick()
        val started = shadowOf(a).nextStartedActivityForResult
        assertNotNull("the button started no document intent", started)
        return started!!.intent
    }

    /** Delivers [uri] through the framework, keyed on the intent actually started. */
    private fun deliver(a: SettingsActivity, intent: Intent, uri: Uri) =
        shadowOf(a).receiveResult(intent, Activity.RESULT_OK, Intent().setData(uri))

    private fun archiveOf(vararg texts: String): ByteArray {
        val src = DictationHistoryStore(temp.newFolder("src-${counter++}"), { 1_700_000_000_000L + counter })
        texts.forEachIndexed { i, t ->
            val f = File(temp.root, "rec-$counter-$i.ogg").apply { writeBytes(ByteArray(50 + i) { (it + i).toByte() }) }
            src.add(f, t, fromRemote = false, audioSeconds = 1.5 + i)
        }
        return ByteArrayOutputStream().also { src.exportArchive(it) }.toByteArray()
    }

    @Test
    fun theButtonsStartCreateAndOpenDocumentForAZip() {
        val a = start()
        val create = tap(a, R.id.stt_export_history)
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, create.action)
        assertEquals("application/zip", create.type)
        assertTrue(create.categories?.contains(Intent.CATEGORY_OPENABLE) == true)
        assertTrue(create.getStringExtra(Intent.EXTRA_TITLE)!!.endsWith(".zip"))

        val open = tap(a, R.id.stt_import_history)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, open.action)
        assertEquals("application/zip", open.type)
        assertTrue(open.categories?.contains(Intent.CATEGORY_OPENABLE) == true)
    }

    @Test
    fun anImportThroughTheScreenLandsInTheAppsStoreAndReportsCounts() {
        val uri = Uri.parse("content://dev.zacx.dicta.test/archive-${counter++}.zip")
        shadowOf(app.contentResolver).registerInputStream(uri, ByteArrayInputStream(archiveOf("alpha line", "beta line")))
        val a = start()

        deliver(a, tap(a, R.id.stt_import_history), uri)

        assertEquals(app.getString(R.string.stt_history_import_done, 2, 0, 0), settled(a))
        assertEquals(setOf("alpha line", "beta line"), appStore.recent(10).map { it.text }.toSet())
    }

    @Test
    fun aRefusedArchiveSaysSoAndLeavesTheAppsStoreEmpty() {
        val good = archiveOf("gamma line")
        val uri = Uri.parse("content://dev.zacx.dicta.test/archive-${counter++}.zip")
        shadowOf(app.contentResolver).registerInputStream(uri, ByteArrayInputStream(good.copyOf(good.size / 2)))
        val a = start()

        deliver(a, tap(a, R.id.stt_import_history), uri)

        val shown = settled(a)
        assertTrue(shown, shown.startsWith(app.getString(R.string.stt_history_import_rejected, "").substringBefore("(")))
        assertEquals(0, appStore.usage().itemCount)
    }

    @Test
    fun anExportIsWrittenThenReadBackAndVerifiedFromTheSameDocument() {
        val rec = File(temp.root, "own.ogg").apply { writeBytes(ByteArray(80) { (it * 3).toByte() }) }
        val id = appStore.add(rec, "delta line", fromRemote = true, audioSeconds = 2.25)
        val uri = Uri.parse("content://dev.zacx.dicta.test/export-${counter++}.zip")
        val written = ByteArrayOutputStream()
        val resolver = shadowOf(app.contentResolver)
        resolver.registerOutputStream(uri, written)
        // The read-back sees exactly what the export wrote, as a provider would.
        resolver.registerInputStream(
            uri,
            object : InputStream() {
                private val bytes by lazy { ByteArrayInputStream(written.toByteArray()) }
                override fun read(): Int = bytes.read()
                override fun read(b: ByteArray, off: Int, len: Int): Int = bytes.read(b, off, len)
            },
        )
        val a = start()

        deliver(a, tap(a, R.id.stt_export_history), uri)

        assertEquals(app.getString(R.string.stt_history_export_done, 1), settled(a))
        val target = DictationHistoryStore(temp.newFolder("target"), { 0L })
        target.importArchive(ByteArrayInputStream(written.toByteArray()))
        assertEquals(appStore.item(id), target.item(id))
        assertArrayEquals(rec.readBytes(), target.audioFor(id)!!.readBytes())
    }
}

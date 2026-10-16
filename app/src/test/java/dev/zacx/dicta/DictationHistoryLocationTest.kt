package dev.zacx.dicta

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * 🔴 Where history lands — the half of the backup fix that is NOT the manifest.
 *
 * [BackupManifestTest] pins `android:allowBackup="false"`. That attribute is
 * one edit away from coming back (a merged library manifest, a later "we want
 * settings backup" change, a careless revert), and the moment it does, every
 * path under `filesDir` becomes eligible for cloud auto-backup and
 * device-to-device transfer. `noBackupFilesDir` is excluded by the **platform**
 * as a property of the path itself, so rooting history there is what makes the
 * fix survive losing the attribute. The two tests are not duplicates: they pin
 * the two independent halves, and the data only leaves the device if BOTH give
 * way.
 *
 * Asserted as a path identity against the live `Context`, not as a substring of
 * a spelling: a guard that looked for `"no_backup"` in the path would pass for
 * `filesDir/no_backup_history/`, which is backed up.
 *
 * It also pins the choice AGAINST `cacheDir` — the opposite of what
 * [ScreenshotStore] correctly does — because the OS may reclaim `cacheDir` at
 * any moment with no notification, and history that silently empties is worse
 * than no history for a user who has stopped copying transcripts out.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DictationHistoryLocationTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun theStoreIsRootedInNoBackupFilesDir() {
        val store = DictationHistoryStore(ctx)

        assertEquals(
            "the history directory is not the platform's no-backup directory. " +
                "That exclusion is the half of the backup fix that survives " +
                "someone flipping android:allowBackup back to true, and " +
                "without it up to 200 MB of the user's recorded voice is " +
                "eligible for cloud backup and device transfer.",
            File(ctx.noBackupFilesDir, DictationHistoryStore.DIR).canonicalFile,
            store.dir.canonicalFile,
        )
    }

    @Test
    fun theStoreIsNotUnderCacheDirOrPlainFilesDir() {
        val dir = DictationHistoryStore(ctx).dir.canonicalFile
        val under = { parent: File -> dir.path.startsWith(parent.canonicalFile.path + File.separator) }

        assertFalse(
            "history is under cacheDir, which the OS may reclaim at any time " +
                "with no notification. ScreenshotStore is right to use it for " +
                "a screenshot needed until the share sheet reads it; a history " +
                "the user has built a habit on must not silently empty. Dir: $dir",
            under(ctx.cacheDir),
        )
        assertFalse(
            "history is under filesDir, which IS backed up the moment " +
                "allowBackup is true. Dir: $dir",
            under(ctx.filesDir),
        )
    }

    @Test
    fun aRecordingAddedThroughTheContextConstructorLandsThere() {
        // The positive control for the two assertions above: a path identity
        // that nothing ever writes to would satisfy them just as well.
        val store = DictationHistoryStore(ctx)
        store.clearAll()
        val source = File.createTempFile("loc", ".ogg").apply { writeBytes(ByteArray(48)) }

        val id = store.add(source, "t", fromRemote = false, audioSeconds = 1.0)
        val stored = store.audioFor(id)

        assertTrue("nothing was written, so this harness proves nothing", stored != null)
        assertEquals(
            File(ctx.noBackupFilesDir, DictationHistoryStore.DIR).canonicalFile,
            stored!!.canonicalFile.parentFile,
        )
        store.clearAll()
    }
}

package dev.zacx.dicta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * [SnipStore]: the imported catalog on disk, and the **seam** the tailnet-fetch
 * follow-up drops into.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SnipStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun store(): SnipStore = SnipStore(temp.newFolder("snippets-${counter++}"))

    private var counter = 0

    private val two = listOf(
        Snippet("Zebra crossing", "walk\nslowly", listOf("stripes"), "path"),
        Snippet("Quince jam", "boil", emptyList(), "prompt"),
    )

    @Test
    fun aSavedCatalogComesBackWithItsRowsAndItsImportDate() {
        val s = store()
        s.save(two, importedAtMs = 1_700_000_000_000L)
        assertEquals(SnipStore.Catalog(two, 1_700_000_000_000L), s.catalog())
    }

    @Test
    fun nothingImportedIsNullAndNotAnEmptyCatalog() {
        // 🔴 The distinction the picker's and the settings screen's copy both
        // rest on: "nothing imported yet" tells the operator what to do next,
        // and "0 snippets" tells them the import failed when it never ran.
        assertNull(store().catalog())
    }

    @Test
    fun anImportedButEmptyCatalogIsNotNull() {
        // The other side of the same distinction, and the one a `null`-on-empty
        // shortcut would collapse: an operator who cleared their snip catalog
        // imported something, and the picker says so differently.
        val s = store()
        s.save(emptyList(), importedAtMs = 42L)
        val catalog = s.catalog()
        assertNotNull("an imported empty catalog read back as nothing imported", catalog)
        assertEquals(emptyList<Snippet>(), catalog!!.snippets)
    }

    @Test
    fun anUnreadableCatalogIsNullAndTheFileIsLeftExactlyAsItWas() {
        // 🔴 The opposite posture from DictationHistoryStore's, deliberately.
        // History is the only copy of the user's voice, so a corrupt index
        // there has to be resolved destructively to keep the app startable. A
        // corrupt catalog costs one re-import, so nothing is swept and nothing
        // is reset — and a store that "recovered" by deleting the file would
        // destroy the evidence of what went wrong.
        val dir = temp.newFolder("corrupt")
        val file = File(dir, SnipStore.FILE).apply { writeText("{\"snippets\": tru") }
        assertNull(SnipStore(dir).catalog())
        assertEquals(
            "the store rewrote or deleted a catalog it could not read",
            "{\"snippets\": tru",
            file.readText(),
        )
    }

    @Test
    fun savingReplacesThePreviousCatalogRatherThanMergingIntoIt() {
        // An import is a replace, which is what @string/stt_import_snippets_desc
        // promises. A merge would leave rows the operator deleted upstream
        // alive on the phone forever, with no way to remove them.
        val s = store()
        s.save(two, importedAtMs = 1L)
        s.save(listOf(two[1]), importedAtMs = 2L)
        assertEquals(SnipStore.Catalog(listOf(two[1]), 2L), s.catalog())
    }

    @Test
    fun theStoreRootsItselfUnderNoBackupFilesDirAndNotUnderFilesOrCache() {
        // 🔴 The property is the PATH, not a manifest attribute: the platform
        // excludes noBackupFilesDir from cloud auto-backup and from
        // device-to-device transfer as a property of the directory itself, so
        // the decision survives losing android:allowBackup="false". Snippet
        // bodies are arbitrary operator text — paths, hostnames, prompt
        // templates — and have no business riding a backup off the phone.
        //
        // And NOT cacheDir, which can be emptied at any moment with no event:
        // a picker that silently went empty would be strictly worse than not
        // having the feature.
        val app = RuntimeEnvironment.getApplication()
        val store = SnipStore(app)
        val dir = SnipStore::class.java.getDeclaredField("dir")
            .let { it.isAccessible = true; it.get(store) as File }

        assertEquals(File(app.noBackupFilesDir, SnipStore.DIR).canonicalPath, dir.canonicalPath)
        assertTrue(
            "the catalog is under filesDir, which rides a backup the moment " +
                "allowBackup is flipped back: ${dir.canonicalPath}",
            !dir.canonicalPath.startsWith(app.filesDir.canonicalPath + File.separator),
        )
        assertTrue(
            "the catalog is under cacheDir, which the OS may empty without " +
                "notice: ${dir.canonicalPath}",
            !dir.canonicalPath.startsWith(app.cacheDir.canonicalPath + File.separator),
        )
    }
}

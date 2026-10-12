package dev.zacx.dicta

import android.content.ClipData
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri

/**
 * Pure intent construction, kept out of the Activity so the wire format can be
 * asserted in a unit test.
 */
object ShareIntents {

    const val MIME_TEXT = "text/plain"
    const val MIME_PNG = "image/png"

    /** Text only — the behaviour the Send button has always had. */
    fun textOnly(text: String): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = MIME_TEXT
            putExtra(Intent.EXTRA_TEXT, text)
        }

    /**
     * An image, with the dictated text alongside it **if there is any**.
     *
     * One function rather than separate image-only / image-plus-text builders:
     * the MIME type, the stream extra, the grant flag and the ClipData are
     * identical in both cases, and a second copy of that is a second place for
     * the grant to be forgotten. The only thing that varies is whether
     * `EXTRA_TEXT` is set, so that is the only branch — `text.isEmpty()` is
     * tested here and nowhere else.
     *
     * Note that `EXTRA_TEXT` is **a request, not a guarantee**: with
     * `type = image/png` and an `EXTRA_STREAM` present, a lot of receivers read
     * only the stream and silently drop `EXTRA_TEXT`. Gmail keeps both; several
     * chat apps do not. Dicta surfaces that in the UI
     * (`R.string.screenshot_attached_note`) and keeps Copy as the reliable path
     * for the text — do not describe this as "text and image are shared
     * together".
     *
     * `ClipData` is set in addition to `EXTRA_STREAM` because the URI grant
     * travels with the clip data; `FLAG_GRANT_READ_URI_PERMISSION` alone only
     * covers `Intent.getData()`, not an extra.
     */
    fun withImage(
        resolver: ContentResolver?,
        text: String,
        image: Uri,
        clipLabel: String,
    ): Intent = Intent(Intent.ACTION_SEND).apply {
        type = MIME_PNG
        putExtra(Intent.EXTRA_STREAM, image)
        // Sending EXTRA_TEXT="" is not the same as not sending it: some targets
        // render an empty body or an empty caption field for it. An image-only
        // share must carry no text extra at all.
        if (text.isNotEmpty()) putExtra(Intent.EXTRA_TEXT, text)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = if (resolver != null) {
            ClipData.newUri(resolver, clipLabel, image)
        } else {
            ClipData.newRawUri(clipLabel, image)
        }
    }

    fun chooser(inner: Intent, title: String): Intent =
        Intent.createChooser(inner, title).apply {
            // The chooser forwards the grant to whichever target is picked only
            // if the wrapper carries the flag too.
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
}

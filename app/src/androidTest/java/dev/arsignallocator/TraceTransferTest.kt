package dev.arsignallocator

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TraceTransferTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun shareAndAttachedSupportUseContentUriClipDataMimeAndReadGrant() {
        val trace = completedTrace()
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.trace-provider",
            trace,
        )

        val share = shareTraceIntent(context.contentResolver, uri)
        val support = supportIntent(
            "support@publisher.invalid",
            SupportDetails("1.0.0", "16", "Phone", "redacted summary"),
            uri,
            context.contentResolver,
        )

        listOf(share, support).forEach { intent ->
            assertEquals(Intent.ACTION_SEND, intent.action)
            assertEquals(TRACE_MIME_TYPE, intent.type)
            assertEquals(uri, intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java))
            assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        }
    }

    @Test
    fun supportWithoutAttachmentUsesMailtoAndNoGrantOrStream() {
        val details = SupportDetails("1.0.0", "16", "Phone", null)
        val intent = supportIntent(
            "support@publisher.invalid",
            details,
            null,
        )

        assertEquals(Intent.ACTION_SENDTO, intent.action)
        assertEquals("mailto", intent.data!!.scheme)
        assertEquals("support@publisher.invalid", intent.data!!.schemeSpecificPart.substringBefore('?'))
        assertEquals("AR Device Finder support", intent.data!!.getQueryParameter("subject"))
        assertEquals(supportBody(details), intent.data!!.getQueryParameter("body"))
        assertArrayEquals(
            arrayOf("support@publisher.invalid"),
            intent.getStringArrayExtra(Intent.EXTRA_EMAIL),
        )
        assertEquals("AR Device Finder support", intent.getStringExtra(Intent.EXTRA_SUBJECT))
        assertEquals(supportBody(details), intent.getStringExtra(Intent.EXTRA_TEXT))
        assertNull(intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java))
        assertNull(intent.clipData)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    @Test
    fun providerExposesOnlyTraceDirectoryAndTransferGateRejectsActiveTrace() {
        val unrelated = File(context.filesDir, "private.ndjson").apply { writeText("{}") }
        val active = File(context.filesDir, "traces/active.active.ndjson").apply {
            parentFile!!.mkdirs()
            writeText("{}")
        }

        assertThrows(IllegalArgumentException::class.java) {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.trace-provider",
                unrelated,
            )
        }
        assertFalse(isCompletedTrace(active, active.parentFile!!))
        assertTrue(isCompletedTrace(completedTrace(), active.parentFile!!))
    }

    @Test
    fun noHandlerFallbackReturnsFalse() {
        val launched = launchExternal(Intent()) { throw ActivityNotFoundException() }

        assertFalse(launched)
    }

    private fun completedTrace(): File =
        File(context.filesDir, "traces/session.ndjson").apply {
            parentFile!!.mkdirs()
            writeText("{}")
        }
}

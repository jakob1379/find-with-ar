package dev.arsignallocator.trace

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import java.io.File

const val TRACE_MIME_TYPE = "application/x-ndjson"
private const val SUPPORT_SUBJECT = "AR Device Finder support"
const val TRACE_CONTENT_DISCLOSURE =
    "This trace can include relative phone poses, timestamps, radio measurements, " +
        "session-local device keys, transport, manufacturer and service identifiers, " +
        "phone model, estimator results, your selected outcome, and your optional note. " +
        "It does not include hardware addresses, advertised names, GPS coordinates, or images."

data class SupportDetails(
    val appVersion: String,
    val androidVersion: String,
    val phoneModel: String,
    val sessionSummary: String?,
)

internal fun TraceSummary.redactedDescription(): String = when (mode) {
    TraceMode.SINGLE_TARGET ->
        "Single-device session: $acceptedObservationCount of $observationCount observations used; " +
            "outcome ${outcome.name.lowercase().replace('_', ' ')}"
    TraceMode.COMPLETE_SCAN ->
        "Complete Scan: $localizedDeviceCount of $observedDeviceCount observed devices localized; " +
            "outcome ${outcome.name.lowercase().replace('_', ' ')}"
}

internal fun supportBody(details: SupportDetails): String = buildString {
    appendLine("Please describe what happened:")
    appendLine()
    appendLine("--- App details ---")
    appendLine("App version: ${details.appVersion}")
    appendLine("Android version: ${details.androidVersion}")
    appendLine("Phone model: ${details.phoneModel}")
    details.sessionSummary?.let { appendLine("Selected session: $it") }
}

internal fun shareTraceIntent(contentResolver: ContentResolver, uri: Uri): Intent =
    Intent(Intent.ACTION_SEND).apply {
        type = TRACE_MIME_TYPE
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newUri(contentResolver, "AR Device Finder trace", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

internal fun supportIntent(
    supportEmail: String,
    details: SupportDetails,
    attachment: Uri?,
    contentResolver: ContentResolver? = null,
): Intent = if (attachment == null) {
    val body = supportBody(details)
    Intent(
        Intent.ACTION_SENDTO,
        Uri.parse(
            "mailto:${Uri.encode(supportEmail)}" +
                "?subject=${Uri.encode(SUPPORT_SUBJECT)}&body=${Uri.encode(body)}",
        ),
    ).apply {
        putExtra(Intent.EXTRA_EMAIL, arrayOf(supportEmail))
        putExtra(Intent.EXTRA_SUBJECT, SUPPORT_SUBJECT)
        putExtra(Intent.EXTRA_TEXT, body)
    }
} else {
    Intent(Intent.ACTION_SEND).apply {
        type = TRACE_MIME_TYPE
        putExtra(Intent.EXTRA_EMAIL, arrayOf(supportEmail))
        putExtra(Intent.EXTRA_SUBJECT, SUPPORT_SUBJECT)
        putExtra(Intent.EXTRA_TEXT, supportBody(details))
        putExtra(Intent.EXTRA_STREAM, attachment)
        clipData = ClipData.newUri(
            checkNotNull(contentResolver),
            "AR Device Finder trace",
            attachment,
        )
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

internal fun isCompletedTrace(file: File, traceDirectory: File): Boolean = runCatching {
    val canonical = file.canonicalFile
    canonical.isFile &&
        canonical.parentFile == traceDirectory.canonicalFile &&
        canonical.name.endsWith(".ndjson") &&
        !canonical.name.endsWith(".active.ndjson") &&
        !canonical.name.endsWith(".quarantined.ndjson")
}.getOrDefault(false)

internal fun completedTraceAt(path: String?, traceDirectory: File): File? =
    path?.let { runCatching { File(it).canonicalFile }.getOrNull() }
        ?.takeIf { isCompletedTrace(it, traceDirectory) }

internal fun launchExternal(intent: Intent, start: (Intent) -> Unit): Boolean = try {
    start(intent)
    true
} catch (_: ActivityNotFoundException) {
    false
}

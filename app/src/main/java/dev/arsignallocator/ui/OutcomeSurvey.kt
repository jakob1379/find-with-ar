package dev.arsignallocator.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.arsignallocator.trace.TraceOutcome
import dev.arsignallocator.trace.TraceOutcomeReason

internal const val SINGLE_TARGET_SURVEY_QUESTION =
    "Did AR Device Finder help you find the device?"
internal const val COMPLETE_SCAN_SURVEY_QUESTION =
    "Did Complete Scan map the devices you expected?"
internal const val OUTCOME_NOTE_LIMIT = 500

internal data class SurveySubmission(
    val outcome: TraceOutcome,
    val reason: TraceOutcomeReason? = null,
    val note: String? = null,
)

internal val surveyAnswers = listOf(
    "Yes" to TraceOutcome.YES,
    "No" to TraceOutcome.NO,
    "Not sure" to TraceOutcome.NOT_SURE,
    "Skip" to TraceOutcome.SKIPPED,
)

internal val surveyReasons = listOf(
    "Device disappeared" to TraceOutcomeReason.DEVICE_DISAPPEARED,
    "Device was hard to identify" to TraceOutcomeReason.DEVICE_HARD_TO_IDENTIFY,
    "Guidance was unclear" to TraceOutcomeReason.GUIDANCE_UNCLEAR,
    "Estimated area was wrong" to TraceOutcomeReason.ESTIMATED_AREA_WRONG,
    "Camera, AR, Bluetooth, or app error" to
        TraceOutcomeReason.CAMERA_AR_BLUETOOTH_OR_APP_ERROR,
    "Other" to TraceOutcomeReason.OTHER,
)

internal fun limitOutcomeNote(value: String): String {
    val end = value.offsetByCodePoints(0, value.codePointCount(0, value.length).coerceAtMost(OUTCOME_NOTE_LIMIT))
    return value.substring(0, end)
}

@Composable
internal fun EndSessionSurvey(
    question: String,
    onSubmit: (SurveySubmission) -> Unit,
    onDismiss: () -> Unit,
) {
    var outcome by remember { mutableStateOf<TraceOutcome?>(null) }
    var reason by remember { mutableStateOf<TraceOutcomeReason?>(null) }
    var note by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                contentPadding = PaddingValues(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text(
                        question,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.semantics { heading() },
                    )
                }
                if (outcome == null) {
                    items(surveyAnswers) { (label, answer) ->
                        Button(
                            onClick = { outcome = answer },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            Text(label)
                        }
                    }
                } else {
                    if (outcome == TraceOutcome.NO || outcome == TraceOutcome.NOT_SURE) {
                        item {
                            Text(
                                "What happened? (optional)",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.semantics { heading() },
                            )
                        }
                        items(surveyReasons) { (label, value) ->
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .clickable { reason = value },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = reason == value, onClick = { reason = value })
                                Text(label)
                            }
                        }
                    }
                    item {
                        Text(
                            "Do not include personal information.",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = note,
                            onValueChange = { note = limitOutcomeNote(it) },
                            label = { Text("Optional note") },
                            supportingText = {
                                Text("${note.codePointCount(0, note.length)}/$OUTCOME_NOTE_LIMIT")
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    onSubmit(
                                        SurveySubmission(
                                            outcome = checkNotNull(outcome),
                                            reason = reason,
                                            note = note.trim().takeIf(String::isNotEmpty),
                                        ),
                                    )
                                },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            ) {
                                Text("Finish")
                            }
                            TextButton(
                                onClick = {
                                    outcome = null
                                    reason = null
                                    note = ""
                                },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            ) {
                                Text("Back")
                            }
                        }
                    }
                }
            }
        }
    }
}

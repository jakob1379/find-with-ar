package dev.arsignallocator.ui

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import dev.arsignallocator.R
import dev.arsignallocator.TRACE_CONTENT_DISCLOSURE
import dev.arsignallocator.monetization.BillingState
import dev.arsignallocator.monetization.ConsentState
import dev.arsignallocator.monetization.EntitlementState
import dev.arsignallocator.monetization.PrivacyOptionsRequirement
import dev.arsignallocator.trace.TraceMode
import dev.arsignallocator.trace.TraceOutcome
import dev.arsignallocator.trace.TraceSummary
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DataOutcomeUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun everyAnswerOffersTheOptionalNoteAndCanFinishWithoutFields() {
        val expected = listOf(
            "Yes" to TraceOutcome.YES,
            "No" to TraceOutcome.NO,
            "Not sure" to TraceOutcome.NOT_SURE,
            "Skip" to TraceOutcome.SKIPPED,
        )
        val answers = mutableListOf<SurveySubmission>()
        var index by mutableIntStateOf(0)
        compose.setContent {
            MaterialTheme {
                if (index < expected.size) {
                    key(index) {
                        EndSessionSurvey(
                            "Question?",
                            {
                                answers += it
                                index += 1
                            },
                            {},
                        )
                    }
                }
            }
        }

        expected.forEach { (label, _) ->
            compose.onNodeWithText(label).performClick()
            compose.onAllNodesWithText("What happened? (optional)").assertCountEquals(
                if (label == "No" || label == "Not sure") 1 else 0,
            )
            compose.onNodeWithText("Optional note").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Do not include personal information.")
                .performScrollTo()
                .assertIsDisplayed()
            compose.onNodeWithText("Finish").performScrollTo().performClick()
        }
        compose.runOnIdle {
            assertEquals(expected.map { it.second }, answers.map { it.outcome })
            assertTrue(answers.all { it.reason == null && it.note == null })
        }
    }

    @Test
    fun dismissDoesNotFinalize() {
        var answer: SurveySubmission? = null
        var dismissals = 0
        compose.setContent {
            MaterialTheme {
                EndSessionSurvey("Question?", { answer = it }, { dismissals += 1 })
            }
        }
        Espresso.pressBack()
        compose.runOnIdle {
            assertNull(answer)
            assertEquals(1, dismissals)
        }
    }

    @Test
    fun noAndNotSureCanSubmitReasonsAndUnicodeLimitedNote() {
        val expected = listOf("No" to TraceOutcome.NO, "Not sure" to TraceOutcome.NOT_SURE)
        val answers = mutableListOf<SurveySubmission>()
        var index by mutableIntStateOf(0)
        compose.setContent {
            MaterialTheme {
                if (index < expected.size) {
                    key(index) {
                        EndSessionSurvey(
                            "Question?",
                            {
                                answers += it
                                index += 1
                            },
                            {},
                        )
                    }
                }
            }
        }

        expected.forEach {
            compose.onNodeWithText(it.first).performClick()
            compose.onNodeWithText("Guidance was unclear").performScrollTo().performClick()
            compose.onNodeWithText("Finish").performScrollTo().performClick()
        }
        compose.runOnIdle {
            assertEquals(expected.map { it.second }, answers.map { it.outcome })
            assertEquals(
                List(2) { dev.arsignallocator.trace.TraceOutcomeReason.GUIDANCE_UNCLEAR },
                answers.map { it.reason },
            )
        }
    }

    @Test
    fun exportShareAndDeleteRequireConfirmation() {
        var exports = 0
        var shares = 0
        var deletes = 0
        compose.setContent {
            MaterialTheme {
                DataHistoryScreen(
                    summaries = listOf(summary()),
                    error = null,
                    onBack = {},
                    onExport = { exports += 1 },
                    onShare = { shares += 1 },
                    onDelete = { deletes += 1 },
                    onDeleteAll = {},
                )
            }
        }

        compose.onNodeWithText("Export").performClick()
        compose.onNodeWithText(TRACE_CONTENT_DISCLOSURE).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, exports) }
        compose.onNodeWithText("Continue").performClick()
        compose.onNodeWithText("Share").performClick()
        compose.runOnIdle { assertEquals(0, shares) }
        compose.onNodeWithText("Continue").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.runOnIdle { assertEquals(0, deletes) }
        compose.onNodeWithText("Delete session").performClick()
        compose.runOnIdle {
            assertEquals(1, exports)
            assertEquals(1, shares)
            assertEquals(1, deletes)
        }
    }

    @Test
    fun supportAttachmentIsOptInAndSeparatelyDisclosed() {
        var sentSummary: TraceSummary? = null
        var sentFile: File? = null
        compose.setContent {
            MaterialTheme {
                ContactSupportScreen(
                    supportEmail = "support@publisher.invalid",
                    summaries = listOf(summary()),
                    onBack = {},
                    onSend = { selected, file ->
                        sentSummary = selected
                        sentFile = file
                    },
                )
            }
        }

        compose.onNodeWithText("Email support without a trace").performClick()
        compose.runOnIdle { assertNull(sentFile) }
        compose.onNodeWithText(
            "Single-device session: 4 of 5 observations used; outcome yes",
        ).performClick()
        compose.onNodeWithText("Attach selected trace and email").performClick()
        compose.runOnIdle { assertNull(sentFile) }
        compose.onNodeWithText(TRACE_CONTENT_DISCLOSURE).assertIsDisplayed()
        compose.onNodeWithText("Continue").performClick()
        compose.runOnIdle {
            assertEquals(summary().file, sentFile)
            assertEquals(summary().file, sentSummary!!.file)
        }
    }

    @Test
    fun helpIsReachable() {
        compose.setContent { MaterialTheme { HelpScreen {} } }
        compose.onNodeWithText("Troubleshooting").assertIsDisplayed()
    }

    @Test
    fun aboutLinksToNotices() {
        var notices = false
        compose.setContent {
            MaterialTheme {
                AboutScreen("1.0.0", {}, { notices = true })
            }
        }
        compose.onNodeWithText("Third-party notices").performClick()
        compose.runOnIdle { assertEquals(true, notices) }
    }

    @Test
    fun combinedSettingsRoutesEveryProductionDestination() {
        val reached = mutableSetOf<String>()
        compose.setContent {
            MaterialTheme {
                SettingsScreen(
                    billingState = BillingState(
                        entitlement = EntitlementState.FREE,
                        isAuthoritative = true,
                        localizedPrice = "DKK 37,00",
                    ),
                    consentState = ConsentState(
                        privacyOptionsRequirement = PrivacyOptionsRequirement.REQUIRED,
                    ),
                    variantMonetizationUi = VariantMonetizationUi(
                        effectiveBillingState = BillingState(EntitlementState.FREE),
                        allowAds = false,
                        overrideState = null,
                        onOverrideChange = {},
                        onAllowAdsChange = {},
                    ),
                    privacyPolicyAvailable = true,
                    onBack = {},
                    onPro = { reached += "pro" },
                    onDataHistory = { reached += "data" },
                    onPrivacyPolicy = { reached += "privacy" },
                    onPrivacyChoices = { reached += "choices" },
                    onHelp = { reached += "help" },
                    onContactSupport = { reached += "support" },
                    onAbout = { reached += "about" },
                )
            }
        }

        listOf(
            "Pro — no ads",
            "Data",
            "Privacy policy",
            "Google privacy choices",
            "Help and safety",
            "Contact support",
            "About",
        ).forEach {
            compose.onNodeWithText(it).performScrollTo().performClick()
        }
        compose.runOnIdle {
            assertEquals(
                setOf("pro", "data", "privacy", "choices", "help", "support", "about"),
                reached,
            )
        }
    }

    @Test
    fun bundledNoticesContainTheApacheLicenseText() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val notices = context.resources.openRawResource(R.raw.third_party_notices)
            .bufferedReader()
            .use { it.readText() }

        assertTrue(notices.contains("Apache License"))
        assertTrue(notices.contains("TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION"))
        assertTrue(notices.contains("END OF TERMS AND CONDITIONS"))
        assertFalse(notices.contains("licence texts are distributed with the project source"))
    }

    @Test
    fun bundledNoticesAreReachable() {
        compose.setContent { MaterialTheme { NoticesScreen {} } }
        compose.onAllNodesWithText("Apache License", substring = true).assertCountEquals(1)
    }

    private fun summary() = TraceSummary(
        file = File("session.ndjson"),
        mode = TraceMode.SINGLE_TARGET,
        startedAtEpochMillis = 0L,
        endedAtEpochMillis = 1_000L,
        durationMillis = 1_000L,
        outcome = TraceOutcome.YES,
        observationCount = 5,
        acceptedObservationCount = 4,
        observedDeviceCount = 1,
        localizedDeviceCount = 1,
    )
}

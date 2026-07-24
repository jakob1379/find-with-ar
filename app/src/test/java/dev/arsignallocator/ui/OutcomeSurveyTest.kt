package dev.arsignallocator.ui

import dev.arsignallocator.model.AppScreen
import dev.arsignallocator.trace.TraceOutcome
import dev.arsignallocator.trace.TraceOutcomeReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OutcomeSurveyTest {
    @Test
    fun `survey exposes every outcome and reason`() {
        assertEquals(
            TraceOutcome.entries.filterNot { it == TraceOutcome.INCOMPLETE },
            surveyAnswers.map { it.second },
        )
        assertEquals(TraceOutcomeReason.entries, surveyReasons.map { it.second })
    }

    @Test
    fun `note limit counts Unicode code points without splitting a surrogate pair`() {
        val limited = limitOutcomeNote("😀".repeat(501))

        assertEquals(500, limited.codePointCount(0, limited.length))
        assertEquals("😀".repeat(500), limited)
    }

    @Test
    fun `both session modes provide their required question`() {
        assertEquals(
            "Did AR Device Finder help you find the device?",
            surveyQuestionFor(AppScreen.TRACKING),
        )
        assertEquals(
            "Did Complete Scan map the devices you expected?",
            surveyQuestionFor(AppScreen.COMPLETE_SCAN),
        )
        assertEquals(SINGLE_TARGET_SURVEY_QUESTION, surveyQuestionFor(AppScreen.DEBUG))
        assertNull(surveyQuestionFor(AppScreen.DEVICES))
    }

    @Test
    fun `production tracking exits route found immediately and devices through survey`() {
        assertEquals(TraceOutcome.YES, trackingExitOutcome(TrackingExit.FOUND_TARGET))
        assertNull(trackingExitOutcome(TrackingExit.DEVICES))
    }

    @Test
    fun `ads are limited to the picker and unobscured single target tracking`() {
        DevicePage.entries.forEach { page ->
            assertEquals(
                page == DevicePage.PICKER,
                currentScreenAllowsAds(AppScreen.DEVICES, page, overlayVisible = false),
            )
        }
        assertEquals(
            true,
            currentScreenAllowsAds(
                AppScreen.TRACKING,
                DevicePage.PICKER,
                overlayVisible = false,
            ),
        )
        assertEquals(
            true,
            currentScreenAllowsAds(
                AppScreen.DEBUG,
                DevicePage.PICKER,
                overlayVisible = false,
            ),
        )
        assertEquals(
            false,
            currentScreenAllowsAds(
                AppScreen.COMPLETE_SCAN,
                DevicePage.PICKER,
                overlayVisible = false,
            ),
        )
        AppScreen.entries.forEach { screen ->
            assertEquals(
                false,
                currentScreenAllowsAds(screen, DevicePage.PICKER, overlayVisible = true),
            )
        }
    }
}

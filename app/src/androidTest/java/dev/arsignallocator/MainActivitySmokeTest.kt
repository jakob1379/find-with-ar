package dev.arsignallocator

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun productionRootHandlesStartupLifecycleAndCompletedTraceSharing() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("ui_shell", Context.MODE_PRIVATE).edit().clear().commit()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val chooserMonitor = Instrumentation.ActivityMonitor(
            IntentFilter(Intent.ACTION_CHOOSER),
            null,
            true,
        )
        instrumentation.addMonitor(chooserMonitor)

        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.onNodeWithText("Find a lost device that is still turned on")
                    .assertIsDisplayed()
                compose.onNode(hasScrollAction()).performScrollToNode(hasText("Continue"))
                compose.onNodeWithText("Continue").performClick()
                compose.onNodeWithText("Camera and nearby-device access").assertIsDisplayed()

                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.moveToState(Lifecycle.State.RESUMED)
                compose.onNodeWithText("Camera and nearby-device access").assertIsDisplayed()

                scenario.onActivity { activity ->
                    val trace = File(activity.filesDir, "traces/smoke.ndjson").apply {
                        parentFile!!.mkdirs()
                        writeText("{}")
                    }
                    activity.shareTrace(trace)
                }
                instrumentation.waitForIdleSync()
                assertEquals(1, chooserMonitor.hits)
            }
        } finally {
            instrumentation.removeMonitor(chooserMonitor)
            File(context.filesDir, "traces/smoke.ndjson").delete()
            context.getSharedPreferences("ui_shell", Context.MODE_PRIVATE).edit().clear().commit()
        }
    }
}

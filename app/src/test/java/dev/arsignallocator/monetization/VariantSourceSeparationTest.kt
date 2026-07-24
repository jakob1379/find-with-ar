package dev.arsignallocator.monetization

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VariantSourceSeparationTest {
    @Test
    fun `main and release sources contain no debug monetization implementation or test IDs`() {
        val project = File(requireNotNull(System.getProperty("user.dir"))).let {
            if (File(it, "src").isDirectory) it else File(it, "app")
        }
        val main = File(project, "src/main").walkTopDown()
            .filter(File::isFile)
            .joinToString("\n", transform = File::readText)
        val release = File(
            project,
            "src/release/java/dev/arsignallocator/ui/VariantMonetizationUi.kt",
        ).readText()
        val forbidden = listOf(
            "DebugEntitlementOverride",
            "Developer monetization",
            "Fake free",
            "Fake Pro",
            "Show test ads",
            "ca-app-pub-3940256099942544",
        )

        forbidden.forEach {
            assertFalse("main source contains $it", it in main)
            assertFalse("release facade contains $it", it in release)
        }
        assertTrue("release facade must be inert", release.contains("= Unit"))
    }
}

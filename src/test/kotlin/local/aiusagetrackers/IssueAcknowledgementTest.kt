package local.aiusagetrackers

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IssueAcknowledgementTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun acknowledgementsSurviveRestartAndStaySeparate() {
        val path = temp.newFolder().toPath().resolve("usage.sqlite")
        UsageDatabase(path).use {
            assertTrue(it.acknowledgedIssues("warnings").isEmpty())
            it.acknowledgeIssues("warnings", setOf("old"))
            it.acknowledgeIssues("warnings", setOf("second"))
            it.acknowledgeIssues("integrity", setOf("damaged"))
        }
        UsageDatabase(path, true).use {
            assertEquals(setOf("old", "second"), it.acknowledgedIssues("warnings"))
            assertEquals(setOf("damaged"), it.acknowledgedIssues("integrity"))
        }
    }

    @Test fun acknowledgedWarningsStopAlarmingButNewIssuesRemainVisible() {
        val snapshot = UsageSnapshot(
            loading = false, operationalIssues = setOf("old", "new"),
            integrityIssues = setOf("Session identity missing; ambiguous copy excluded"), acknowledgedWarnings = setOf("old"),
            acknowledgedIntegrity = setOf("Session identity missing; ambiguous copy excluded"))
        assertEquals(listOf("new"), WidgetWarnings.messages(snapshot, emptyList()))
        val pages = UsageDetailPages.capture(snapshot, DisplayPreferences(), ZoneOffset.UTC, AnalyticsDisplayOptions())
        assertTrue(pages.warnings.contains("[Acknowledged] old"))
        assertTrue(pages.integrity.contains("[Acknowledged] Skipped a possible duplicate because its session ID was missing."))
        assertTrue("old" !in pages.warningIssues)
        assertTrue("new" in pages.warningIssues)
        assertTrue(pages.integrityFindings.isEmpty())
    }
}

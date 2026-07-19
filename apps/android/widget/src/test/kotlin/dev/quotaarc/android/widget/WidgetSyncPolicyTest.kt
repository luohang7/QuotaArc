package dev.quotaarc.android.widget

import dev.quotaarc.android.data.connection.ConnectionRestoreResult
import dev.quotaarc.android.data.connection.DeviceCapability
import dev.quotaarc.android.data.connection.DeviceConnectionMetadata
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetSyncPolicyTest {
    @Test
    fun `periodic work is one unique 30 minute keep schedule`() {
        val spec = WidgetSyncPolicy.periodicSpec

        assertEquals("quotaarc.widget.periodic", spec.uniqueName)
        assertEquals(30L, spec.intervalMinutes)
        assertTrue(spec.keepExisting)
        assertTrue(requireNotNull(spec.intervalMinutes) >= 15L)
    }

    @Test
    fun `manual work is a distinct unique keep request`() {
        val periodic = WidgetSyncPolicy.periodicSpec
        val manual = WidgetSyncPolicy.manualSpec

        assertEquals("quotaarc.widget.manual", manual.uniqueName)
        assertNull(manual.intervalMinutes)
        assertTrue(manual.keepExisting)
        assertNotEquals(periodic.uniqueName, manual.uniqueName)
    }

    @Test
    fun `periodic work requires committed connection metadata`() {
        assertFalse(WidgetSyncPolicy.canSchedulePeriodic(false))
        assertTrue(WidgetSyncPolicy.canSchedulePeriodic(true))
    }

    @Test
    fun `authoritative ready restore overrides a missing scheduling hint`() {
        assertTrue(
            connectionReadyForScheduling(
                restoreResult = ConnectionRestoreResult.Ready(METADATA),
                metadataHint = false,
            ),
        )
    }

    @Test
    fun `authoritative unavailable or absent restore overrides a stale hint`() {
        assertFalse(
            connectionReadyForScheduling(
                restoreResult = ConnectionRestoreResult.CredentialUnavailable(METADATA),
                metadataHint = true,
            ),
        )
        assertFalse(
            connectionReadyForScheduling(
                restoreResult = ConnectionRestoreResult.Absent,
                metadataHint = true,
            ),
        )
        assertFalse(
            connectionReadyForScheduling(
                restoreResult = ConnectionRestoreResult.Invalid,
                metadataHint = true,
            ),
        )
    }

    @Test
    fun `metadata hint is used only while restore is pending`() {
        assertTrue(connectionReadyForScheduling(null, metadataHint = true))
        assertFalse(connectionReadyForScheduling(null, metadataHint = false))
    }

    @Test
    fun `refresh indicator expires and cannot create a minute loop`() {
        val started = 1_000L
        val inside = started + Duration.ofMinutes(9).toMillis()
        val expired = started + Duration.ofMinutes(11).toMillis()

        assertTrue(WidgetRefreshStateStore.isActive(started, inside))
        assertFalse(WidgetRefreshStateStore.isActive(started, expired))
        assertFalse(WidgetRefreshStateStore.isActive(started, started - 1))
    }

    private companion object {
        val METADATA = DeviceConnectionMetadata(
            endpoint = "https://collector.example:8443",
            collectorId = "qac_abcdefghijklmnopqrstuv",
            certificateSha256 =
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            deviceId = "abcdefghijklmnop",
            scopes = setOf(
                DeviceCapability.SUMMARY_READ,
                DeviceCapability.REFRESH_WRITE,
            ),
        )
    }
}

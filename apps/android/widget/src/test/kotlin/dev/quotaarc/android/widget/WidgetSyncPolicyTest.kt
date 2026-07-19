package dev.quotaarc.android.widget

import dev.quotaarc.android.data.DeviceTransportGate
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
    fun `gate closed release does not schedule periodic failure writes`() {
        assertFalse(
            WidgetSyncPolicy.canSchedulePeriodic(DeviceTransportGate.CLOSED),
        )
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
}

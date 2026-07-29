package servicoop.comunic.panelito.core.time

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class TimeProviderTest {
    private val provider = SystemTimeProvider(
        Clock.fixed(Instant.parse("2026-07-27T02:30:00Z"), ZoneOffset.UTC),
    )

    @Test
    fun `presenta UTC menos tres y trata legado sin zona como UTC`() {
        assertEquals(
            "2026-07-26T23:30:00",
            provider.formatForPresentation("2026-07-27T02:30:00Z"),
        )
        assertEquals(
            "2026-07-26T23:30:00",
            provider.formatForPresentation("2026-07-27 02:30:00"),
        )
    }
}

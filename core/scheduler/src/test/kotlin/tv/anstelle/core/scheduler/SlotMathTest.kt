package tv.anstelle.core.scheduler

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class SlotMathTest {
    private fun utc(zone: String, local: String): Long =
        LocalDateTime.parse(local).atZone(ZoneId.of(zone)).toInstant().toEpochMilli()

    private fun offsetMs(zone: String, utcMs: Long): Long =
        ZoneId.of(zone).rules.getOffset(Instant.ofEpochMilli(utcMs)).totalSeconds * 1000L

    @Test
    fun alignsUpToNextQuarterHour() {
        val t = utc("UTC", "2026-10-05T20:07:30")
        assertEquals(utc("UTC", "2026-10-05T20:15:00"), SlotMath.alignUp(t, 15, 0))
    }

    @Test
    fun boundaryIsAlreadyAligned() {
        val t = utc("UTC", "2026-10-05T20:30:00")
        assertEquals(t, SlotMath.alignUp(t, 30, 0))
        assertEquals(t, SlotMath.alignDown(t, 30, 0))
    }

    @Test
    fun hourSlotsFollowLocalHourInHalfHourOffsetZone() {
        val zone = "Asia/Kolkata" // UTC+05:30
        val t = utc(zone, "2026-10-05T20:10:00")
        val aligned = SlotMath.alignUp(t, 60, offsetMs(zone, t))
        assertEquals(utc(zone, "2026-10-05T21:00:00"), aligned)
    }

    @Test
    fun alignmentOffReturnsInput() {
        val t = 1_234_567L
        assertEquals(t, SlotMath.alignUp(t, 0, 0))
        assertEquals(t, SlotMath.alignDown(t, 0, 0))
    }

    @Test
    fun alignDownBeforeEpochUsesFloor() {
        assertEquals(-15 * SlotMath.MINUTE_MS, SlotMath.alignDown(-1L, 15, 0))
    }
}

package tv.anstelle.core.jellyfin

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/*
 * The Jellyfin SDK represents server timestamps as LocalDateTime in the device's time zone:
 * it converts UTC to the device zone when decoding JSON, and sends query parameters with
 * the device's UTC offset. These helpers convert at that boundary. In the repeated hour of
 * a DST change they resolve to the earlier offset, which errs towards fetching more.
 */

fun LocalDateTime.toEpochMs(): Long = atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

fun sdkDateTime(epochMs: Long): LocalDateTime =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDateTime()

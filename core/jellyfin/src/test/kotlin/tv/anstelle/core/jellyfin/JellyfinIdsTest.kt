package tv.anstelle.core.jellyfin

import org.jellyfin.sdk.model.serializer.toUUID
import kotlin.test.Test
import kotlin.test.assertEquals

class JellyfinIdsTest {
    @Test
    fun `ids are stored as 32 lowercase hex digits`() {
        assertEquals("fa11000000000000000000000000002a", normalizeJellyfinId("FA110000-0000-0000-0000-00000000002A"))
        assertEquals("fa11000000000000000000000000002a", "fa11000000000000000000000000002a".toUUID().toJellyfinId())
    }
}

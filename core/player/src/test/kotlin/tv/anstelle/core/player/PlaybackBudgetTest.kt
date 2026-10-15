package tv.anstelle.core.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackBudgetTest {
    @Test fun `two gigabyte TV boxes use the smaller budget even without the low RAM flag`() {
        assertTrue(PlaybackBudget.isLowMemory(false, 2L * 1024 * 1024 * 1024, 256))
        assertTrue(PlaybackBudget.isLowMemory(true, 4L * 1024 * 1024 * 1024, 256))
        assertTrue(PlaybackBudget.isLowMemory(false, 4L * 1024 * 1024 * 1024, 128))
        assertFalse(PlaybackBudget.isLowMemory(false, 4L * 1024 * 1024 * 1024, 256))
    }
}

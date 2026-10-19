package tv.anstelle.feature.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerPanelTest {
    @Test
    fun backReturnsFromEveryPickerBeforeDismissingControls() {
        for (picker in listOf(PlayerPanel.AUDIO, PlayerPanel.SUBTITLES, PlayerPanel.EPISODES)) {
            assertTrue("$picker must stay open while the viewer chooses", picker.isPicker)
            val returned = picker.afterBack()
            assertEquals("Back from $picker must keep the controls visible", PlayerPanel.CONTROLS, returned)
            assertNull("A subsequent Back dismisses the controls", returned.afterBack())
        }
    }

    @Test
    fun backWithoutAPickerDoesNotOpenControls() {
        assertFalse(PlayerPanel.CONTROLS.isPicker)
        assertNull(PlayerPanel.CONTROLS.afterBack())
        assertNull(null.afterBack())
    }
}

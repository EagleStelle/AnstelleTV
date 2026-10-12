package tv.anstelle.core.data.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PeerTransferTest {
    @Test
    fun `typed addresses become web control's address`() {
        assertEquals("http://192.168.1.20:8765", PeerTransfer.normalize("192.168.1.20"))
        assertEquals("http://192.168.1.20:9000", PeerTransfer.normalize(" 192.168.1.20:9000 "))
        assertEquals("http://living-room.local:8765", PeerTransfer.normalize("http://living-room.local:8765/"))
        assertEquals("http://[fd00::5]:8765", PeerTransfer.normalize("[fd00::5]"))
    }

    @Test
    fun `what isn't an address is refused`() {
        assertNull(PeerTransfer.normalize(""))
        assertNull(PeerTransfer.normalize("https://192.168.1.20:8765"))
        assertNull(PeerTransfer.normalize("192.168.1.20:8765/api"))
        assertNull(PeerTransfer.normalize("living room"))
    }
}

package tv.anstelle.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ProfileAccessTest {
    @Test
    fun `nothing is kept from an open profile`() {
        val access = ProfileAccess(1, null)
        assertTrue(access.isOpen)
        assertTrue(access.allows(7, null))
        assertFalse(access.isHidden(7))
    }

    @Test
    fun `hidden items are kept back whatever their rating`() {
        val access = ProfileAccess(1, null, listOf(30L, 10L, 20L))
        assertFalse(access.isOpen)
        assertTrue(access.isHidden(10))
        assertTrue(access.isHidden(30))
        assertFalse(access.isHidden(15))
        assertFalse(access.allows(20, 0))
        assertTrue(access.allows(25, null))
    }

    @Test
    fun `the rating limit still applies, unrated items never pass it`() {
        val access = ProfileAccess(1, 13, listOf(5L))
        assertTrue(access.allows(1, 13))
        assertFalse(access.allows(1, 16))
        assertFalse(access.allows(1, null))
        assertFalse(access.allows(5, 0))
    }

    @Test
    fun `equal access compares by content, in any order`() {
        assertEquals(ProfileAccess(1, 13, listOf(2L, 1L)), ProfileAccess(1, 13, listOf(1L, 2L)))
        assertEquals(ProfileAccess(1, 13, listOf(2L, 1L)).hashCode(), ProfileAccess(1, 13, listOf(1L, 2L)).hashCode())
        assertNotEquals(ProfileAccess(1, 13, listOf(1L)), ProfileAccess(1, 13, listOf(1L, 2L)))
        assertNotEquals(ProfileAccess(1, 13), ProfileAccess(2, 13))
    }
}

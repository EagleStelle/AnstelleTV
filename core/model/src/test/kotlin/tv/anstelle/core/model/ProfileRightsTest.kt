package tv.anstelle.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfileRightsTest {
    private fun profile(id: Long, role: ProfileRole, pin: Boolean = false) = Profile(
        id = id, name = "P$id", pictureUrl = null, role = role, maxRatingScore = null, hasPin = pin, themeId = null,
    )

    private val owner = profile(1, ProfileRole.OWNER, pin = true)
    private val admin = profile(2, ProfileRole.ADMIN, pin = true)
    private val otherAdmin = profile(3, ProfileRole.ADMIN)
    private val member = profile(4, ProfileRole.MEMBER, pin = true)
    private val otherMember = profile(5, ProfileRole.MEMBER)

    @Test
    fun `the owner changes everyone, admins change members, members only themselves`() {
        for (target in listOf(owner, admin, otherAdmin, member, otherMember)) assertTrue(ProfileRights.canEdit(owner, target))
        assertTrue(ProfileRights.canEdit(admin, admin))
        assertTrue(ProfileRights.canEdit(admin, member))
        assertFalse(ProfileRights.canEdit(admin, otherAdmin))
        assertFalse(ProfileRights.canEdit(admin, owner))
        assertTrue(ProfileRights.canEdit(member, member))
        assertFalse(ProfileRights.canEdit(member, otherMember))
        assertFalse(ProfileRights.canEdit(member, admin))
    }

    @Test
    fun `a PIN is asked only for changing one's own profile`() {
        assertTrue(ProfileRights.needsPin(owner, owner))
        assertFalse(ProfileRights.needsPin(owner, admin))
        assertFalse(ProfileRights.needsPin(admin, member))
        assertTrue(ProfileRights.needsPin(member, member))
        assertFalse(ProfileRights.needsPin(otherMember, otherMember))
    }

    @Test
    fun `only the owner and admins manage the app`() {
        assertTrue(ProfileRights.canManageApp(owner))
        assertTrue(ProfileRights.canManageApp(admin))
        assertFalse(ProfileRights.canManageApp(member))
    }

    @Test
    fun `parental controls are set for members by the owner or an admin`() {
        assertTrue(ProfileRights.canSetParental(owner, member))
        assertTrue(ProfileRights.canSetParental(admin, member))
        assertFalse(ProfileRights.canSetParental(member, member))
        assertFalse(ProfileRights.canSetParental(owner, admin))
        assertFalse(ProfileRights.canSetParental(admin, otherAdmin))
    }

    @Test
    fun `only the owner makes admins or hands over ownership`() {
        assertTrue(ProfileRights.canSetAdmin(owner, member))
        assertTrue(ProfileRights.canSetAdmin(owner, admin))
        assertFalse(ProfileRights.canSetAdmin(owner, owner))
        assertFalse(ProfileRights.canSetAdmin(admin, member))
        assertTrue(ProfileRights.canTransferOwnership(owner, admin))
        assertFalse(ProfileRights.canTransferOwnership(admin, member))
    }

    @Test
    fun `only the owner and admins add profiles, and the owner is never deleted`() {
        assertTrue(ProfileRights.canAdd(owner))
        assertTrue(ProfileRights.canAdd(admin))
        assertFalse(ProfileRights.canAdd(member))
        assertFalse(ProfileRights.canDelete(admin, owner))
        assertFalse(ProfileRights.canDelete(owner, owner))
        assertTrue(ProfileRights.canDelete(owner, admin))
        assertTrue(ProfileRights.canDelete(admin, member))
        assertFalse(ProfileRights.canDelete(admin, otherAdmin))
        assertFalse(ProfileRights.canDelete(member, member))
    }

    @Test
    fun `members and disconnected admins can sign out only themselves`() {
        assertTrue(ProfileRights.canSignOut(member, member, ownsServer = false))
        assertTrue(ProfileRights.canSignOut(admin, admin, ownsServer = false))
        assertFalse(ProfileRights.canSignOut(admin, admin, ownsServer = true))
        assertFalse(ProfileRights.canSignOut(owner, owner, ownsServer = false))
        assertFalse(ProfileRights.canSignOut(owner, admin, ownsServer = false))
        assertFalse(ProfileRights.canSignOut(admin, member, ownsServer = false))
    }

    @Test
    fun `server owning profiles cannot be deleted directly even by owner`() {
        assertFalse(ProfileRights.canDelete(owner, admin, ownsServer = true))
        assertTrue(ProfileRights.canDelete(owner, admin, ownsServer = false))
        assertFalse(ProfileRights.canDelete(owner, member, ownsServer = true))
        assertFalse(ProfileRights.canSetAdmin(owner, admin, ownsServer = true))
        assertTrue(ProfileRights.canTransferOwnership(owner, admin))
    }

    @Test
    fun `only app owner and connection owner can remove a server`() {
        assertTrue(ServerRights.canRemove(owner.id, owner.id, admin.id))
        assertTrue(ServerRights.canRemove(admin.id, owner.id, admin.id))
        assertFalse(ServerRights.canRemove(otherAdmin.id, owner.id, admin.id))
        assertFalse(ServerRights.canRemove(member.id, owner.id, admin.id))
    }

    @Test
    fun `rating levels come from the server's table, kept to what the library reaches`() {
        val table = listOf(
            RatingLevel(0, "G"), RatingLevel(0, "TV-G"), RatingLevel(0, "TV-Y"),
            RatingLevel(7, "TV-Y7"), RatingLevel(7, "TV-Y7-FV"),
            RatingLevel(10, "PG"), RatingLevel(10, "TV-PG"),
            RatingLevel(13, "PG-13"), RatingLevel(14, "TV-14"),
            RatingLevel(17, "R"), RatingLevel(17, "TV-MA"),
            RatingLevel(18, "NC-17"), RatingLevel(21, "X"),
        )
        val carried = listOf(
            CarriedRating("TV-G", 0, 15), CarriedRating("6+", 6, 14),
            CarriedRating("TV-PG", 10, 236), CarriedRating("PG", 10, 77),
            CarriedRating("PG13", 13, 25), CarriedRating("TV-14", 14, 2417),
            CarriedRating("NC16", 16, 65), CarriedRating("TV-MA", 17, 460), CarriedRating("R", 17, 18),
            CarriedRating("M18", 18, 14),
        )
        assertEquals(
            listOf(
                RatingLevel(0, "TV-G"),
                // No item carries TV-Y7, but "6+" falls under it: named by the table's first.
                RatingLevel(7, "TV-Y7"),
                RatingLevel(10, "TV-PG / PG"),
                // "PG13" on the items is the table's PG-13, spelled the table's way.
                RatingLevel(13, "PG-13"),
                RatingLevel(14, "TV-14"),
                RatingLevel(17, "TV-MA / R"),
                RatingLevel(18, "NC-17"),
                // Nothing scores over 18, so X would change nothing and is left out.
            ),
            RatingLimits.levels(table, carried),
        )
    }

    @Test
    fun `without a table the library's own ratings name the levels`() {
        val carried = listOf(CarriedRating("PG", 10, 5), CarriedRating("TV-PG", 10, 9), CarriedRating("12", 12, 3))
        assertEquals(listOf(RatingLevel(10, "TV-PG"), RatingLevel(12, "12")), RatingLimits.levels(emptyList(), carried))
    }

    @Test
    fun `rating limits are named by their levels`() {
        val levels = listOf(RatingLevel(0, "G"), RatingLevel(13, "PG-13"), RatingLevel(16, "R-16"), RatingLevel(18, "R"))
        assertEquals(listOf(null, 18, 16, 13, 0), RatingLimits.choices(levels))
        assertEquals("R-16", RatingLimits.label(16, levels))
        assertEquals("PG-13", RatingLimits.label(14, levels))
        assertEquals("No limit", RatingLimits.label(null, levels))
        assertEquals("Age 7", RatingLimits.label(7, emptyList()))
    }

    @Test
    fun `a limit lets through what is rated at or under it, never anything unrated`() {
        assertTrue(RatingLimits.allows(null, null))
        assertTrue(RatingLimits.allows(null, 18))
        assertTrue(RatingLimits.allows(13, 13))
        assertTrue(RatingLimits.allows(13, 0))
        assertFalse(RatingLimits.allows(13, 16))
        assertFalse(RatingLimits.allows(13, null))
    }
}

package tv.anstelle.core.data

import tv.anstelle.core.model.ProfileRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ServerRemovalTest {
    private fun profile(id: Long, role: ProfileRole, accounts: Set<Long>, signedIn: Set<Long> = accounts) =
        RemovalProfile(id, "Profile $id", role, accounts, signedIn)

    private val owner = profile(1, ProfileRole.OWNER, setOf(10))
    private val admin = profile(2, ProfileRole.ADMIN, setOf(20))
    private val member = profile(3, ProfileRole.MEMBER, setOf(20))

    @Test fun `owner removal requires an explicit surviving admin`() {
        val plan = serverRemovalPlan(10, setOf(10, 20), listOf(owner, admin, member))
        assertTrue(plan.requiresTransfer)
        assertFalse(plan.resetsApp)
        assertEquals(setOf(1L), plan.deletedProfileIds)
        assertEquals(listOf(RemovalAdmin(2, "Profile 2")), plan.admins)
        assertEquals("owner_transfer_required", assertFailsWith<ServerRemovalException> { plan.validate(1, null) }.code)
        assertEquals("invalid_owner", assertFailsWith<ServerRemovalException> { plan.validate(1, 3) }.code)
        assertEquals("owner_only", assertFailsWith<ServerRemovalException> { plan.validate(2, 2) }.code)
        plan.validate(1, 2)
    }

    @Test fun `profiles with a valid account elsewhere survive`() {
        val linkedOwner = owner.copy(accountServers = setOf(10, 20), signedInServers = setOf(10, 20))
        val plan = serverRemovalPlan(10, setOf(10, 20), listOf(linkedOwner, admin))
        assertFalse(plan.requiresTransfer)
        assertTrue(plan.deletedProfileIds.isEmpty())
        plan.validate(2, null)
        assertFailsWith<ServerRemovalException> { plan.validate(1, 2) }
    }

    @Test fun `deleting a member server deletes orphan profiles but keeps linked ones`() {
        val linked = member.copy(accountServers = setOf(10, 20), signedInServers = setOf(10, 20))
        val plan = serverRemovalPlan(20, setOf(10, 20), listOf(owner, admin, linked))
        assertFalse(plan.requiresTransfer)
        assertFalse(plan.resetsApp)
        assertEquals(setOf(2L), plan.deletedProfileIds)
    }

    @Test fun `last server resets including stale profiles and servers`() {
        val expiredAdmin = admin.copy(signedInServers = emptySet())
        val plan = serverRemovalPlan(10, setOf(10), listOf(owner, expiredAdmin, member))
        assertTrue(plan.resetsApp)
        assertFalse(plan.requiresTransfer)
        assertEquals(setOf(1L, 2L, 3L), plan.deletedProfileIds)
        plan.validate(1, null)
        assertEquals("owner_only", assertFailsWith<ServerRemovalException> { plan.validate(2, null) }.code)
    }

    @Test fun `expired and departing admins cannot receive ownership`() {
        val expired = admin.copy(signedInServers = emptySet())
        val departing = profile(4, ProfileRole.ADMIN, setOf(10))
        val plan = serverRemovalPlan(10, setOf(10, 20), listOf(owner, expired, departing, member))
        assertTrue(plan.requiresTransfer)
        assertTrue(plan.admins.isEmpty())
        assertFailsWith<ServerRemovalException> { plan.validate(1, 2) }
        assertFailsWith<ServerRemovalException> { plan.validate(1, 4) }
    }

    @Test fun `an expired alternate account does not keep the departing owner`() {
        val expiredAlternate = owner.copy(accountServers = setOf(10, 20))
        val plan = serverRemovalPlan(10, setOf(10, 20), listOf(expiredAlternate, admin))
        assertTrue(plan.requiresTransfer)
        assertEquals(setOf(1L), plan.deletedProfileIds)
    }

    @Test fun `another signed-in server prevents reset even with no other profiles`() {
        val plan = serverRemovalPlan(10, setOf(10, 20), listOf(owner))
        assertFalse(plan.resetsApp)
        assertTrue(plan.requiresTransfer)
        assertFailsWith<ServerRemovalException> { plan.validate(1, null) }
    }
}

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
    private val servers = listOf(RemovalServer(10, "Owner server", 1, true), RemovalServer(20, "Admin server", 2, true))

    @Test fun `owner removal requires an explicit surviving admin`() {
        val plan = serverRemovalPlan(10, servers, listOf(owner, admin, member))
        assertTrue(plan.requiresTransfer)
        assertFalse(plan.resetsApp)
        assertEquals(setOf(1L), plan.deletedProfileIds)
        assertEquals(listOf(RemovalAdmin(2, "Profile 2")), plan.admins)
        assertEquals("owner_transfer_required", assertFailsWith<ServerRemovalException> { plan.validate(1, null) }.code)
        assertEquals("invalid_owner", assertFailsWith<ServerRemovalException> { plan.validate(1, 3) }.code)
        assertEquals("owner_only", assertFailsWith<ServerRemovalException> { plan.validate(2, 2) }.code)
        plan.validate(1, 2)
    }

    @Test fun `linked profiles are deleted even with valid accounts elsewhere`() {
        val linkedOwner = owner.copy(accountServers = setOf(10, 20), signedInServers = setOf(10, 20))
        val plan = serverRemovalPlan(10, servers, listOf(linkedOwner, admin))
        assertTrue(plan.requiresTransfer)
        assertEquals(setOf(1L), plan.deletedProfileIds)
        assertFailsWith<ServerRemovalException> { plan.validate(2, null) }
        plan.validate(1, 2)
    }

    @Test fun `admin removing own server deletes every linked profile`() {
        val linked = member.copy(accountServers = setOf(10, 20), signedInServers = setOf(10, 20))
        val plan = serverRemovalPlan(20, servers, listOf(owner, admin, linked))
        assertFalse(plan.requiresTransfer)
        assertFalse(plan.resetsApp)
        assertEquals(setOf(2L, 3L), plan.deletedProfileIds)
        assertEquals(setOf(20L), plan.deletedServerIds)
        plan.validate(2, null)
        plan.validate(1, null)
        assertEquals("owner_only", assertFailsWith<ServerRemovalException> { plan.validate(3, null) }.code)
        assertTrue(plan.explanation.contains("Profile 2, Profile 3"))
        assertTrue(plan.explanation.contains("sign-ins on every server"))
    }

    @Test fun `last server resets including stale profiles and servers`() {
        val expiredAdmin = admin.copy(signedInServers = emptySet())
        val plan = serverRemovalPlan(10, servers.map { if (it.id == 20L) it.copy(signedIn = false) else it }, listOf(owner, expiredAdmin, member))
        assertTrue(plan.resetsApp)
        assertFalse(plan.requiresTransfer)
        assertEquals(setOf(1L, 2L, 3L), plan.deletedProfileIds)
        plan.validate(1, null)
        assertEquals("owner_only", assertFailsWith<ServerRemovalException> { plan.validate(2, null) }.code)
    }

    @Test fun `expired and departing admins cannot receive ownership`() {
        val expired = admin.copy(signedInServers = emptySet())
        val departing = profile(4, ProfileRole.ADMIN, setOf(10))
        val plan = serverRemovalPlan(10, servers, listOf(owner, expired, departing, member))
        assertTrue(plan.requiresTransfer)
        assertTrue(plan.admins.isEmpty())
        assertFailsWith<ServerRemovalException> { plan.validate(1, 2) }
        assertFailsWith<ServerRemovalException> { plan.validate(1, 4) }
    }

    @Test fun `an expired alternate account does not keep the departing owner`() {
        val expiredAlternate = owner.copy(accountServers = setOf(10, 20))
        val plan = serverRemovalPlan(10, servers, listOf(expiredAlternate, admin))
        assertTrue(plan.requiresTransfer)
        assertEquals(setOf(1L), plan.deletedProfileIds)
    }

    @Test fun `another signed-in server prevents reset even with no other profiles`() {
        val plan = serverRemovalPlan(10, servers, listOf(owner))
        assertFalse(plan.resetsApp)
        assertTrue(plan.requiresTransfer)
        assertFailsWith<ServerRemovalException> { plan.validate(1, null) }
    }

    @Test fun `signing into another server grants no deletion rights`() {
        val visitor = profile(4, ProfileRole.ADMIN, setOf(10, 20))
        val plan = serverRemovalPlan(20, servers, listOf(owner, admin, member, visitor))
        assertFalse(plan.canRemove(4))
        assertEquals("owner_only", assertFailsWith<ServerRemovalException> { plan.validate(4, null) }.code)
    }

    @Test fun `expired connection still belongs to its admin`() {
        val plan = serverRemovalPlan(20, servers.map { if (it.id == 20L) it.copy(signedIn = false) else it }, listOf(owner, admin, member))
        plan.validate(2, null)
        assertEquals(setOf(2L, 3L), plan.deletedProfileIds)
    }

    @Test fun `cascade never leaves servers owned by deleted profiles`() {
        val linkedAdmin = admin.copy(accountServers = setOf(10, 20))
        val plan = serverRemovalPlan(10, servers, listOf(owner, linkedAdmin, member))
        assertTrue(plan.resetsApp)
        assertEquals(setOf(10L, 20L), plan.deletedServerIds)
        assertEquals(setOf(1L, 2L, 3L), plan.deletedProfileIds)
        assertFalse(plan.canRemove(2))
    }

    @Test fun `admin cannot indirectly delete someone else's server`() {
        val otherAdmin = profile(4, ProfileRole.ADMIN, setOf(20, 30))
        val moreServers = servers + RemovalServer(30, "Other server", 4, true)
        val plan = serverRemovalPlan(20, moreServers, listOf(owner, admin, member, otherAdmin))
        assertEquals(setOf(20L, 30L), plan.deletedServerIds)
        assertFalse(plan.canRemove(2))
        plan.validate(1, null)
    }
}

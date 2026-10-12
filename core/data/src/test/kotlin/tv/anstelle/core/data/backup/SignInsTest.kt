package tv.anstelle.core.data.backup

import kotlin.test.Test
import kotlin.test.assertEquals

class SignInsTest {
    private val home = SignIns.Server("jf-home", "http://192.168.1.20:8096", "u-here", "Alex", signedIn = true)
    private val cabin = SignIns.Server("jf-cabin", "http://10.0.0.5:8096", "u-cabin", "Alex", signedIn = true)
    private val old = SignIns.Server("jf-old", "http://10.0.0.9:8096", null, null, signedIn = false)

    @Test
    fun `a server signed in here and in the backup stays signed in`() {
        val plan = SignIns.plan(listOf(home, cabin, old), emptyList(), backupServers = setOf("jf-home", "jf-old"), backupAccounts = emptySet())
        // Home keeps this device's user and address; the cabin isn't in the backup.
        assertEquals(mapOf("jf-home" to home), plan.kept)
        assertEquals(listOf(cabin), plan.dropServers)
    }

    @Test
    fun `a profile sign-in stays only where the backup has the same one`() {
        val same = SignIns.Account("jf-home", "profile-aaaa", home.baseUrl, signedIn = true)
        val other = SignIns.Account("jf-home", "profile-bbbb", home.baseUrl, signedIn = true)
        val gone = SignIns.Account("jf-cabin", "profile-cccc", cabin.baseUrl, signedIn = false)
        val plan = SignIns.plan(listOf(home), listOf(same, other, gone), setOf("jf-home"), setOf("jf-home" to "profile-aaaa"))
        // A sign-in with no token has nothing to sign out.
        assertEquals(listOf(other), plan.dropAccounts)
    }
}

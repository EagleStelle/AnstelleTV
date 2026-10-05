package tv.anstelle.webserver.pairing

import tv.anstelle.core.database.dao.PairedClientDao
import tv.anstelle.core.database.entity.PairedClientEntity
import javax.inject.Inject

/** Storage for paired browsers. Narrow on purpose so pairing can be tested without Room. */
interface PairedClientStore {
    suspend fun insert(client: PairedClientEntity): Long
    suspend fun byTokenHash(tokenHash: String): PairedClientEntity?
    suspend fun touch(id: Long, seenUtcMs: Long, address: String?)
    suspend fun delete(id: Long)
}

internal class RoomPairedClientStore @Inject constructor(
    private val dao: PairedClientDao,
) : PairedClientStore {
    override suspend fun insert(client: PairedClientEntity) = dao.insert(client)
    override suspend fun byTokenHash(tokenHash: String) = dao.byTokenHash(tokenHash)
    override suspend fun touch(id: Long, seenUtcMs: Long, address: String?) = dao.touch(id, seenUtcMs, address)
    override suspend fun delete(id: Long) = dao.delete(id)
}

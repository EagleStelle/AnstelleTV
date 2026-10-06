package tv.anstelle.webserver.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import tv.anstelle.core.data.ProfileRepository
import tv.anstelle.core.data.channels.ChannelRepository
import tv.anstelle.core.database.dao.ChannelDao
import javax.inject.Inject

/** Data the REST API reads and writes. Kept as an interface so routes can be tested without Room. */
interface ApiDataSource {
    suspend fun profiles(): List<ProfileDto>
    suspend fun channels(): List<ChannelDto>

    /** The channel's logo as PNG bytes, or null when it has none or doesn't exist. */
    suspend fun channelLogo(channelId: Long): ByteArray?

    /**
     * Stores an uploaded logo. Returns false when there is no such channel; throws
     * [java.io.IOException] when [bytes] is not an image.
     */
    suspend fun setChannelLogo(channelId: Long, bytes: ByteArray): Boolean

    /** Returns false when there is no such channel. */
    suspend fun clearChannelLogo(channelId: Long): Boolean
}

internal class RoomApiDataSource @Inject constructor(
    private val profiles: ProfileRepository,
    private val channelDao: ChannelDao,
    private val channels: ChannelRepository,
) : ApiDataSource {
    override suspend fun profiles(): List<ProfileDto> =
        profiles.observeProfiles().first().map { ProfileDto(it.id, it.name, it.isKid, it.hasPin) }

    override suspend fun channels(): List<ChannelDto> {
        val profile = profiles.ensureActiveProfile()
        return channelDao.observeAll(profile.id).first().map {
            // Each upload gets a new file name, so the URL changes with the logo and can be cached.
            val logoUrl = it.logoUri?.let { uri -> "/api/channels/${it.id}/logo?v=${uri.substringAfterLast('/').substringBefore('.')}" }
            ChannelDto(it.id, it.number, it.name, logoUrl, it.isHidden, it.isFavorite, it.groupId)
        }
    }

    override suspend fun channelLogo(channelId: Long): ByteArray? {
        val file = channels.logoFile(channelId) ?: return null
        return withContext(Dispatchers.IO) { file.readBytes() }
    }

    override suspend fun setChannelLogo(channelId: Long, bytes: ByteArray): Boolean {
        if (channelDao.byId(channelId) == null) return false
        channels.setLogo(channelId, bytes)
        return true
    }

    override suspend fun clearChannelLogo(channelId: Long): Boolean {
        if (channelDao.byId(channelId) == null) return false
        channels.clearLogo(channelId)
        return true
    }
}

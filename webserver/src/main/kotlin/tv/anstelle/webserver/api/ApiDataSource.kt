package tv.anstelle.webserver.api

import kotlinx.coroutines.flow.first
import tv.anstelle.core.data.ProfileRepository
import tv.anstelle.core.database.dao.ChannelDao
import javax.inject.Inject

/** Data the REST API reads. Kept as an interface so routes can be tested without Room. */
interface ApiDataSource {
    suspend fun profiles(): List<ProfileDto>
    suspend fun channels(): List<ChannelDto>
}

internal class RoomApiDataSource @Inject constructor(
    private val profiles: ProfileRepository,
    private val channelDao: ChannelDao,
) : ApiDataSource {
    override suspend fun profiles(): List<ProfileDto> =
        profiles.observeProfiles().first().map { ProfileDto(it.id, it.name, it.isKid, it.hasPin) }

    override suspend fun channels(): List<ChannelDto> {
        val profile = profiles.ensureActiveProfile()
        return channelDao.observeAll(profile.id).first().map {
            ChannelDto(it.id, it.number, it.name, it.logoUri, it.isHidden, it.isFavorite, it.groupId)
        }
    }
}

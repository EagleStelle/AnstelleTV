package tv.anstelle.webserver.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import tv.anstelle.core.data.ProfileRepository
import tv.anstelle.core.data.ThemeImportException
import tv.anstelle.core.data.ThemeRepository
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

    suspend fun themes(): List<ThemeDto>

    /** The theme's file name and JSON, or null when there is no such theme. */
    suspend fun themeFile(themeId: Long): Pair<String, String>?

    /** Adds a theme from its JSON; throws [ThemeImportException] when it isn't one. */
    suspend fun importTheme(json: String): ThemeDto
}

internal class RoomApiDataSource @Inject constructor(
    private val profiles: ProfileRepository,
    private val channelDao: ChannelDao,
    private val channels: ChannelRepository,
    private val themes: ThemeRepository,
) : ApiDataSource {
    override suspend fun profiles(): List<ProfileDto> =
        profiles.observeProfiles().first().map { ProfileDto(it.id, it.name, it.role.name.lowercase(), it.hasPin) }

    override suspend fun channels(): List<ChannelDto> =
        channelDao.all().map {
            // Each upload gets a new file name, so the URL changes with the logo and can be cached.
            val logoUrl = it.logoUri?.let { uri -> "/api/channels/${it.id}/logo?v=${uri.substringAfterLast('/').substringBefore('.')}" }
            ChannelDto(it.id, it.number, it.name, logoUrl, it.isHidden, it.isFavorite, it.groupId)
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

    override suspend fun themes(): List<ThemeDto> {
        themes.seedBuiltIns()
        return themes.observeThemes().first().map { ThemeDto(it.id, it.spec.name, it.isBuiltIn) }
    }

    override suspend fun themeFile(themeId: Long): Pair<String, String>? {
        val theme = themes.observeTheme(themeId).first() ?: return null
        val name = theme.spec.name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "theme" }
        return "$name.json" to theme.spec.toJson()
    }

    override suspend fun importTheme(json: String): ThemeDto {
        val id = themes.import(json)
        val theme = requireNotNull(themes.observeTheme(id).first())
        return ThemeDto(id, theme.spec.name, isBuiltIn = false)
    }
}

package tv.anstelle.core.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import tv.anstelle.core.common.TimeSource
import tv.anstelle.core.database.dao.ProfileDao
import tv.anstelle.core.database.entity.ProfileEntity
import tv.anstelle.core.model.Profile
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProfileRepository @Inject constructor(
    private val dao: ProfileDao,
    private val settings: SettingsRepository,
    private val time: TimeSource,
) {
    fun observeProfiles(): Flow<List<Profile>> = dao.observeAll().map { list -> list.map { it.toModel() } }

    /**
     * Makes sure at least one profile exists and returns the active one. On first launch
     * this creates the default "Home" profile and makes it active.
     */
    suspend fun ensureActiveProfile(): Profile {
        val profiles = dao.all()
        val existing = if (profiles.isEmpty()) {
            val id = dao.upsert(
                ProfileEntity(
                    name = DEFAULT_PROFILE_NAME,
                    avatar = null,
                    isKid = false,
                    maxRatingScore = null,
                    pinHash = null,
                    serverId = null,
                    jellyfinUserId = null,
                    themeId = null,
                    sortOrder = 0,
                    createdAtUtcMs = time.nowUtcMs(),
                ),
            )
            listOf(requireNotNull(dao.byId(id)))
        } else {
            profiles
        }
        val activeId = settings.get(Settings.ActiveProfileId)
        val active = existing.firstOrNull { it.id == activeId } ?: existing.first()
        if (active.id != activeId) settings.set(Settings.ActiveProfileId, active.id)
        return active.toModel()
    }

    suspend fun setActive(profileId: Long) = settings.set(Settings.ActiveProfileId, profileId)

    private companion object {
        const val DEFAULT_PROFILE_NAME = "Home"
    }
}

internal fun ProfileEntity.toModel() = Profile(
    id = id,
    name = name,
    avatar = avatar,
    isKid = isKid,
    maxRatingScore = maxRatingScore,
    hasPin = pinHash != null,
    jellyfinUserId = jellyfinUserId,
    themeId = themeId,
)

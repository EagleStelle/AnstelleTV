package tv.anstelle.core.data

import tv.anstelle.core.database.AnstelleDatabase
import tv.anstelle.core.model.ProfileRole

/** Called inside the caller's transaction so profile removal and its replacement become visible together. */
internal suspend fun AnstelleDatabase.deleteProfiles(ids: Set<Long>, settings: SettingsRepository) {
    for (id in ids) {
        profileDao().delete(id)
        settingDao().removeForProfile(id, Settings.serverKeys(id))
    }
    if (settings.get(Settings.ActiveProfileId) in ids) {
        val remaining = profileDao().all()
        val next = remaining.firstOrNull { it.role == ProfileRole.OWNER } ?: remaining.firstOrNull()
        settings.set(Settings.ActiveProfileId, next?.id ?: 0L)
    }
}

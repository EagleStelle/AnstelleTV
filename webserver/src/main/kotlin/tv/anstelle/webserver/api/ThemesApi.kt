package tv.anstelle.webserver.api

import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.jsonObject
import tv.anstelle.core.data.ProfileRepository
import tv.anstelle.core.data.ThemeImportException
import tv.anstelle.core.data.ThemeRepository
import tv.anstelle.core.data.ThemeSummary
import tv.anstelle.core.data.themes.colorRoles
import tv.anstelle.core.data.themes.readability
import tv.anstelle.core.data.themes.themeChoices
import tv.anstelle.core.model.ThemeContrast
import tv.anstelle.core.model.ThemeSpec
import javax.inject.Inject

/** Thrown when a change targets a built-in theme, which never changes. */
class BuiltInThemeException : IllegalStateException("Built-in themes can't be edited. Copy one to make it your own.")

/**
 * The theme library and the designer, for the web UI, and the theme the paired profile wears:
 * the browser wears it too, and picking one there puts it on that profile on the TV. Missing
 * themes come back as null or false.
 */
interface ThemesApi {
    /** Every theme, [profileId]'s marked in use. */
    suspend fun themes(profileId: Long): List<ThemeDto>

    /** The theme [profileId] wears: its own pick, else the default. */
    suspend fun worn(profileId: Long): WornThemeDto

    /** Puts [themeId] on [profileId]; false when there is no such theme. */
    suspend fun use(profileId: Long, themeId: Long): Boolean

    /** The theme's file name and JSON, or null when there is no such theme. */
    suspend fun themeFile(themeId: Long): Pair<String, String>?

    /** Adds a theme from its JSON; throws [ThemeImportException] when it isn't one. */
    suspend fun import(json: String): ThemeDto

    /** A new theme of the user's own, a copy of the default. */
    suspend fun create(): Long
    suspend fun copy(themeId: Long): Long?
    suspend fun delete(themeId: Long): Boolean
    suspend fun design(themeId: Long): ThemeDesignDto?

    /**
     * The designer's readout for [specJson] as edited, with value [index] of [choice] picked when
     * one is given, without saving it; throws [IllegalArgumentException] for a value it can't have.
     */
    suspend fun check(themeId: Long, specJson: String, choice: String? = null, index: Int = 0): ThemeDesignDto?

    /** Saves [specJson] over a theme of the user's own; throws [IllegalArgumentException] for a value it can't have. */
    suspend fun save(themeId: Long, specJson: String): ThemeDesignDto?
}

internal class RoomThemesApi @Inject constructor(
    private val themes: ThemeRepository,
    private val profiles: ProfileRepository,
) : ThemesApi {
    override suspend fun themes(profileId: Long): List<ThemeDto> {
        val worn = wornSummary(profileId)?.id
        return themes.observeThemes().first()
            .sortedWith(compareBy<ThemeSummary>({ !it.isBuiltIn }, { !it.isDefault }, { it.spec.name.lowercase() }))
            .map { summary ->
                val colors = summary.spec.colors
                ThemeDto(
                    summary.id,
                    summary.spec.name,
                    summary.isBuiltIn,
                    listOf(colors.background, colors.surface, colors.text, colors.accent),
                    inUse = summary.id == worn,
                    isDefault = summary.isDefault,
                    spec = ApiJson.parseToJsonElement(summary.spec.toJson()).jsonObject,
                )
            }
    }

    override suspend fun worn(profileId: Long): WornThemeDto {
        val theme = wornSummary(profileId) ?: throw ThemeImportException("The default theme is missing")
        return WornThemeDto(theme.id, ApiJson.parseToJsonElement(theme.spec.toJson()).jsonObject)
    }

    override suspend fun use(profileId: Long, themeId: Long): Boolean {
        val theme = themes.observeTheme(themeId).first() ?: return false
        // The default is stored as no pick, so it follows whatever the default becomes.
        themes.use(profileId, if (theme.isDefault) null else themeId)
        return true
    }

    /** [profileId]'s own pick while it still exists, else the default. */
    private suspend fun wornSummary(profileId: Long): ThemeSummary? {
        themes.seedBuiltIns()
        val all = themes.observeThemes().first()
        val picked = profiles.observeProfile(profileId).first()?.themeId
        return all.firstOrNull { it.id == picked } ?: all.firstOrNull { it.isDefault }
    }

    override suspend fun themeFile(themeId: Long): Pair<String, String>? {
        val theme = themes.observeTheme(themeId).first() ?: return null
        val name = theme.spec.name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "theme" }
        return "$name.json" to theme.spec.toJson()
    }

    override suspend fun import(json: String): ThemeDto {
        val id = themes.import(json)
        val theme = requireNotNull(themes.observeTheme(id).first())
        return ThemeDto(id, theme.spec.name, isBuiltIn = false)
    }

    override suspend fun create(): Long {
        themes.seedBuiltIns()
        val default = themes.observeThemes().first().firstOrNull { it.isDefault }
            ?: throw ThemeImportException("The default theme is missing")
        return themes.duplicate(default.id)
    }

    override suspend fun copy(themeId: Long): Long? {
        themes.observeTheme(themeId).first() ?: return null
        return themes.duplicate(themeId)
    }

    override suspend fun delete(themeId: Long): Boolean {
        val theme = themes.observeTheme(themeId).first() ?: return false
        if (theme.isBuiltIn) throw BuiltInThemeException()
        themes.delete(themeId)
        return true
    }

    override suspend fun design(themeId: Long): ThemeDesignDto? = themes.observeTheme(themeId).first()?.let(::designOf)

    override suspend fun check(themeId: Long, specJson: String, choice: String?, index: Int): ThemeDesignDto? {
        val theme = themes.observeTheme(themeId).first() ?: return null
        val edited = parse(theme, specJson)
        val spec = if (choice == null) {
            edited
        } else {
            val setting = requireNotNull(themeChoices.firstOrNull { it.key == choice }) { "There is no such setting." }
            require(index in setting.labels.indices) { "That setting has no such value." }
            setting.apply(edited, index)
        }
        return designOf(theme.copy(spec = spec))
    }

    override suspend fun save(themeId: Long, specJson: String): ThemeDesignDto? {
        val theme = themes.observeTheme(themeId).first() ?: return null
        if (theme.isBuiltIn) throw BuiltInThemeException()
        val spec = parse(theme, specJson)
        themes.save(themeId, spec)
        return designOf(theme.copy(spec = spec))
    }

    /** [specJson] as [theme]'s new spec, keeping its key; throws [IllegalArgumentException] for a value it can't have. */
    private fun parse(theme: ThemeSummary, specJson: String): ThemeSpec {
        val spec = try {
            ThemeSpec.parse(specJson)
        } catch (e: SerializationException) {
            throw IllegalArgumentException("That isn't a theme", e)
        }.copy(key = theme.spec.key)
        require(spec.name.isNotBlank()) { "Give the theme a name." }
        spec.validate()
        return spec
    }

    private fun designOf(theme: ThemeSummary): ThemeDesignDto {
        val colors = theme.spec.colors
        return ThemeDesignDto(
            id = theme.id,
            isBuiltIn = theme.isBuiltIn,
            spec = ApiJson.parseToJsonElement(theme.spec.toJson()).jsonObject,
            roles = colorRoles(colors).map { ColorRoleDto(it.key, it.label, it.about) },
            readability = ThemeContrast.floors(colors).map { floor ->
                ReadabilityDto(
                    foreground = floor.foreground,
                    background = floor.background,
                    ok = ThemeContrast.ratio(floor, colors) >= floor.minimum,
                    text = readability(floor, colors),
                    inForeground = readability(floor, colors, open = floor.foreground),
                    inBackground = readability(floor, colors, open = floor.background),
                )
            },
            choices = themeChoices.map { DesignChoiceDto(it.key, it.section, it.title, it.labels, it.selected(theme.spec)) },
        )
    }
}

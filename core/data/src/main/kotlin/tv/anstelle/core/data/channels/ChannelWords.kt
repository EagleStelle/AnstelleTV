package tv.anstelle.core.data.channels

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tv.anstelle.core.common.AppLog
import tv.anstelle.core.model.Languages
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** A media type such as anime: never a channel alone, only with a genre or a decade. */
class MediaKind(
    /** The noun channel names use: "Sports Anime", "90s Cartoons". */
    val name: String,
    genres: Collection<String>,
    tags: Collection<String>,
    language: String? = null,
) {
    val key: String = valueKey(name)
    val genreKeys: Set<String> = genres.mapTo(HashSet(), ::valueKey)
    val tagKeys: Set<String> = tags.mapTo(HashSet(), ::valueKey)

    /** The audio language that confirms the type (ISO 639-2/T, "jpn" for anime), if any. */
    val language: String? = language?.let(Languages::normalize)
}

/**
 * The words channel names need beyond the library's metadata, read from
 * `assets/channel-words.json`. It names no titles, studios or networks, and decides no lineup:
 *
 * - `types`: media types (anime, cartoons) in precedence order, with the genres and tags that mark
 *   them and the audio language that confirms them (see [MediaKinds]).
 * - `same`: spellings of one genre; the first is the one shown.
 * - `heads`: genres that end a two-genre name ("Action Comedy", "Crime Drama").
 * - `modifiers`: a genre's adjective, used in every combined name ("Comedic Drama", "Comedic Anime").
 *   Other genres modify as they are ("Sports Anime").
 * - `ignoredTags`: tags that describe the file or the credits, not the content.
 * - `minorWords`: words title case leaves lowercase ("Based on Manga").
 * - `names`: what to call the series channel of a genre ("Horror": "Night Shivers"), of a media
 *   type with a genre ("Anime + Horror": "Anime After Dark") or of two genres ("Crime + Drama":
 *   "Case Files"); `movieNames` the same for movie channels. A channel without one is named from
 *   its genres ("Comedic Drama", "Comedic Drama Movies").
 */
class ChannelWords(
    val types: List<MediaKind>,
    same: List<List<String>>,
    heads: Collection<String>,
    modifiers: Map<String, String>,
    ignoredTags: Collection<String>,
    minorWords: Collection<String>,
    names: Map<String, String> = emptyMap(),
    movieNames: Map<String, String> = emptyMap(),
) {
    private val canonical: Map<String, String> = buildMap {
        for (spellings in same) for (spelling in spellings) put(valueKey(spelling), spellings.first())
    }
    private val headKeys: Set<String> = heads.mapTo(HashSet(), ::genreKey)
    private val modifierOf: Map<String, String> = modifiers.mapKeys { genreKey(it.key) }
    private val ignored: Set<String> = ignoredTags.mapTo(HashSet(), ::valueKey)
    private val minor: Set<String> = minorWords.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
    private val seriesNames = combinations(names)
    private val movieNames = combinations(movieNames)

    /** Genres whose own channel has a name. */
    val namedGenres: Set<String> get() = seriesNames.keys.filter { it.size == 1 && !it.single().startsWith(TYPE_PREFIX) }.mapTo(HashSet()) { it.single() }

    /** The key of a genre: every spelling in a `same` list shares one. */
    fun genreKey(value: String): String {
        val key = valueKey(value)
        return canonical[key]?.let(::valueKey) ?: key
    }

    /** The name a `same` list gives the genre with [key], or null to use the library's spelling. */
    fun genreName(key: String): String? = canonical[key]

    /** True when the genre with [key] ends a two-genre name: Comedy in "Action Comedy". */
    fun isHead(key: String): Boolean = key in headKeys

    /** The adjective the genre with [key] takes in combined names, or null when it modifies as it is. */
    fun modifier(key: String): String? = modifierOf[key]

    fun isIgnoredTag(key: String): Boolean = key in ignored

    /** The name of the series (or [movies]) channel of the genre with [key], or null to use the genre's own. */
    fun nameOf(key: String, movies: Boolean = false): String? = namesFor(movies)[setOf(key)]

    /** The name of the series (or [movies]) channel of two genres, or null to name it from them. */
    fun nameOf(first: String, second: String, movies: Boolean = false): String? = namesFor(movies)[setOf(first, second)]

    /** The name of the series (or [movies]) channel of [kind] with a genre, or null to name it from them. */
    fun nameOf(kind: MediaKind, genre: String, movies: Boolean = false): String? = namesFor(movies)[setOf(TYPE_PREFIX + kind.key, genre)]

    private fun namesFor(movies: Boolean) = if (movies) movieNames else seriesNames

    /** `"Anime + Horror"` → the keys of Anime and Horror. */
    private fun combinations(names: Map<String, String>): Map<Set<String>, String> = names.entries.associate { (combination, name) ->
        combination.split('+').map { part ->
            val type = types.firstOrNull { it.key == valueKey(part) }
            if (type != null) TYPE_PREFIX + type.key else genreKey(part)
        }.toSet() to name.trim()
    }

    /** `"based on manga"` → `"Based on Manga"`; text with any capital stays as written. */
    fun titleCase(text: String): String {
        if (text.any(Char::isUpperCase)) return text
        return text.split(' ').mapIndexed { i, word ->
            if (i > 0 && word in minor) word else word.replaceFirstChar { it.titlecase(Locale.ROOT) }
        }.joinToString(" ")
    }

    companion object {
        val EMPTY = ChannelWords(emptyList(), emptyList(), emptyList(), emptyMap(), emptyList(), emptyList())

        private const val TYPE_PREFIX = "type:"
        private val json = Json { ignoreUnknownKeys = false }

        /** Parses the word list. Throws [IllegalArgumentException] describing the problem. */
        fun parse(text: String): ChannelWords {
            val file = json.decodeFromString(WordsJson.serializer(), text)
            for (type in file.types) {
                require(type.name.isNotBlank()) { "a type has no name" }
                require(type.genres.isNotEmpty() || type.tags.isNotEmpty()) { "type ${type.name} has no genres or tags" }
            }
            require(file.types.map { valueKey(it.name) }.toSet().size == file.types.size) { "type names repeat" }
            require(file.same.none { it.isEmpty() }) { "an empty same list" }
            for ((combination, name) in file.names.entries + file.movieNames.entries) {
                val parts = combination.split('+').map(String::trim)
                require(parts.size in 1..2 && parts.all(String::isNotEmpty)) { "a name is for one genre or two: $combination" }
                require(name.isNotBlank()) { "no name for $combination" }
            }
            return ChannelWords(
                types = file.types.map { MediaKind(it.name.trim(), it.genres, it.tags, it.language) },
                same = file.same.map { list -> list.map(String::trim) },
                heads = file.heads,
                modifiers = file.modifiers,
                ignoredTags = file.ignoredTags,
                minorWords = file.minorWords,
                names = file.names,
                movieNames = file.movieNames,
            )
        }
    }
}

/** The word list channel names are made with. */
fun interface ChannelWordsSource {
    fun words(): ChannelWords
}

/** Reads `assets/channel-words.json` once. An unreadable file is logged and channels use no word list. */
@Singleton
class AssetChannelWords @Inject constructor(
    @ApplicationContext private val context: Context,
) : ChannelWordsSource {
    private val words: ChannelWords by lazy {
        try {
            ChannelWords.parse(context.assets.open(FILE).bufferedReader().use { it.readText() })
        } catch (e: IOException) {
            AppLog.w(TAG, "Unreadable $FILE: ${e.message}")
            ChannelWords.EMPTY
        } catch (e: IllegalArgumentException) {
            AppLog.w(TAG, "Broken $FILE: ${e.message}")
            ChannelWords.EMPTY
        }
    }

    override fun words(): ChannelWords = words

    private companion object {
        const val TAG = "Channels"
        const val FILE = "channel-words.json"
    }
}

@Serializable
private class WordsJson(
    val types: List<TypeJson> = emptyList(),
    val same: List<List<String>> = emptyList(),
    val heads: List<String> = emptyList(),
    val modifiers: Map<String, String> = emptyMap(),
    val ignoredTags: List<String> = emptyList(),
    val minorWords: List<String> = emptyList(),
    val names: Map<String, String> = emptyMap(),
    val movieNames: Map<String, String> = emptyMap(),
)

@Serializable
private class TypeJson(val name: String, val genres: List<String> = emptyList(), val tags: List<String> = emptyList(), val language: String? = null)

package tv.anstelle.core.data.channels

import tv.anstelle.core.model.MediaType
import tv.anstelle.core.model.PlaybackOrder
import tv.anstelle.core.model.RuleField
import tv.anstelle.core.model.RuleOp
import java.text.Collator
import java.util.BitSet
import java.util.Locale
import java.util.TreeSet
import kotlin.math.ceil

/**
 * The guide groups library channels join, in guide order. Each numbers its channels up from its
 * own start ([defaultStart] until a profile picks another).
 */
enum class ChannelSegment(val groupName: String, val defaultStart: Int) {
    PRODUCTIONS("Productions", 1000),
    DECADES("Decades", 2000),
    GENRES("Genres", 3000),
    THEMES("Themes", 4000),
    ;

    /** How a channel names it as its segment ("genres"). */
    val key: String get() = name.lowercase(Locale.ROOT)
}

/** A channel made from the library. [key] identifies it across syncs; [name] is what the guide shows. */
class GeneratedChannel(
    val key: String,
    val name: String,
    val segment: ChannelSegment,
    val lineup: Lineup,
    val order: PlaybackOrder,
    match: () -> MatchResult,
) {
    /** What the lineup matches, listed only when asked for. */
    val match: MatchResult by lazy(match)
}

/**
 * Makes channels from the library's metadata: which channels exist and what they air come from
 * the library's genres, tags, years and studios. Genre channels take their names from [words]
 * ("Fright Night", "Anime After Dark", "Case Files") and are otherwise named from their genres.
 *
 * A genre or decade channel airs series or movies, never both, and one kind of show only:
 * anime, cartoons or neither (the catalog decides each show's kind; animation it can't place
 * airs on none of them). Themes and productions air everything with their tag or studio.
 * Channels a person builds may mix anything.
 *
 * - Genres: each genre, each pair of genres and each genre with a media type. A media type never
 *   airs alone.
 * - Themes: each tag ("Isekai"), when more shows carry it than a genre needs.
 * - Decades: TV, movies and each media type by decade ("90s TV", "2010s Anime Movies").
 * - Productions: each studio, by its own name ("Netflix").
 *
 * A channel needs [MIN_SHOWS] shows (a movie counts as one) and [MIN_DURATION_MS] of airtime,
 * times [scale]: the sync keeps an existing channel while it would be made at [KEEP_SCALE] of
 * the thresholds, so channels near the limit don't come and go with every sync.
 * A channel whose shows are nearly the same as one kept before it is dropped, and so is a
 * studio whose shows nearly all belong to a bigger studio. Pure, so it is tested without a
 * database.
 */
class ChannelGenerator(private val words: ChannelWords, scale: Double = 1.0) {
    private val kinds = words.types
    private val minShows = ceil(MIN_SHOWS * scale).toInt()
    private val tagMinShows = ceil(TAG_MIN_SHOWS * scale).toInt()
    private val minDurationMs = (MIN_DURATION_MS * scale).toLong()
    private val collator = Collator.getInstance(Locale.ROOT).apply { strength = Collator.PRIMARY }

    /**
     * Guide order of `(key, name)`: segments, then names; decades by year, then TV, movies and
     * each media type's series and movies.
     */
    val order: Comparator<Pair<String, String>> = compareBy<Pair<String, String>> { segmentOf(it.first)?.ordinal ?: Int.MAX_VALUE }
        .thenBy { decadeOf(it.first) }
        .thenBy { kindRank(it.first) }
        .thenComparator { a, b -> collator.compare(a.second, b.second) }
        .thenBy { it.first }

    /** The library's channels, in guide order. Every profile shares them. */
    fun generate(catalog: Catalog): List<GeneratedChannel> = generate(catalog.items)

    fun generate(items: List<CatalogItem>): List<GeneratedChannel> =
        Library(items.filter { it.row.type in Medium.Types }).channels().sortedWith(compareBy(order) { it.key to it.name })

    private fun decadeOf(key: String): Int =
        if (segmentOf(key) == ChannelSegment.DECADES) key.split(':').getOrNull(2)?.toIntOrNull() ?: 0 else 0

    /** Within a decade: TV, Movies, then each media type's series and movies. */
    private fun kindRank(key: String): Int {
        if (segmentOf(key) != ChannelSegment.DECADES) return 0
        val parts = key.split(':')
        val medium = if (parts[0] == Medium.MOVIES.key) 1 else 0
        val kind = parts.getOrNull(3)?.let { kind -> kinds.indexOfFirst { it.key == kind } } ?: -1
        return (kind + 1) * 2 + medium
    }

    /** Series and movies never share a generated channel. */
    private enum class Medium(val key: String, val type: MediaType) {
        SERIES("tv", MediaType.EPISODE),
        MOVIES("movies", MediaType.MOVIE),
        ;

        val rule = Rule(RuleField.MEDIA_TYPE, RuleOp.EQUALS, listOf(type.name))

        companion object {
            val Types = entries.mapTo(HashSet()) { it.type }
        }
    }

    /** One generation: the library indexed by genre, tag, studio, decade, kind and medium. */
    private inner class Library(private val items: List<CatalogItem>) {
        private val showOf = IntArray(items.size)
        private val showTotal: Int
        private val inMedium = Medium.entries.associateWith { BitSet() }

        /** Items of no media type. */
        private val plain = BitSet()
        private val kindItems = List(kinds.size) { BitSet() }
        private val genres = Facet()
        private val tags = Facet()
        private val studios = Facet()
        private val decades = sortedMapOf<Int, BitSet>()

        init {
            val shows = HashMap<String, Int>()
            // Episodes repeat their show's lists, so each distinct list is read once.
            val genreLists = HashMap<String, List<GenrePart>>()
            val valueLists = HashMap<String, List<Pair<String, String>>>()
            items.forEachIndexed { i, item ->
                val row = item.row
                showOf[i] = shows.getOrPut(item.showKey) { shows.size }
                Medium.entries.first { it.type == row.type }.let { inMedium.getValue(it).set(i) }
                row.year?.let { year -> decades.getOrPut(year - Math.floorMod(year, 10)) { BitSet() }.set(i) }
                // Animation the catalog couldn't place is in neither set.
                when (val kind = kinds.indexOfFirst { it.key == item.kind }) {
                    -1 -> if (item.kind == MediaKinds.NONE) plain.set(i)
                    else -> kindItems[kind].set(i)
                }

                for (part in genreLists.getOrPut(row.genres) { genreParts(row.genres) }) {
                    if (!part.isKind) genres.add(part.genreKey, i, part.value, part.part)
                }
                for ((value, key) in valueLists.getOrPut(row.tags) { split(row.tags).map { it to valueKey(it) } }) {
                    tags.add(key, i, value, value)
                }
                for ((value, key) in valueLists.getOrPut(row.studios) { split(row.studios).map { it to valueKey(it) } }) {
                    studios.add(key, i, value, value)
                }
            }
            showTotal = shows.size
        }

        /** "Sci-Fi & Fantasy" is two genres; each genre value is still matched as written. */
        private fun genreParts(list: String): List<GenrePart> = split(list).flatMap { value ->
            value.split('&').map(String::trim).filter(String::isNotEmpty).map { part ->
                val key = valueKey(part)
                GenrePart(value, part, words.genreKey(part), isKind = kinds.any { key in it.genreKeys })
            }
        }

        fun channels(): List<GeneratedChannel> {
            val kept = ArrayList<Candidate>()
            val candidates = Medium.entries.flatMap { genreCandidates(it) } +
                tagCandidates() +
                decadeCandidates() +
                studioCandidates()
            for (candidate in candidates) {
                val duplicate = candidate.parents.any { candidate.shows.similar(it) } ||
                    kept.any { candidate.shows.similar(it.shows) } ||
                    // A studio whose shows nearly all aired on a bigger one (a regional station
                    // carrying a network's lineup) adds nothing.
                    candidate.segment == ChannelSegment.PRODUCTIONS &&
                    kept.any { it.segment == ChannelSegment.PRODUCTIONS && candidate.shows.inside(it.shows) }
                if (!duplicate) kept += candidate
            }
            return kept.map { it.channel() }
        }

        /** Single genres, then genres with a media type, then pairs; the bigger first within each. */
        private fun genreCandidates(medium: Medium): List<Candidate> {
            val movies = medium == Medium.MOVIES
            val media = inMedium.getValue(medium)
            val singles = genres.keys().mapNotNull { key ->
                val name = words.nameOf(key, movies) ?: withMedium(genreName(key), medium)
                candidate(ChannelSegment.GENRES, "${medium.key}:genre:$key", name, genres.itemsOf(key).intersect(plain).intersect(media), minShows) {
                    kindGroup(-1, medium, genreRule(key))
                }
            }.biggestFirst()

            val typed = kinds.indices.flatMap { k ->
                val kindShows = ShowSet(showsOf(kindItems[k].intersect(media)))
                genres.keys().mapNotNull { key ->
                    val name = words.nameOf(kinds[k], key, movies) ?: withMedium("${modifierOf(key)} ${kinds[k].name}", medium)
                    val matched = genres.itemsOf(key).intersect(kindItems[k]).intersect(media)
                    candidate(ChannelSegment.GENRES, "${medium.key}:type:${kinds[k].key}+$key", name, matched, minShows, listOf(kindShows)) {
                        kindGroup(k, medium, genreRule(key))
                    }
                }
            }.biggestFirst()

            // Only genres with a channel of their own pair up.
            val single = singles.associateBy { it.key.substringAfterLast(':') }
            val pairable = single.keys.sorted()
            val pairs = pairable.flatMapIndexed { i, a ->
                pairable.drop(i + 1).mapNotNull { b ->
                    val first = single.getValue(a)
                    val second = single.getValue(b)
                    val name = words.nameOf(a, b, movies) ?: withMedium(pairName(a, first.shows.size, b, second.shows.size), medium)
                    candidate(ChannelSegment.GENRES, "${medium.key}:genre:$a+$b", name, first.matched.intersect(second.matched), minShows, listOf(first.shows, second.shows)) {
                        kindGroup(-1, medium, genreRule(a), genreRule(b))
                    }
                }
            }.biggestFirst()

            return singles + typed + pairs
        }

        /**
         * Tags that aren't a genre, a media type, junk or on most of the library's shows. A theme
         * airs everything with its tag: series and movies, anime, cartoons and the rest.
         */
        private fun tagCandidates(): List<Candidate> {
            val genreKeys = genres.keys().toSet()
            return tags.keys()
                .filter { key ->
                    !words.isIgnoredTag(key) &&
                        words.genreKey(key) !in genreKeys &&
                        kinds.none { key in it.tagKeys || key in it.genreKeys }
                }
                .mapNotNull { key ->
                    candidate(ChannelSegment.THEMES, "$MIXED:tag:$key", words.titleCase(tags.nameOf(key)), tags.itemsOf(key), tagMinShows) {
                        listOf(RuleGroup(listOf(Rule(RuleField.TAG, RuleOp.ANY_OF, tags.valuesOf(key)), Aired)))
                    }?.takeIf { it.shows.size * 2 <= showTotal }
                }
                .biggestFirst()
        }

        private fun decadeCandidates(): List<Candidate> = decades.flatMap { (decade, inDecade) ->
            val label = decadeLabel(decade)
            val year = Rule(RuleField.DECADE, RuleOp.EQUALS, listOf(decade.toString()))
            Medium.entries.flatMap { medium ->
                val media = inMedium.getValue(medium)
                val plainName = if (medium == Medium.MOVIES) "$label Movies" else "$label TV"
                (listOf(-1) + kinds.indices).mapNotNull { k ->
                    val name = if (k < 0) plainName else withMedium("$label ${kinds[k].name}", medium)
                    val kindPart = if (k < 0) "" else ":${kinds[k].key}"
                    val matched = inDecade.intersect(if (k < 0) plain else kindItems[k]).intersect(media)
                    candidate(ChannelSegment.DECADES, "${medium.key}:decade:$decade$kindPart", name, matched, minShows) {
                        kindGroup(k, medium, year)
                    }
                }
            }
        }

        /** Each studio by its own name, airing everything it made: series and movies, anime, cartoons and the rest. */
        private fun studioCandidates(): List<Candidate> = studios.keys().mapNotNull { key ->
            candidate(ChannelSegment.PRODUCTIONS, "$MIXED:studio:$key", studios.nameOf(key), studios.itemsOf(key), minShows) {
                listOf(RuleGroup(listOf(Rule(RuleField.STUDIO, RuleOp.ANY_OF, studios.valuesOf(key)), Aired)))
            }
        }.biggestFirst()

        /** One rule group: items of kind [kind] (-1: of none) in [medium] that pass [rules]. */
        private fun kindGroup(kind: Int, medium: Medium, vararg rules: Rule): List<RuleGroup> {
            val kindRule = Rule(RuleField.KIND, RuleOp.EQUALS, listOf(if (kind < 0) MediaKinds.NONE else kinds[kind].key))
            return listOf(RuleGroup(rules.toList() + kindRule + medium.rule))
        }

        private fun genreRule(key: String) = Rule(RuleField.GENRE, RuleOp.ANY_OF, genres.valuesOf(key))

        private fun genreName(key: String): String = words.genreName(key) ?: genres.nameOf(key)

        private fun modifierOf(key: String): String = words.modifier(key) ?: genreName(key)

        private fun withMedium(name: String, medium: Medium) = if (medium == Medium.MOVIES) "$name Movies" else name

        /**
         * `<modifier> <head>`. A head genre ends the name ("Action Comedy", "Crime Drama"); between
         * two heads or two others, the genre with an adjective modifies ("Comedic Drama"), and
         * otherwise the genre with fewer shows does ("Mystery Thriller").
         */
        private fun pairName(a: String, showsA: Int, b: String, showsB: Int): String {
            val headA = words.isHead(a)
            val headB = words.isHead(b)
            val adjectiveA = words.modifier(a) != null
            val adjectiveB = words.modifier(b) != null
            val aModifies = when {
                headA != headB -> headB
                adjectiveA != adjectiveB -> adjectiveA
                showsA != showsB -> showsA < showsB
                else -> a < b
            }
            return if (aModifies) "${modifierOf(a)} ${genreName(b)}" else "${modifierOf(b)} ${genreName(a)}"
        }

        private fun candidate(
            segment: ChannelSegment,
            key: String,
            name: String,
            matched: BitSet,
            minShows: Int,
            parents: List<ShowSet> = emptyList(),
            groups: () -> List<RuleGroup>,
        ): Candidate? {
            val shows = showsOf(matched)
            if (shows.cardinality() < minShows) return null
            var durationMs = 0L
            matched.forEachSet { durationMs += items[it].row.durationMs }
            if (durationMs < minDurationMs) return null
            return Candidate(segment, key, name, matched, ShowSet(shows), parents, groups)
        }

        private fun showsOf(matched: BitSet): BitSet = BitSet(showTotal).also { shows -> matched.forEachSet { shows.set(showOf[it]) } }

        private fun List<Candidate>.biggestFirst() = sortedWith(compareByDescending<Candidate> { it.shows.size }.thenBy { it.key })

        private inner class Candidate(
            val segment: ChannelSegment,
            val key: String,
            val name: String,
            val matched: BitSet,
            val shows: ShowSet,
            /** Show sets the channel must differ from: a pair's genres, a typed genre's media type. */
            val parents: List<ShowSet>,
            val groups: () -> List<RuleGroup>,
        ) {
            fun channel(): GeneratedChannel {
                var moviesOnly = true
                matched.forEachSet { if (items[it].row.type != MediaType.MOVIE) moviesOnly = false }
                return GeneratedChannel(
                    key = key,
                    name = name,
                    segment = segment,
                    lineup = Lineup(groups()),
                    order = if (moviesOnly) PlaybackOrder.SHUFFLE else PlaybackOrder.EPISODE_ORDER,
                ) {
                    val matches = ArrayList<Match>(matched.cardinality())
                    matched.forEachSet { matches += Match(items[it], 1f) }
                    MatchResult(matches)
                }
            }
        }
    }

    /** One genre in a genre value; [isKind] when it names a media type ("Animation") instead. */
    private class GenrePart(val value: String, val part: String, val genreKey: String, val isKind: Boolean)

    /** A set of shows as bit words, so comparing two sets allocates nothing. */
    private class ShowSet(bits: BitSet) {
        private val words: LongArray = bits.toLongArray()
        val size: Int = bits.cardinality()

        private fun shared(other: ShowSet): Int {
            var count = 0
            for (i in 0 until minOf(words.size, other.words.size)) count += java.lang.Long.bitCount(words[i] and other.words[i])
            return count
        }

        /** The shared shows are at least [SIMILAR] of the shows in either set. */
        fun similar(other: ShowSet): Boolean {
            val shared = shared(other)
            val all = size + other.size - shared
            return all == 0 || shared >= SIMILAR * all
        }

        /** At least [INSIDE] of these shows are also in [other]. */
        fun inside(other: ShowSet): Boolean = size > 0 && shared(other) >= INSIDE * size
    }

    /** One list field (genres, tags or studios), by key: the items, the values as written, and how often each spelling appears. */
    private class Facet {
        private val items = HashMap<String, BitSet>()
        private val values = HashMap<String, TreeSet<String>>()
        private val spellings = HashMap<String, HashMap<String, Int>>()

        fun add(key: String, item: Int, value: String, spelling: String) {
            if (key.isEmpty()) return
            items.getOrPut(key) { BitSet() }.set(item)
            values.getOrPut(key) { TreeSet() } += value
            spellings.getOrPut(key) { HashMap() }.merge(spelling, 1, Int::plus)
        }

        fun keys(): List<String> = items.keys.sorted()

        fun itemsOf(key: String): BitSet = items.getValue(key)

        fun valuesOf(key: String): List<String> = distinct(values.getValue(key))

        /** The most common spelling; ties go to the first in order. */
        fun nameOf(key: String): String =
            spellings.getValue(key).entries.minWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).key
    }

    companion object {
        const val MIN_SHOWS = 3

        /** Tags are looser than genres, so a tag channel needs more shows. */
        const val TAG_MIN_SHOWS = 8
        const val MIN_DURATION_MS = 12 * 3_600_000L

        /** The medium part of a key for a channel of series and movies together. */
        private const val MIXED = "all"
        private val Aired = Rule(RuleField.MEDIA_TYPE, RuleOp.ANY_OF, listOf(MediaType.EPISODE.name, MediaType.MOVIE.name))

        /** Show sets this alike (shared shows over all shows) count as the same channel. */
        const val SIMILAR = 0.85

        /** The share of the thresholds an existing channel must still meet to stay. */
        const val KEEP_SCALE = 0.75

        /** A studio with this share of its shows inside a bigger studio's is left out. */
        const val INSIDE = 0.9

        /**
         * The segment a channel key belongs to, or null for a key no generator made. Keys read
         * `<medium>:<family>:…`: `tv:genre:comedy`, `movies:decade:1990:anime`.
         */
        fun segmentOf(key: String): ChannelSegment? = when (key.split(':').getOrNull(1)) {
            "genre", "type" -> ChannelSegment.GENRES
            "tag" -> ChannelSegment.THEMES
            "decade" -> ChannelSegment.DECADES
            "studio" -> ChannelSegment.PRODUCTIONS
            else -> null
        }

        /** 1990 → "90s"; 1910 and earlier, and 2000 on, in full: "2010s". */
        internal fun decadeLabel(decade: Int): String = if (decade in 1920..1990) "${decade % 100}s" else "${decade}s"

        private fun split(value: String): List<String> = value.split('|').map(String::trim).filter(String::isNotEmpty)

        /** Values in order, one spelling per letter case. */
        private fun distinct(values: Collection<String>): List<String> = values.sorted().distinctBy { it.lowercase(Locale.ROOT) }

        private fun BitSet.intersect(other: BitSet): BitSet = (clone() as BitSet).apply { and(other) }

        private inline fun BitSet.forEachSet(action: (Int) -> Unit) {
            var i = nextSetBit(0)
            while (i >= 0) {
                action(i)
                i = nextSetBit(i + 1)
            }
        }
    }
}

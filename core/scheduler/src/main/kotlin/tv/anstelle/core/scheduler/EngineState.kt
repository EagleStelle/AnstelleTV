package tv.anstelle.core.scheduler

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * A position inside a series in canonical order: the next episode to air is the first one at
 * or after it. Positions survive library changes, so new or deleted episodes don't reset a series.
 */
data class EpisodeKey(val season: Int, val episode: Int, val id: Long)

/** A position in the movies' release order: the next movie is the first one at or after it. */
data class ReleaseKey(val release: Long, val id: Long)

/**
 * A shuffle deck is kept per lineup ([pool] key), daypart (-1 when none applies) and kind of
 * item: the episodes, or the [movies].
 */
data class DeckKey(val pool: Long, val daypart: Int, val movies: Boolean)

/**
 * A movie that aired too recently to come back as soon as its deck starts: it waits until the
 * deck has run [readyAfterMs].
 */
data class DeckHold(val id: Long, val readyAfterMs: Long)

/**
 * A shuffle deck stored as its seed instead of its order, so the state stays small.
 * [fingerprint] covers the deck's item ids; when they change, a new deck starts.
 * [prevId] is the item played just before the deck started, which the deck must not open with.
 */
data class DeckState(
    val fingerprint: Long,
    val seed: Long,
    val prevId: Long,
    val position: Int,
    val holds: List<DeckHold> = emptyList(),
)

/**
 * Everything needed to continue a schedule exactly where it stopped. Generating 10 days and
 * extending by 20 from this state gives the same slots as generating 30 days at once.
 */
data class EngineState(
    /** Where the next program starts. */
    val nextStartUtcMs: Long,
    val rngState: Long,
    val showCursors: Map<String, EpisodeKey> = emptyMap(),
    /** Where the movies' release order continues. */
    val movieCursor: ReleaseKey? = null,
    val decks: Map<DeckKey, DeckState> = emptyMap(),
    /** The running block's series, or null for a block of movies. */
    val blockShow: String? = null,
    /** How many more items the running block plays; 0 when none runs. */
    val blockRemaining: Int = 0,
    /** The item the last program aired, or [NO_ID]. */
    val lastItemId: Long = NO_ID,
    /** When each movie still inside the movie gap last started, by item id. */
    val movieStarts: Map<Long, Long> = emptyMap(),
) {
    /** The same state with every time in it moved by [deltaMs], for a schedule that moved. */
    fun shifted(deltaMs: Long): EngineState =
        copy(nextStartUtcMs = nextStartUtcMs + deltaMs, movieStarts = movieStarts.mapValues { it.value + deltaMs })

    /** Compact binary form for `schedule_state.stateBlob`. Maps are written in key order. */
    fun encode(): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeByte(VERSION)
            out.writeLong(nextStartUtcMs)
            out.writeLong(rngState)
            out.writeBoolean(blockShow != null)
            if (blockShow != null) out.writeUTF(blockShow)
            out.writeInt(blockRemaining)
            out.writeLong(lastItemId)
            out.writeBoolean(movieCursor != null)
            if (movieCursor != null) {
                out.writeLong(movieCursor.release)
                out.writeLong(movieCursor.id)
            }

            out.writeInt(showCursors.size)
            for ((show, key) in showCursors.entries.sortedBy { it.key }) {
                out.writeUTF(show)
                out.writeInt(key.season)
                out.writeInt(key.episode)
                out.writeLong(key.id)
            }

            out.writeInt(decks.size)
            for ((key, deck) in decks.entries.sortedWith(compareBy({ it.key.pool }, { it.key.daypart }, { it.key.movies }))) {
                out.writeLong(key.pool)
                out.writeInt(key.daypart)
                out.writeBoolean(key.movies)
                out.writeLong(deck.fingerprint)
                out.writeLong(deck.seed)
                out.writeLong(deck.prevId)
                out.writeInt(deck.position)
                out.writeInt(deck.holds.size)
                for (hold in deck.holds) {
                    out.writeLong(hold.id)
                    out.writeLong(hold.readyAfterMs)
                }
            }

            out.writeInt(movieStarts.size)
            for ((id, start) in movieStarts.entries.sortedBy { it.key }) {
                out.writeLong(id)
                out.writeLong(start)
            }
        }
        return bytes.toByteArray()
    }

    companion object {
        /** Marks "no item" in [lastItemId] and [DeckState.prevId]. */
        const val NO_ID = Long.MIN_VALUE
        private const val VERSION = 3

        /** Reads [encode] output. Throws [IllegalArgumentException] for an unknown format. */
        fun decode(blob: ByteArray): EngineState = DataInputStream(ByteArrayInputStream(blob)).use { input ->
            val version = input.readByte().toInt()
            require(version == VERSION) { "Unknown engine state version $version" }
            val nextStart = input.readLong()
            val rngState = input.readLong()
            val blockShow = if (input.readBoolean()) input.readUTF() else null
            val blockRemaining = input.readInt()
            val lastItemId = input.readLong()
            val movieCursor = if (input.readBoolean()) ReleaseKey(input.readLong(), input.readLong()) else null

            val cursors = HashMap<String, EpisodeKey>()
            repeat(input.readInt()) {
                cursors[input.readUTF()] = EpisodeKey(input.readInt(), input.readInt(), input.readLong())
            }
            val decks = HashMap<DeckKey, DeckState>()
            repeat(input.readInt()) {
                val key = DeckKey(input.readLong(), input.readInt(), input.readBoolean())
                decks[key] = DeckState(
                    fingerprint = input.readLong(),
                    seed = input.readLong(),
                    prevId = input.readLong(),
                    position = input.readInt(),
                    holds = List(input.readInt()) { DeckHold(input.readLong(), input.readLong()) },
                )
            }
            val movieStarts = HashMap<Long, Long>()
            repeat(input.readInt()) { movieStarts[input.readLong()] = input.readLong() }
            EngineState(nextStart, rngState, cursors, movieCursor, decks, blockShow, blockRemaining, lastItemId, movieStarts)
        }
    }
}

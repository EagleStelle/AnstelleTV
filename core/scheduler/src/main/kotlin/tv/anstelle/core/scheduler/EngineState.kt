package tv.anstelle.core.scheduler

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * A position inside a show in canonical order: the next episode to air is the first one at
 * or after it. Positions survive library changes, so new or deleted episodes don't reset a show.
 */
data class EpisodeKey(val season: Int, val episode: Int, val id: Long)

/** A shuffle deck is kept per lineup ([pool] key) and daypart (-1 when none applies). */
data class DeckKey(val pool: Long, val daypart: Int)

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
    val lastId: Long,
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
    val decks: Map<DeckKey, DeckState> = emptyMap(),
    /** Show of the running [tv.anstelle.core.model.PlaybackOrder.BLOCKS] block. */
    val blockShow: String? = null,
    val blockRemaining: Int = 0,
) {
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

            out.writeInt(showCursors.size)
            for ((show, key) in showCursors.entries.sortedBy { it.key }) {
                out.writeUTF(show)
                out.writeInt(key.season)
                out.writeInt(key.episode)
                out.writeLong(key.id)
            }

            out.writeInt(decks.size)
            for ((key, deck) in decks.entries.sortedWith(compareBy({ it.key.pool }, { it.key.daypart }))) {
                out.writeLong(key.pool)
                out.writeInt(key.daypart)
                out.writeLong(deck.fingerprint)
                out.writeLong(deck.seed)
                out.writeLong(deck.prevId)
                out.writeInt(deck.position)
                out.writeLong(deck.lastId)
            }
        }
        return bytes.toByteArray()
    }

    companion object {
        /** Marks "no item" in [DeckState.prevId] and [DeckState.lastId]. */
        const val NO_ID = Long.MIN_VALUE
        private const val VERSION = 1

        /** Reads [encode] output. Throws [IllegalArgumentException] for an unknown format. */
        fun decode(blob: ByteArray): EngineState = DataInputStream(ByteArrayInputStream(blob)).use { input ->
            val version = input.readByte().toInt()
            require(version == VERSION) { "Unknown engine state version $version" }
            val nextStart = input.readLong()
            val rngState = input.readLong()
            val blockShow = if (input.readBoolean()) input.readUTF() else null
            val blockRemaining = input.readInt()

            val cursors = HashMap<String, EpisodeKey>()
            repeat(input.readInt()) {
                cursors[input.readUTF()] = EpisodeKey(input.readInt(), input.readInt(), input.readLong())
            }
            val decks = HashMap<DeckKey, DeckState>()
            repeat(input.readInt()) {
                val key = DeckKey(input.readLong(), input.readInt())
                decks[key] = DeckState(
                    fingerprint = input.readLong(),
                    seed = input.readLong(),
                    prevId = input.readLong(),
                    position = input.readInt(),
                    lastId = input.readLong(),
                )
            }
            EngineState(nextStart, rngState, cursors, decks, blockShow, blockRemaining)
        }
    }
}

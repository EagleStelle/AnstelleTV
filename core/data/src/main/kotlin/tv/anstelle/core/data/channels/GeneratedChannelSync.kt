package tv.anstelle.core.data.channels

/** A stored channel as the sync sees it. */
data class SyncedChannel(
    val id: Long,
    /** The generated channel it was made from, or null for a channel built by hand. */
    val autoKey: String?,
    /** True until someone edits it; only managed channels are updated, removed and renumbered. */
    val isManaged: Boolean,
    val number: Int,
    val name: String,
)

/** What a sync does to one profile's channels. */
class SyncActions(
    /** Generated channels no channel holds and nobody removed. */
    val add: List<GeneratedChannel>,
    /** Managed channels and the generated channel they follow; they are rewritten where they differ. */
    val refresh: Map<Long, GeneratedChannel>,
    /** Managed channels that are no longer generated, not even at lower thresholds. */
    val remove: List<Long>,
)

/** Decides how a profile's channels follow the library. Pure, so it is tested without a database. */
object GeneratedChannelSync {
    /**
     * [dismissed] are keys whose channels were deleted; they don't come back. A managed channel
     * that is no longer generated stays while its id is in [keep] (it would still be made at
     * [ChannelGenerator.KEEP_SCALE] of the thresholds).
     */
    fun plan(existing: List<SyncedChannel>, generated: List<GeneratedChannel>, dismissed: Set<String>, keep: Set<Long>): SyncActions {
        val held = existing.mapNotNullTo(HashSet()) { it.autoKey }
        val byKey = generated.associateBy { it.key }
        val refresh = LinkedHashMap<Long, GeneratedChannel>()
        val remove = ArrayList<Long>()
        for (channel in existing) {
            if (!channel.isManaged || channel.autoKey == null) continue
            val current = byKey[channel.autoKey]
            when {
                current != null -> refresh[channel.id] = current
                channel.id !in keep -> remove += channel.id
            }
        }
        return SyncActions(
            add = generated.filter { it.key !in held && it.key !in dismissed },
            refresh = refresh,
            remove = remove,
        )
    }

    /**
     * New numbers for managed channels: each segment's channels count up from its [start] in
     * [order] of `(key, name)` (1000, 1001, 1002…), skipping numbers other channels or earlier
     * segments hold. Returns only the channels whose number changes.
     */
    fun numbers(channels: List<SyncedChannel>, order: Comparator<Pair<String, String>>, start: (ChannelSegment) -> Int): Map<Long, Int> {
        val (managed, others) = channels.partition { it.isManaged && it.autoKey?.let(ChannelGenerator::segmentOf) != null }
        val taken = others.mapTo(HashSet()) { it.number }
        val bySegment = managed.groupBy { ChannelGenerator.segmentOf(it.autoKey!!)!! }
        val changes = LinkedHashMap<Long, Int>()
        for (segment in ChannelSegment.entries) {
            var next = start(segment)
            for (channel in bySegment[segment].orEmpty().sortedWith(compareBy(order) { it.autoKey!! to it.name })) {
                while (next in taken) next++
                taken += next
                if (channel.number != next) changes[channel.id] = next
                next++
            }
        }
        return changes
    }
}

package tv.anstelle.core.data.channels

/** A stored channel as the sync sees it. */
data class SyncedChannel(
    val id: Long,
    /** The generated channel it was made from, or null for a channel built by hand. */
    val autoKey: String?,
)

/** What a sync does to the library channels. */
class SyncActions(
    /** Generated channels no channel holds yet. */
    val add: List<GeneratedChannel>,
    /** Library channels and the generated channel they follow; they are rewritten where they differ. */
    val refresh: Map<Long, GeneratedChannel>,
    /** Library channels that are no longer generated, not even at lower thresholds. */
    val remove: List<Long>,
)

/** Decides how the library channels follow the library. Pure, so it is tested without a database. */
object GeneratedChannelSync {
    /**
     * A library channel that is no longer generated stays while its id is in [keep] (it would
     * still be made at [ChannelGenerator.KEEP_SCALE] of the thresholds). Channels built by hand
     * are left alone.
     */
    fun plan(existing: List<SyncedChannel>, generated: List<GeneratedChannel>, keep: Set<Long>): SyncActions {
        val held = existing.mapNotNullTo(HashSet()) { it.autoKey }
        val byKey = generated.associateBy { it.key }
        val refresh = LinkedHashMap<Long, GeneratedChannel>()
        val remove = ArrayList<Long>()
        for (channel in existing) {
            val key = channel.autoKey ?: continue
            val current = byKey[key]
            when {
                current != null -> refresh[channel.id] = current
                channel.id !in keep -> remove += channel.id
            }
        }
        return SyncActions(
            add = generated.filter { it.key !in held },
            refresh = refresh,
            remove = remove,
        )
    }
}

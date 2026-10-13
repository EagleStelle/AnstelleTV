package tv.anstelle.core.data.channels

/** What the channel list holds, for the settings home's readout. */
data class ChannelStats(
    val total: Int = 0,
    val custom: Int = 0,
    val visible: Int = 0,
    val favorites: Int = 0,
    val hidden: Int = 0,
    val groups: Int = 0,
) {
    val library: Int get() = total - custom

    companion object {
        fun of(channels: List<ChannelSummary>) = ChannelStats(
            total = channels.size,
            custom = channels.count { !it.channel.fromLibrary },
            visible = channels.count { !it.channel.isHidden },
            favorites = channels.count { it.channel.isFavorite },
            hidden = channels.count { it.channel.isHidden },
            groups = channels.mapTo(HashSet()) { it.channel.groupId }.size,
        )
    }
}

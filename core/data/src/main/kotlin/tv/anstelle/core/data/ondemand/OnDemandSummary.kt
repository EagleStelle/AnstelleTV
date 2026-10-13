package tv.anstelle.core.data.ondemand

import tv.anstelle.core.data.Settings

/** On Demand's banners and the row each catalog tab opens with, for the settings home's row and readout. */
data class OnDemandSummary(
    val homeBanner: Int = Settings.OnDemandBanner.default,
    val banners: Map<CatalogKind, Int> = emptyMap(),
    /** Each kind's first row by name. */
    val firstRow: Map<CatalogKind, String> = emptyMap(),
) {
    /** How many picks [kind]'s banner deals. */
    fun banner(kind: CatalogKind): Int = banners[kind] ?: Settings.catalogBanner(kind).default

    /** "10 per banner", or each banner's size when they differ. */
    val summary: String
        get() {
            val sizes = listOf(homeBanner) + CatalogKind.entries.map(::banner)
            return if (sizes.distinct().size == 1) "${sizes.first()} per banner" else "Banners of ${sizes.dropLast(1).joinToString(", ")} and ${sizes.last()}"
        }

    companion object {
        /**
         * The first row of each kind as stored, by name: a channel row by its channel's name from
         * [channelNames]. Without a stored order, the fixed rows lead, as on the tab.
         */
        fun of(homeBanner: Int, banners: Map<CatalogKind, Int>, orders: Map<CatalogKind, String>, channelNames: Map<Long, String>) = OnDemandSummary(
            homeBanner = homeBanner,
            banners = banners,
            firstRow = CatalogKind.entries.mapNotNull { kind ->
                val stored = ShelfOrder.decode(orders[kind].orEmpty())
                val keys = stored.ifEmpty { kind.fixedShelves.map { it.key } }
                keys.firstNotNullOfOrNull { key ->
                    ShelfType.entries.firstOrNull { it.key == key && it != ShelfType.CHANNEL }?.label
                        ?: key.substringAfter("${ShelfType.CHANNEL.key}:", "").toLongOrNull()?.let(channelNames::get)
                }?.let { kind to it }
            }.toMap(),
        )
    }
}

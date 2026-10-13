package tv.anstelle.core.data.ondemand

import tv.anstelle.core.data.Settings

/** On Demand's banners and the rows each catalog tab opens with, for the settings home's row and readout. */
data class OnDemandSummary(
    val homeBanner: Int = Settings.OnDemandBanner.default,
    val banners: Map<CatalogKind, Int> = emptyMap(),
    /** Each kind's first rows by name, in the tab's own order. */
    val firstRows: Map<CatalogKind, List<String>> = emptyMap(),
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
        /** How many rows of each tab the readout names. */
        const val SHOWN_ROWS = 3

        /** The summary from each kind's rows as its tab shows them, channels' among them: [shelves] in order. */
        fun of(homeBanner: Int, banners: Map<CatalogKind, Int>, shelves: Map<CatalogKind, List<Shelf>>) = OnDemandSummary(
            homeBanner = homeBanner,
            banners = banners,
            firstRows = shelves.mapValues { (_, rows) -> rows.take(SHOWN_ROWS).map { it.label } }.filterValues { it.isNotEmpty() },
        )
    }
}

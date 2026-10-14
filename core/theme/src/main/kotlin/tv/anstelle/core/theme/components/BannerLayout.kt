package tv.anstelle.core.theme.components

import androidx.compose.ui.unit.dp

/** Base backdrop proportions for mobile catalogs. TV banners use 5/6 of this height. */
const val BANNER_ASPECT = 16f / 9f

/** Shared TV backdrop proportions for catalog banners and channel, title, and episode pages. */
const val TV_BANNER_ASPECT = BANNER_ASPECT * 6f / 5f

/** A phone-sized banner for the dashboard's narrower TV pane (412 dp at 16:9). */
val COMPACT_TV_BANNER_HEIGHT = 412.dp / BANNER_ASPECT

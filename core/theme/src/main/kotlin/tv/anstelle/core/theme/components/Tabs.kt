package tv.anstelle.core.theme.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.LocalIsTv

/** Tabs and search reserve the same label slot and the same space for their selected rule. */
val TAB_INDICATOR_HEIGHT = 2.dp

@Composable
@ReadOnlyComposable
fun osdTabContentHeight(compact: Boolean): Dp {
    val isTv = LocalIsTv.current
    val padding = if (isTv) (if (compact) 6.dp else 9.dp) else 10.dp
    val line = with(LocalDensity.current) { osdTabLabel(compact).lineHeight.toDp() }
    return maxOf(if (isTv) 32.dp else 48.dp, line + padding * 2)
}

@Composable
@ReadOnlyComposable
fun osdTabHeight(compact: Boolean): Dp = osdTabContentHeight(compact) + TAB_INDICATOR_HEIGHT

/** Header geometry shared by On Demand's tabs/search and the dashboard's tabs/span. */
@Composable
fun OsdTabBar(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth().height(osdTabHeight(compact = LocalIsTv.current)),
        verticalAlignment = Alignment.Bottom,
        content = content,
    )
}

/** Every tab and search field uses this rule, including season tabs on detail pages. */
@Composable
fun OsdTabIndicator(selected: Boolean, focused: Boolean = false, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(TAB_INDICATOR_HEIGHT).background(if (selected && !focused) LocalAnstelleTheme.current.accent else Color.Transparent))
}

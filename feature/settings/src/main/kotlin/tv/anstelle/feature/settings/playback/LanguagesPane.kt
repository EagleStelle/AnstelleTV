package tv.anstelle.feature.settings.playback

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import tv.anstelle.core.model.Languages
import tv.anstelle.core.model.TrackPrefs
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.components.CheckRow
import tv.anstelle.core.theme.components.InfoRow
import tv.anstelle.core.theme.components.RemoveKey
import tv.anstelle.core.theme.components.ReorderRow
import tv.anstelle.core.theme.components.SettingsNotice
import tv.anstelle.core.theme.components.SettingsSearch
import tv.anstelle.core.theme.components.rememberInitialFocus
import tv.anstelle.core.theme.components.rememberReorderList
import tv.anstelle.core.theme.components.reorderItems
import tv.anstelle.core.theme.components.section
import tv.anstelle.feature.settings.PaneFrame

/** The two ordered language lists. */
internal enum class LanguageList { AUDIO, SUBTITLES }

internal fun TrackPrefs.languages(list: LanguageList): List<String> = when (list) {
    LanguageList.AUDIO -> audioLanguages
    LanguageList.SUBTITLES -> subtitleLanguages
}

internal fun TrackPrefs.with(list: LanguageList, codes: List<String>): TrackPrefs = when (list) {
    LanguageList.AUDIO -> copy(audioLanguages = codes)
    LanguageList.SUBTITLES -> copy(subtitleLanguages = codes)
}

/**
 * A language list as a pane: the list in order, numbered, each moved by its handle and removed
 * by its bin; then every language with a check, found by what is typed, where checking one adds
 * it to the end and unchecking takes it off. Focus stays on a language as it is checked.
 */
@Composable
internal fun LanguagesPane(pane: PlaybackPane, list: LanguageList, codes: List<String>, onClose: () -> Unit, onApply: (List<String>) -> Unit) {
    var working by remember { mutableStateOf(codes) }
    var filter by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val firstFocus = rememberInitialFocus()
    val order = rememberReorderList(working, listState, key = { "order-$it" }) { code, from, to -> working = working.moved(code, to - from) }
    val all = remember { Languages.all() }
    val shown = all.filter { filter.isBlank() || it.name.contains(filter.trim(), ignoreCase = true) }

    PaneFrame(pane.title, PlaybackTrail, codes, working, onApply, onClose, about = languagesAbout(list), listState = listState) {
        section("Order")
        if (working.isEmpty()) item(key = "none") { SettingsNotice(languagesNone(list)) }
        reorderItems(order) { index, code ->
            ReorderRow(list = order, item = code, title = languageName(code), modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier) { rowModifier ->
                // The language is a fact; its red bin removes it.
                Row(rowModifier, verticalAlignment = Alignment.CenterVertically) {
                    InfoRow(title = languageName(code), leading = { OrderNumber(index + 1) }, modifier = Modifier.weight(1f))
                    RemoveKey("Remove ${languageName(code)}", onClick = { working = working - code })
                }
            }
        }
        section("All languages")
        item(key = "find") { SettingsSearch(filter, { filter = it }, "Find a language") }
        items(shown, key = { "all-${it.code}" }) { language ->
            val checked = language.code in working
            CheckRow(
                title = language.name,
                checked = checked,
                onClick = { working = if (checked) working - language.code else working + language.code },
                // With none in order, the first language takes the pane's first focus, so the keyboard waits for the search.
                modifier = if (working.isEmpty() && language == shown.first()) Modifier.focusRequester(firstFocus) else Modifier,
            )
        }
    }
}

/** [code] moved [delta] places, or the list as it is when that leaves it. */
private fun List<String>.moved(code: String, delta: Int): List<String> {
    val from = indexOf(code)
    val to = from + delta
    return if (from < 0 || to !in indices) this else toMutableList().apply { add(to, removeAt(from)) }
}

/** A language's place in its list, in the icon's slot: tabular, so the column of numbers lines up. */
@Composable
private fun OrderNumber(number: Int) {
    val theme = LocalAnstelleTheme.current
    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        androidx.compose.material3.Text(
            "$number",
            style = androidx.compose.material3.MaterialTheme.typography.titleMedium.merge(TextStyle(fontFamily = theme.displayFont, fontFeatureSettings = "tnum")),
            color = androidx.compose.material3.LocalContentColor.current,
        )
    }
}

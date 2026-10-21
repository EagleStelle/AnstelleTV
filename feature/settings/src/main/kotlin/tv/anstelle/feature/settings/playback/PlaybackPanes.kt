package tv.anstelle.feature.settings.playback

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.SubtitleView
import tv.anstelle.core.model.SegmentAction
import tv.anstelle.core.model.SegmentType
import tv.anstelle.core.model.SubtitleBackground
import tv.anstelle.core.model.SubtitleColor
import tv.anstelle.core.model.SubtitleEdge
import tv.anstelle.core.model.SubtitleFont
import tv.anstelle.core.model.SubtitleLook
import tv.anstelle.core.model.SubtitleMode
import tv.anstelle.core.model.SubtitleSize
import tv.anstelle.core.player.SubtitleStyle
import tv.anstelle.core.theme.PanelShape
import tv.anstelle.core.theme.components.rememberInitialFocus
import tv.anstelle.core.theme.components.section
import tv.anstelle.feature.settings.EditPane
import tv.anstelle.feature.settings.choiceRows

/** When subtitles show, as a radio list with what each mode does with [subtitleLanguages]. */
@Composable
internal fun SubtitleModePane(pane: PlaybackPane, mode: SubtitleMode, subtitleLanguages: List<String>, onClose: () -> Unit, onApply: (SubtitleMode) -> Unit) {
    val firstFocus = rememberInitialFocus()
    EditPane(pane.title, pane.name, mode, PlaybackTrail, onApply, onClose, about = ModeAbout) { working, edit ->
        choiceRows("mode", ModeOrder, working, ::modeName, onPick = { picked -> edit { picked } }, detail = { modeHint(it, subtitleLanguages) }, firstFocus = firstFocus)
    }
}

/**
 * How plain subtitles look in every player: each part a radio list under its heading, beside a
 * sample that answers each pick, held in view above the list on phones.
 */
@Composable
internal fun SubtitleStylePane(pane: PlaybackPane, look: SubtitleLook, onClose: () -> Unit, onApply: (SubtitleLook) -> Unit) {
    val firstFocus = rememberInitialFocus()
    EditPane(
        title = pane.title,
        id = pane.name,
        value = look,
        trail = PlaybackTrail,
        onApply = onApply,
        onClose = onClose,
        about = "Applies to plain text subtitles, such as SRT. Styled (ASS) and image (PGS) subtitles keep the look they came with.",
        aside = { SubtitleSample(it) },
        pinAside = true,
    ) { working, edit ->
        section("Size")
        choiceRows("size", SubtitleSize.entries, working.size, ::sizeName, onPick = { size -> edit { it.copy(size = size) } }, firstFocus = firstFocus)
        section("Font")
        choiceRows("font", SubtitleFont.entries, working.font, ::fontName, onPick = { font -> edit { it.copy(font = font) } })
        section("Color")
        choiceRows("color", SubtitleColor.entries, working.color, ::colorName, onPick = { color -> edit { it.copy(color = color) } })
        section("Edge")
        choiceRows("edge", SubtitleEdge.entries, working.edge, ::edgeName, onPick = { edge -> edit { it.copy(edge = edge) } }, detail = ::edgeHint)
        section("Background")
        choiceRows("background", SubtitleBackground.entries, working.background, ::backgroundName, onPick = { background -> edit { it.copy(background = background) } }, detail = ::backgroundHint)
    }
}

/** What On Demand does with each kind of segment, a radio list under each kind's heading. */
@Composable
internal fun SkippingPane(
    pane: PlaybackPane,
    skipping: Map<SegmentType, SegmentAction>,
    onClose: () -> Unit,
    onApply: (Map<SegmentType, SegmentAction>) -> Unit,
) {
    val firstFocus = rememberInitialFocus()
    EditPane(pane.title, pane.name, skipping, PlaybackTrail, onApply, onClose, about = SkippingAbout) { working, edit ->
        SegmentType.entries.forEachIndexed { i, type ->
            section(segmentName(type))
            choiceRows(
                "skip-${type.key}",
                SkipOrder,
                working.getValue(type),
                ::skipName,
                onPick = { action -> edit { it + (type to action) } },
                detail = { skipHint(type, it) },
                firstFocus = firstFocus.takeIf { i == 0 },
            )
        }
    }
}

/**
 * Two lines of subtitles in [look], drawn by the players' own subtitle view so they match, over
 * a picture that runs from a bright scene to a dark one. It only shows.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun SubtitleSample(look: SubtitleLook) {
    AndroidView(
        factory = { context -> SubtitleView(context).apply { setCues(listOf(Cue.Builder().setText(SAMPLE).build())) } },
        update = { SubtitleStyle.applyTo(it, look) },
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(PanelShape)
            // Stands in for video, so no theme role: a bright scene fading into a dark one.
            .background(Brush.horizontalGradient(listOf(Color(0xFFE4E4E4), Color(0xFF7A7A7A), Color(0xFF101010))))
            .clearAndSetSemantics { },
    )
}

private const val SAMPLE = "This is how subtitles will look\nover bright and dark scenes."

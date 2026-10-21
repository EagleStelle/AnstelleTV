package tv.anstelle.feature.settings.playback

import tv.anstelle.core.model.Languages
import tv.anstelle.core.model.SegmentAction
import tv.anstelle.core.model.SegmentType
import tv.anstelle.core.model.SubtitleBackground
import tv.anstelle.core.model.SubtitleColor
import tv.anstelle.core.model.SubtitleEdge
import tv.anstelle.core.model.SubtitleFont
import tv.anstelle.core.model.SubtitleLook
import tv.anstelle.core.model.SubtitleMode
import tv.anstelle.core.model.SubtitleSize
import tv.anstelle.core.model.WatchSync

internal fun languageName(code: String): String = Languages.displayName(code).replaceFirstChar { it.titlecase() }

/** A language list for its row, in order, or what an empty one means. */
internal fun languagesSummary(list: LanguageList, codes: List<String>): String = codes.joinToString(", ", transform = ::languageName).ifEmpty { languagesEmpty(list) }

/** What an empty list means, in a row's few words. */
internal fun languagesEmpty(list: LanguageList): String = when (list) {
    LanguageList.AUDIO -> "Each program's default"
    LanguageList.SUBTITLES -> "None chosen"
}

internal fun languagesAbout(list: LanguageList): String = when (list) {
    LanguageList.AUDIO -> "Programs play the first of these languages they have, or their own default track."
    LanguageList.SUBTITLES -> "Subtitles come in the first of these languages a program has."
} + " Pick more under All languages; each joins the end. Drag one by its handle to reorder."

/** What an empty list means, where its languages would be. */
internal fun languagesNone(list: LanguageList): String = when (list) {
    LanguageList.AUDIO -> "None yet. Programs play their own default track."
    LanguageList.SUBTITLES -> "None yet. Subtitles stay off until you turn them on while watching."
}

/** The subtitle modes in the order their choices list them: Jellyfin's own, in its order. */
internal val ModeOrder = listOf(SubtitleMode.SMART, SubtitleMode.FORCED, SubtitleMode.ALWAYS, SubtitleMode.OFF)

internal fun modeName(mode: SubtitleMode): String = when (mode) {
    SubtitleMode.OFF -> "None"
    SubtitleMode.ALWAYS -> "Always"
    SubtitleMode.SMART -> "Smart"
    SubtitleMode.FORCED -> "Forced"
}

/** The mode pane's note: the two kinds of subtitle the hints name. */
internal const val ModeAbout = "Full subtitles cover all dialogue. Forced subtitles cover only signs and foreign lines."

/** What [mode] does; Smart names the [subtitleLanguages] the playing audio is checked against. */
internal fun modeHint(mode: SubtitleMode, subtitleLanguages: List<String>): String = when (mode) {
    SubtitleMode.SMART -> {
        val names = subtitleLanguages.map(::languageName)
        val audio = if (names.isEmpty()) "Audio in a subtitle language" else names.dropLast(1).joinToString(", ").let { if (it.isEmpty()) "" else "$it or " } + "${names.last()} audio"
        "$audio gets forced subtitles, other audio gets full ones."
    }
    SubtitleMode.FORCED -> "Forced subtitles, whatever the audio."
    SubtitleMode.ALWAYS -> "Full subtitles, whatever the audio."
    SubtitleMode.OFF -> "Off until you turn them on while watching."
}

/** The look in a few words for its row: "Medium white, outline", "Large yellow, solid box". */
internal fun lookSummary(look: SubtitleLook): String = listOfNotNull(
    "${sizeName(look.size)} ${colorName(look.color).lowercase()}",
    edgeName(look.edge).lowercase().takeIf { look.edge != SubtitleEdge.NONE },
    "${backgroundName(look.background).lowercase()} box".takeIf { look.background != SubtitleBackground.NONE },
).joinToString(", ")

internal fun sizeName(size: SubtitleSize): String = when (size) {
    SubtitleSize.SMALL -> "Small"
    SubtitleSize.MEDIUM -> "Medium"
    SubtitleSize.LARGE -> "Large"
    SubtitleSize.EXTRA_LARGE -> "Extra large"
}

/** The theme designer's names for the same families. */
internal fun fontName(font: SubtitleFont): String = when (font) {
    SubtitleFont.SANS -> "Sans"
    SubtitleFont.CONDENSED -> "Condensed"
    SubtitleFont.SERIF -> "Serif"
    SubtitleFont.MONO -> "Mono"
}

internal fun colorName(color: SubtitleColor): String = when (color) {
    SubtitleColor.WHITE -> "White"
    SubtitleColor.GRAY -> "Gray"
    SubtitleColor.YELLOW -> "Yellow"
    SubtitleColor.CYAN -> "Cyan"
    SubtitleColor.GREEN -> "Green"
}

internal fun edgeName(edge: SubtitleEdge): String = when (edge) {
    SubtitleEdge.OUTLINE -> "Outline"
    SubtitleEdge.DROP_SHADOW -> "Drop shadow"
    SubtitleEdge.RAISED -> "Raised"
    SubtitleEdge.SUNKEN -> "Sunken"
    SubtitleEdge.NONE -> "None"
}

internal fun edgeHint(edge: SubtitleEdge): String = when (edge) {
    SubtitleEdge.OUTLINE -> "A dark line around each letter."
    SubtitleEdge.DROP_SHADOW -> "A dark shadow down and to the right of each letter."
    SubtitleEdge.RAISED -> "Letters look raised: light above, dark below."
    SubtitleEdge.SUNKEN -> "Letters look pressed in: dark above, light below."
    SubtitleEdge.NONE -> "Plain letters. A box keeps them readable over bright scenes."
}

internal fun backgroundName(background: SubtitleBackground): String = when (background) {
    SubtitleBackground.NONE -> "None"
    SubtitleBackground.TRANSLUCENT -> "Translucent"
    SubtitleBackground.SOLID -> "Solid"
}

internal fun backgroundHint(background: SubtitleBackground): String? = when (background) {
    SubtitleBackground.NONE -> null
    SubtitleBackground.TRANSLUCENT -> "A dark box behind each line that lets the picture show through."
    SubtitleBackground.SOLID -> "A black box behind each line."
}

internal const val SkippingAbout = "What On Demand does while each kind of segment plays. Channels play like TV and skip nothing."

/** Skipping in a few words for its row: "Ask to skip all", "Skip intros and recaps, play previews". */
internal fun skippingSummary(skipping: Map<SegmentType, SegmentAction>): String {
    skipping.values.toSet().singleOrNull()?.let { return "${skipName(it)} all" }
    return SkipOrder.filter { it != SegmentAction.ASK_TO_SKIP }.mapNotNull { action ->
        val kinds = SegmentType.entries.filter { skipping[it] == action }.map { segmentName(it).lowercase() }
        if (kinds.isEmpty()) null else "${skipName(action).lowercase()} ${kinds.dropLast(1).joinToString(", ").let { if (it.isEmpty()) "" else "$it and " }}${kinds.last()}"
    }.joinToString(", ").replaceFirstChar { it.titlecase() }
}

/** A kind of segment as its heading names it: Intros, Outros… */
internal fun segmentName(type: SegmentType): String = when (type) {
    SegmentType.INTRO -> "Intros"
    SegmentType.OUTRO -> "Outros"
    SegmentType.RECAP -> "Recaps"
    SegmentType.PREVIEW -> "Previews"
    SegmentType.COMMERCIAL -> "Commercials"
}

/** The [SegmentAction] choices in the order the Skipping pane lists them: Jellyfin's own, in its order. */
internal val SkipOrder = listOf(SegmentAction.ASK_TO_SKIP, SegmentAction.SKIP, SegmentAction.PLAY)

internal fun skipName(action: SegmentAction): String = when (action) {
    SegmentAction.ASK_TO_SKIP -> "Ask to skip"
    SegmentAction.SKIP -> "Skip"
    SegmentAction.PLAY -> "Play"
}

internal fun skipHint(type: SegmentType, action: SegmentAction): String = when (action) {
    SegmentAction.ASK_TO_SKIP -> "A Skip ${type.key.replaceFirstChar { it.titlecase() }} key shows while one plays."
    SegmentAction.SKIP -> "Skipped as soon as one starts. Going back into it plays it."
    SegmentAction.PLAY -> "Plays through, with nothing to press."
}

/** The [WatchSync] choices in the order the Watch history page lists them. */
internal val SyncOrder = listOf(WatchSync.STATUS, WatchSync.PROGRESS, WatchSync.BOTH, WatchSync.OFF)

internal fun syncName(sync: WatchSync): String = when (sync) {
    WatchSync.STATUS -> "Status"
    WatchSync.PROGRESS -> "Progress"
    WatchSync.BOTH -> "Both"
    WatchSync.OFF -> "Off"
}

internal fun syncDescription(place: SyncPlace, sync: WatchSync): String = when (place) {
    SyncPlace.LIVE_TV -> when (sync) {
        WatchSync.STATUS -> "What you finish is marked watched after at least 30 seconds of playback, even if you joined late."
        WatchSync.PROGRESS -> "After 30 seconds of playback, progress updates every 5 seconds and appears in Now Playing. Watched status stays unchanged."
        WatchSync.BOTH -> "After 30 seconds of playback, progress updates every 5 seconds and appears in Now Playing. What you finish is marked watched."
        WatchSync.OFF -> "Live TV sends nothing to your server."
    }
    SyncPlace.ON_DEMAND -> when (sync) {
        WatchSync.STATUS -> "What you finish is marked watched. Where you stop isn't saved."
        WatchSync.PROGRESS -> "Progress updates every 5 seconds and appears in Now Playing. Watched status stays unchanged."
        WatchSync.BOTH -> "Progress updates every 5 seconds and appears in Now Playing. What you finish is marked watched."
        WatchSync.OFF -> "On Demand sends nothing to your server."
    }
}

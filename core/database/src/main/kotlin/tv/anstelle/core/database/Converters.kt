package tv.anstelle.core.database

import androidx.room.TypeConverter
import tv.anstelle.core.model.BreakMode
import tv.anstelle.core.model.ChannelKind
import tv.anstelle.core.model.FillerKind
import tv.anstelle.core.model.FillerSource
import tv.anstelle.core.model.HdrType
import tv.anstelle.core.model.LibraryRole
import tv.anstelle.core.model.LiveSourceType
import tv.anstelle.core.model.MediaType
import tv.anstelle.core.model.PlaybackOrder
import tv.anstelle.core.model.RuleField
import tv.anstelle.core.model.RuleOp
import tv.anstelle.core.model.SeasonMode
import tv.anstelle.core.model.SegmentType
import tv.anstelle.core.model.SlotKind
import tv.anstelle.core.model.WatchSource
import tv.anstelle.core.model.codeOf

/** Enums are stored as their stable integer codes. */
internal class Converters {
    @TypeConverter fun fromMediaType(value: MediaType): Int = value.code
    @TypeConverter fun toMediaType(code: Int): MediaType = codeOf(code)

    @TypeConverter fun fromPlaybackOrder(value: PlaybackOrder): Int = value.code
    @TypeConverter fun toPlaybackOrder(code: Int): PlaybackOrder = codeOf(code)

    @TypeConverter fun fromSlotKind(value: SlotKind): Int = value.code
    @TypeConverter fun toSlotKind(code: Int): SlotKind = codeOf(code)

    @TypeConverter fun fromChannelKind(value: ChannelKind): Int = value.code
    @TypeConverter fun toChannelKind(code: Int): ChannelKind = codeOf(code)

    @TypeConverter fun fromRuleField(value: RuleField): Int = value.code
    @TypeConverter fun toRuleField(code: Int): RuleField = codeOf(code)

    @TypeConverter fun fromRuleOp(value: RuleOp): Int = value.code
    @TypeConverter fun toRuleOp(code: Int): RuleOp = codeOf(code)

    @TypeConverter fun fromSeasonMode(value: SeasonMode): Int = value.code
    @TypeConverter fun toSeasonMode(code: Int): SeasonMode = codeOf(code)

    @TypeConverter fun fromFillerKind(value: FillerKind): Int = value.code
    @TypeConverter fun toFillerKind(code: Int): FillerKind = codeOf(code)

    @TypeConverter fun fromFillerSource(value: FillerSource): Int = value.code
    @TypeConverter fun toFillerSource(code: Int): FillerSource = codeOf(code)

    @TypeConverter fun fromBreakMode(value: BreakMode): Int = value.code
    @TypeConverter fun toBreakMode(code: Int): BreakMode = codeOf(code)

    @TypeConverter fun fromLibraryRole(value: LibraryRole): Int = value.code
    @TypeConverter fun toLibraryRole(code: Int): LibraryRole = codeOf(code)

    @TypeConverter fun fromLiveSourceType(value: LiveSourceType): Int = value.code
    @TypeConverter fun toLiveSourceType(code: Int): LiveSourceType = codeOf(code)

    @TypeConverter fun fromSegmentType(value: SegmentType): Int = value.code
    @TypeConverter fun toSegmentType(code: Int): SegmentType = codeOf(code)

    @TypeConverter fun fromHdrType(value: HdrType): Int = value.code
    @TypeConverter fun toHdrType(code: Int): HdrType = codeOf(code)

    @TypeConverter fun fromWatchSource(value: WatchSource): Int = value.code
    @TypeConverter fun toWatchSource(code: Int): WatchSource = codeOf(code)
}

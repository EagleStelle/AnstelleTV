package tv.anstelle.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CodedEnumTest {
    private val all: List<List<CodedEnum>> = listOf(
        MediaType.entries, PlaybackOrder.entries, SlotKind.entries, ChannelKind.entries,
        RuleField.entries, RuleOp.entries, SeasonMode.entries, LiveSourceType.entries,
        HdrType.entries, WatchSource.entries, WatchSync.entries, SubtitleMode.entries, ProfileRole.entries,
    )

    @Test
    fun codesAreUniquePerEnum() {
        for (entries in all) {
            val codes = entries.map { it.code }
            assertEquals(codes.size, codes.toSet().size, "duplicate code in ${entries.first()::class.simpleName}")
        }
    }

    @Test
    fun codeOfRoundTrips() {
        for (type in MediaType.entries) assertEquals(type, codeOf<MediaType>(type.code))
        for (kind in SlotKind.entries) assertEquals(kind, codeOf<SlotKind>(kind.code))
    }

    @Test
    fun unknownCodeFails() {
        assertFailsWith<IllegalArgumentException> { codeOf<SlotKind>(99) }
    }
}

package tv.anstelle.feature.settings.channels

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tv.anstelle.core.data.channels.ChannelRepository
import tv.anstelle.core.data.channels.Segment
import tv.anstelle.core.model.ChannelGroup
import tv.anstelle.core.theme.components.ChoiceRow
import tv.anstelle.core.theme.components.EditSheet
import tv.anstelle.core.theme.components.LocalSettingsSheets

/** Where a new channel goes: a group (its Live TV tab) and a segment (its numbers). */
@Immutable
data class Placement(
    val groups: List<ChannelGroup> = emptyList(),
    val segments: List<Segment> = emptyList(),
    val groupId: Long? = null,
    val segment: String? = null,
) {
    /** What still has to be picked, or null once both are. */
    val problem: String?
        get() = when {
            groups.none { it.id == groupId } -> "Pick a group."
            segments.none { it.key == segment } -> "Pick a segment."
            else -> null
        }
}

/**
 * Holds a new channel's group and segment while it is made, starting with the ones the last
 * channel went into. A group or segment made here is picked at once.
 */
class PlacementPicker(
    private val repository: ChannelRepository,
    private val scope: CoroutineScope,
    private val onProblem: (String) -> Unit,
) {
    private val picked = MutableStateFlow<Pair<Long?, String?>>(null to null)

    val state: StateFlow<Placement> =
        combine(repository.observeGroups(), repository.observeSegments(), picked) { groups, segments, (groupId, segment) ->
            Placement(groups, segments, groupId, segment)
        }.stateIn(scope, SharingStarted.Eagerly, Placement())

    init {
        scope.launch {
            val (group, segment) = repository.lastPlacement()
            // A pick made before the last one was read stays.
            picked.update { (pickedGroup, pickedSegment) -> (pickedGroup ?: group) to (pickedSegment ?: segment) }
        }
    }

    fun pickGroup(groupId: Long) = picked.update { it.copy(first = groupId) }

    fun pickSegment(segment: String) = picked.update { it.copy(second = segment) }

    fun newGroup(name: String) = make { pickGroup(repository.createGroup(name)) }

    fun newSegment(name: String) = make { pickSegment(Segment.keyOf(repository.createSegment(name))) }

    private fun make(action: suspend () -> Unit) {
        scope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onProblem(e.message ?: "That didn't work.")
            }
        }
    }
}

/** Marks New group and New segment among the choices. */
private const val NEW_GROUP = -1L
private const val NEW_SEGMENT = "new"

/** A channel's group, its tab in Live TV. The last choice names a new group, which [onCreate] makes. */
@Composable
internal fun GroupChoiceRow(
    groups: List<ChannelGroup>,
    selected: Long?,
    onSelect: (Long) -> Unit,
    onCreate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheets = LocalSettingsSheets.current
    ChoiceRow<Long?>(
        title = "Group",
        value = selected?.takeIf { id -> groups.any { it.id == id } },
        choices = groups.map { it.id } + NEW_GROUP,
        label = { id -> if (id == NEW_GROUP) "New group" else groups.firstOrNull { it.id == id }?.name ?: "Pick one" },
        about = "Its tab in Live TV.",
        onSelect = { id ->
            if (id == NEW_GROUP) {
                sheets.show(
                    EditSheet("New group", null, "Name", "", KeyboardType.Text, { it.take(40) }) { name ->
                        if (name.isBlank()) "Give the group a name." else null.also { onCreate(name) }
                    },
                )
            } else if (id != null) {
                onSelect(id)
            }
        },
        icon = Icons.Filled.Folder,
        modifier = modifier,
    )
}

/** A channel's segment, which gives it its number. The last choice names a new segment, which [onCreate] makes. */
@Composable
internal fun SegmentChoiceRow(
    segments: List<Segment>,
    selected: String?,
    onSelect: (String) -> Unit,
    onCreate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheets = LocalSettingsSheets.current
    ChoiceRow<String?>(
        title = "Segment",
        value = selected?.takeIf { key -> segments.any { it.key == key } },
        choices = segments.map { it.key } + NEW_SEGMENT,
        label = { key -> if (key == NEW_SEGMENT) "New segment" else segments.firstOrNull { it.key == key }?.name ?: "Pick one" },
        detail = { key -> segments.firstOrNull { it.key == key }?.let { "Starts at ${it.start}" } },
        about = "Sets its channel number. Channels in a segment are numbered A to Z from its start.",
        onSelect = { key ->
            if (key == NEW_SEGMENT) {
                sheets.show(
                    EditSheet("New segment", null, "Name", "", KeyboardType.Text, { it.take(40) }) { name ->
                        if (name.isBlank()) "Give the segment a name." else null.also { onCreate(name) }
                    },
                )
            } else if (key != null) {
                onSelect(key)
            }
        },
        icon = Icons.Filled.FormatListNumbered,
        modifier = modifier,
    )
}

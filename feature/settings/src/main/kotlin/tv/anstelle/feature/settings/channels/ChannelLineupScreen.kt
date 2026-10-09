package tv.anstelle.feature.settings.channels

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.Scale
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.anstelle.core.data.channels.Catalog
import tv.anstelle.core.data.channels.ChannelRepository
import tv.anstelle.core.data.channels.Lineup
import tv.anstelle.core.data.channels.LineupMatcher
import tv.anstelle.core.data.channels.Pick
import tv.anstelle.core.data.channels.Rule
import tv.anstelle.core.data.channels.RuleGroup
import tv.anstelle.core.model.PlaybackOrder
import tv.anstelle.core.model.RuleField
import tv.anstelle.core.model.RuleOp
import tv.anstelle.core.theme.components.AnstelleTextField
import tv.anstelle.core.theme.components.CheckRow
import tv.anstelle.core.theme.components.ChoiceRow
import tv.anstelle.core.theme.components.LinkRow
import tv.anstelle.core.theme.components.MenuAction
import tv.anstelle.core.theme.components.MenuRow
import tv.anstelle.core.theme.components.OptionRow
import tv.anstelle.core.theme.components.OsdButton
import tv.anstelle.core.theme.components.PageFocusScope
import tv.anstelle.core.theme.components.SettingsButtons
import tv.anstelle.core.theme.components.SettingsNotice
import tv.anstelle.core.theme.components.SettingsPage
import tv.anstelle.core.theme.components.TextRow
import tv.anstelle.core.theme.components.rememberInitialFocus
import tv.anstelle.core.theme.components.section
import tv.anstelle.core.theme.components.settingsEdge
import javax.inject.Inject

/** Where the builder is. Rules are added in three steps: field, operator, values. */
sealed interface BuilderPage {
    data object Main : BuilderPage

    /** Choosing what a new rule tests. A [group] equal to the group count starts a new group. */
    data class Field(val group: Int) : BuilderPage

    data class Op(val group: Int, val field: RuleField) : BuilderPage

    /**
     * Choosing values from a list: genres, networks, shows, types, ratings, years. For BETWEEN the
     * first pick is [from] and the second ends the range.
     */
    data class Choose(
        val group: Int,
        val field: RuleField,
        val op: RuleOp,
        val selected: Set<String> = emptySet(),
        val query: String = "",
        val from: ValueOption? = null,
    ) : BuilderPage

    /** Typing values: numbers, keywords or text to look for. */
    data class Type(val group: Int, val field: RuleField, val op: RuleOp, val first: String = "", val second: String = "") : BuilderPage

    /** Choosing a show or movie to always or never include. */
    data class Shows(val include: Boolean, val query: String = "") : BuilderPage
}

/** A value to pick: what is stored, what is shown, and how many items have it. */
@Immutable
data class ValueOption(val value: String, val label: String, val count: Int)

@Immutable
data class GroupRow(val rules: List<String>, val weight: Float)

@Immutable
data class PickRow(val title: String, val weight: Float)

@Immutable
data class LineupUiState(
    val isNew: Boolean = false,
    val name: String = "",
    val groups: List<GroupRow> = emptyList(),
    val includes: List<PickRow> = emptyList(),
    val excludes: List<PickRow> = emptyList(),
    /** What the lineup airs; null while it is being counted. */
    val reach: LineupReach? = null,
    val page: BuilderPage = BuilderPage.Main,
    /** Choices for the Choose and Shows pages, filtered by the query. */
    val options: List<ValueOption> = emptyList(),
    val error: String? = null,
    val busy: Boolean = false,
    val loaded: Boolean = false,
    /** A library channel was reached through a restored or otherwise stale route. */
    val locked: Boolean = false,
    /** Set once saved: the channel's id. */
    val savedChannelId: Long? = null,
)

@HiltViewModel
class ChannelLineupViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repository: ChannelRepository,
) : ViewModel() {
    /** Matches the `channelId` argument of the navigation route; 0 builds a new channel. */
    private val channelId: Long = savedState.get<Long>("channelId") ?: 0L

    private val _state = MutableStateFlow(LineupUiState(isNew = channelId == 0L))
    val state: StateFlow<LineupUiState> = _state.asStateFlow()

    /** A new channel's group and segment. */
    val placement = PlacementPicker(repository, viewModelScope) { fail(it) }

    private var catalog = Catalog(emptyList())
    private var lineup = Lineup.EMPTY
    private var summaryJob: Job? = null

    /** The library's airtime, for a lineup's share of it. */
    private var libraryMs = 0L

    /** Each rating score with the ratings the library gives it ("TV-14, PG-13"). */
    private var ratingNames: Map<Int, String> = emptyMap()

    init {
        viewModelScope.launch {
            catalog = repository.catalog()
            libraryMs = catalog.items.sumOf { it.row.durationMs }
            ratingNames = catalog.items
                .mapNotNull { item -> item.row.ratingScore?.let { score -> item.row.officialRating?.let { score to it } } }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, names) -> names.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER).take(3).joinToString(", ") }
            if (channelId != 0L) {
                val channel = repository.observeChannel(channelId).first()
                if (channel?.fromLibrary == true) {
                    _state.update { it.copy(name = channel.name, locked = true, loaded = true) }
                    return@launch
                }
                lineup = repository.lineup(channelId)
                _state.update { it.copy(name = channel?.name.orEmpty()) }
            }
            _state.update { it.copy(loaded = true) }
            changed()
        }
    }

    fun setName(name: String) = _state.update { it.copy(name = name, error = null) }

    fun addGroup() = go(BuilderPage.Field(lineup.groups.size))

    fun addRule(group: Int) = go(BuilderPage.Field(group))

    fun chooseField(field: RuleField) {
        val page = _state.value.page as? BuilderPage.Field ?: return
        go(BuilderPage.Op(page.group, field))
    }

    fun chooseOp(op: RuleOp) {
        val page = _state.value.page as? BuilderPage.Op ?: return
        // Only keywords and "contains" are typed; everything else is picked from what the library has.
        val typed = page.field == RuleField.KEYWORD || op == RuleOp.CONTAINS
        go(if (typed) BuilderPage.Type(page.group, page.field, op) else BuilderPage.Choose(page.group, page.field, op))
    }

    /**
     * Picks a value: a rule that takes one value is added at once; BETWEEN takes where the range
     * starts, then where it ends; a list toggles the value.
     */
    fun toggleValue(option: ValueOption) {
        val page = _state.value.page as? BuilderPage.Choose ?: return
        val value = option.value
        when {
            page.op == RuleOp.BETWEEN && page.from == null -> go(page.copy(from = option, query = ""))
            page.op == RuleOp.BETWEEN -> add(page.group, Rule(page.field, page.op, listOf(page.from!!.value, value)))
            page.op in SinglePickOps -> add(page.group, Rule(page.field, page.op, listOf(value)))
            else -> go(page.copy(selected = if (value in page.selected) page.selected - value else page.selected + value))
        }
    }

    fun setQuery(query: String) {
        when (val page = _state.value.page) {
            is BuilderPage.Choose -> go(page.copy(query = query))
            is BuilderPage.Shows -> go(page.copy(query = query))
            else -> Unit
        }
    }

    fun setTyped(first: String, second: String) {
        val page = _state.value.page as? BuilderPage.Type ?: return
        go(page.copy(first = first, second = second))
    }

    /** Adds the rule being built on the Choose or Type page. */
    fun confirmRule() {
        when (val page = _state.value.page) {
            is BuilderPage.Choose -> {
                if (page.selected.isEmpty()) return fail("Pick at least one.")
                add(page.group, Rule(page.field, page.op, page.selected.toList()))
            }
            is BuilderPage.Type -> {
                val values = when {
                    page.op == RuleOp.BETWEEN -> listOf(page.first, page.second)
                    page.field == RuleField.KEYWORD -> page.first.split(',')
                    else -> listOf(page.first)
                }.map { it.trim() }.filter { it.isNotEmpty() }
                val rule = Rule(page.field, page.op, values)
                rule.problem()?.let { return fail(it) }
                add(page.group, rule)
            }
            else -> Unit
        }
    }

    fun removeRule(group: Int, index: Int) {
        val groups = lineup.groups.toMutableList()
        val rules = groups[group].rules.filterIndexed { i, _ -> i != index }
        if (rules.isEmpty()) groups.removeAt(group) else groups[group] = groups[group].copy(rules = rules)
        lineup = lineup.copy(groups = groups)
        changed()
    }

    /** Names a rating score for a rule's description ("TV-14, PG-13"). */
    private fun ratingOf(value: String): String? = value.toIntOrNull()?.let(ratingNames::get)

    fun setGroupWeight(group: Int, weight: Float) {
        lineup = lineup.copy(groups = lineup.groups.mapIndexed { i, g -> if (i == group) g.copy(weight = weight) else g })
        changed()
    }

    fun removeGroup(group: Int) {
        lineup = lineup.copy(groups = lineup.groups.filterIndexed { i, _ -> i != group })
        changed()
    }

    fun openShows(include: Boolean) = go(BuilderPage.Shows(include))

    fun pickShow(remoteId: String) {
        val page = _state.value.page as? BuilderPage.Shows ?: return
        val field = if (catalog.isSeries(remoteId)) RuleField.SERIES else RuleField.ITEM
        val pick = Pick(field, remoteId)
        lineup = if (page.include) {
            lineup.copy(includes = lineup.includes.filter { it.remoteId != remoteId } + pick)
        } else {
            lineup.copy(excludes = lineup.excludes.filter { it.remoteId != remoteId } + pick)
        }
        go(BuilderPage.Main)
        changed()
    }

    fun removePick(include: Boolean, index: Int) {
        lineup = if (include) {
            lineup.copy(includes = lineup.includes.filterIndexed { i, _ -> i != index })
        } else {
            lineup.copy(excludes = lineup.excludes.filterIndexed { i, _ -> i != index })
        }
        changed()
    }

    fun setPickWeight(index: Int, weight: Float) {
        lineup = lineup.copy(includes = lineup.includes.mapIndexed { i, p -> if (i == index) p.copy(weight = weight) else p })
        changed()
    }

    /** Steps back one page; false on the main page. */
    fun back(): Boolean {
        val previous = when (val page = _state.value.page) {
            BuilderPage.Main -> return false
            is BuilderPage.Field, is BuilderPage.Shows -> BuilderPage.Main
            is BuilderPage.Op -> BuilderPage.Field(page.group)
            is BuilderPage.Choose -> if (page.from != null) page.copy(from = null) else BuilderPage.Op(page.group, page.field)
            is BuilderPage.Type -> BuilderPage.Op(page.group, page.field)
        }
        go(previous)
        return true
    }

    fun save() {
        val current = _state.value
        if (current.busy) return
        // A new channel never goes without a group and a segment.
        val place = placement.state.value
        if (channelId == 0L) place.problem?.let { return fail(it) }
        if (lineup.isEmpty) return fail("Add a rule or a show first.")
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val id = if (channelId == 0L) {
                    repository.create(current.name.ifBlank { "New channel" }, lineup, PlaybackOrder.EPISODE_ORDER, 1, place.groupId!!, place.segment!!)
                } else {
                    repository.setLineup(channelId, lineup)
                    channelId
                }
                _state.update { it.copy(savedChannelId = id) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e.message ?: "Couldn't save the lineup.")
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private fun add(group: Int, rule: Rule) {
        lineup = if (group >= lineup.groups.size) {
            lineup.copy(groups = lineup.groups + RuleGroup(listOf(rule)))
        } else {
            lineup.copy(groups = lineup.groups.mapIndexed { i, g -> if (i == group) g.copy(rules = g.rules + rule) else g })
        }
        go(BuilderPage.Main)
        changed()
    }

    private fun fail(message: String) = _state.update { it.copy(error = message) }

    private fun go(page: BuilderPage) = _state.update { it.copy(page = page, options = optionsFor(page), error = null) }

    /** Rebuilds the rows from the lineup and recounts what it matches, off the main thread. */
    private fun changed() {
        val titleOf = catalog::titleOf
        _state.update { state ->
            state.copy(
                groups = lineup.groups.map { group -> GroupRow(group.rules.map { describe(it, titleOf, ::ratingOf) }, group.weight) },
                includes = lineup.includes.map { PickRow(titleOf(it.remoteId) ?: "Not in your library", it.weight) },
                excludes = lineup.excludes.map { PickRow(titleOf(it.remoteId) ?: "Not in your library", it.weight) },
            )
        }
        val snapshot = lineup
        summaryJob?.cancel()
        summaryJob = viewModelScope.launch {
            val reach = withContext(Dispatchers.Default) { LineupReach.of(LineupMatcher(snapshot).match(catalog), libraryMs) }
            _state.update { it.copy(reach = reach) }
        }
    }

    /**
     * The values a page lists, in a fixed order that picking never changes: alphabetical, or
     * ascending for years and rating scores. Picked values stay listed while the list is filtered;
     * a range's end lists only values from its start on.
     */
    private fun optionsFor(page: BuilderPage): List<ValueOption> {
        val (all, query, selected) = when (page) {
            is BuilderPage.Choose -> {
                val start = page.from?.value?.toIntOrNull()
                val values = choices(page.field, page.op).filter { start == null || (it.value.toIntOrNull() ?: Int.MIN_VALUE) >= start }
                Triple(values, page.query, page.selected + listOfNotNull(page.from?.value))
            }
            is BuilderPage.Shows -> Triple(catalog.shows.map { ValueOption(it.remoteId, it.title, it.itemCount) }.alphabetical(), page.query, emptySet())
            else -> return emptyList()
        }
        val needle = query.trim()
        return all.filter { needle.isEmpty() || it.value in selected || it.label.contains(needle, ignoreCase = true) }.take(MAX_OPTIONS)
    }

    private fun choices(field: RuleField, op: RuleOp): List<ValueOption> = when (field) {
        RuleField.MEDIA_TYPE -> Rule.PlayableTypes.map { type ->
            ValueOption(type.name, mediaTypeLabel(type.name), catalog.items.count { it.row.type == type })
        }.filter { it.count > 0 }.alphabetical()
        RuleField.SERIES -> catalog.shows.filter { catalog.isSeries(it.remoteId) }.map { ValueOption(it.remoteId, it.title, it.itemCount) }.alphabetical()
        RuleField.KIND -> catalog.values(field).map { ValueOption(it.value, kindLabel(it.value), it.count) }.alphabetical()
        RuleField.YEAR -> catalog.items.mapNotNull { it.row.year }.groupingBy { it }.eachCount().entries
            .sortedBy { it.key }
            .map { ValueOption(it.key.toString(), it.key.toString(), it.value) }
        // A rating compared by score picks a score, named by the ratings that have it.
        RuleField.OFFICIAL_RATING -> if (op == RuleOp.AT_LEAST || op == RuleOp.AT_MOST || op == RuleOp.BETWEEN) {
            catalog.items.mapNotNull { it.row.ratingScore }.groupingBy { it }.eachCount().entries
                .sortedBy { it.key }
                .map { ValueOption(it.key.toString(), ratingNames[it.key] ?: "Score ${it.key}", it.value) }
        } else {
            catalog.values(field).map { ValueOption(it.value, it.value, it.count) }.alphabetical()
        }
        else -> catalog.values(field).map { ValueOption(it.value, it.value, it.count) }.alphabetical()
    }

    private fun List<ValueOption>.alphabetical() = sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })

    private companion object {
        const val MAX_OPTIONS = 200
    }
}

/** Builds a channel's lineup: rule sets (any set may match), and shows always or never included. */
@Composable
fun ChannelLineupScreen(
    onSaved: (channelId: Long, isNew: Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ChannelLineupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val placement by viewModel.placement.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.savedChannelId) { state.savedChannelId?.let { onSaved(it, state.isNew) } }
    androidx.activity.compose.BackHandler { if (!viewModel.back()) onBack() }
    if (!state.loaded) return
    if (state.locked) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val panes = rememberSaveableStateHolder()
    // The pages above this one: the channel's own page for a lineup, the channel list for a new one.
    val trail = listOf("Settings", "Channels") + listOfNotNull(state.name.takeIf { !state.isNew && it.isNotEmpty() })
    val builder = trail + if (state.isNew) "New channel" else "Lineup"

    val page = state.page
    val pane = @Composable {
        PageFocusScope {
            when (page) {
                BuilderPage.Main -> MainPage(state, placement, viewModel, trail, modifier)
                is BuilderPage.Field -> FieldPage(viewModel, builder, modifier)
                is BuilderPage.Op -> OpPage(page, viewModel, builder, modifier)
                is BuilderPage.Choose -> ChoosePage(page, state, viewModel, builder, modifier)
                is BuilderPage.Type -> TypePage(page, state, viewModel, builder, modifier)
                is BuilderPage.Shows -> ShowsPage(page, state, viewModel, builder, modifier)
            }
        }
    }
    // The pages a step back returns to keep their scroll and focused row; the last steps start fresh.
    val returnKey = when (page) {
        BuilderPage.Main -> "main"
        is BuilderPage.Field -> "field"
        is BuilderPage.Op -> "op-${page.field}"
        else -> null
    }
    if (returnKey != null) panes.SaveableStateProvider(returnKey, pane) else pane()
}

@Composable
private fun MainPage(state: LineupUiState, placement: Placement, viewModel: ChannelLineupViewModel, trail: List<String>, modifier: Modifier) {
    val firstFocus = rememberInitialFocus()
    SettingsPage(
        title = if (state.isNew) "New channel" else "Lineup",
        trail = trail,
        about = if (state.isNew) {
            "Name it, pick its group and segment, then add rules. A show plays when it matches every rule in a set."
        } else {
            "A show plays when it matches every rule in a set. Changes start after the next program."
        },
        aside = { ReachReadout(state.reach) },
        modifier = modifier,
    ) {
        if (state.isNew) {
            item(key = "name") {
                TextRow(
                    title = "Name",
                    value = state.name,
                    summary = state.name.ifBlank { "New channel" },
                    onSave = { name -> viewModel.setName(name); null },
                    modifier = Modifier.focusRequester(firstFocus),
                )
            }
            item(key = "group") {
                GroupChoiceRow(placement.groups, placement.groupId, viewModel.placement::pickGroup, viewModel.placement::newGroup)
            }
            item(key = "segment") {
                SegmentChoiceRow(placement.segments, placement.segment, viewModel.placement::pickSegment, viewModel.placement::newSegment)
            }
        }
        state.groups.forEachIndexed { g, group ->
            groupItems(g, group, viewModel, firstFocus.takeIf { g == 0 && !state.isNew })
        }
        item(key = "add-group") {
            SettingsButtons {
                OsdButton(
                    if (state.groups.isEmpty()) "Add rule" else "Add rule set",
                    onClick = viewModel::addGroup,
                    icon = Icons.Filled.Add,
                    modifier = if (state.groups.isEmpty() && !state.isNew) Modifier.focusRequester(firstFocus) else Modifier,
                )
            }
        }
        picks("Always include", state.includes, include = true, viewModel)
        picks("Never include", state.excludes, include = false, viewModel)
        item(key = "error") { state.error?.let { SettingsNotice(it, isError = true) } }
        item(key = "save") {
            SettingsButtons(Modifier.padding(top = 16.dp)) {
                OsdButton(
                    text = if (state.busy) "Saving…" else if (state.isNew) "Create channel" else "Save lineup",
                    onClick = viewModel::save,
                    icon = Icons.Filled.Check,
                    primary = true,
                )
            }
        }
    }
}

private fun LazyListScope.groupItems(g: Int, group: GroupRow, viewModel: ChannelLineupViewModel, focus: FocusRequester?) {
    section(if (g == 0) "Rule set 1" else "Or rule set ${g + 1}", key = "group-$g")
    group.rules.forEachIndexed { r, text ->
        item(key = "rule-$g-$r") {
            MenuRow(
                title = text,
                icon = Icons.Filled.FilterAlt,
                actions = listOf(MenuAction("Remove rule", Icons.Filled.Delete, danger = true) { viewModel.removeRule(g, r) }),
                modifier = if (focus != null && r == 0) Modifier.focusRequester(focus) else Modifier,
            )
        }
    }
    item(key = "weight-$g") {
        ChoiceRow(
            title = "Weight",
            value = group.weight,
            choices = WeightSteps,
            label = ::weightLabel,
            detail = ::weightHint,
            about = "How often this set's shows play.",
            onSelect = { viewModel.setGroupWeight(g, it) },
            icon = Icons.Filled.Scale,
        )
    }
    item(key = "group-buttons-$g") {
        SettingsButtons {
            OsdButton("Add rule", onClick = { viewModel.addRule(g) }, icon = Icons.Filled.Add)
            OsdButton("Remove rule set", onClick = { viewModel.removeGroup(g) }, icon = Icons.Filled.Delete, danger = true)
        }
    }
}

private fun LazyListScope.picks(label: String, rows: List<PickRow>, include: Boolean, viewModel: ChannelLineupViewModel) {
    section(label, key = "picks-$include")
    rows.forEachIndexed { i, row ->
        item(key = "pick-$include-$i") {
            MenuRow(
                title = row.title,
                summary = if (include) "Weight ${weightLabel(row.weight)}" else null,
                icon = if (include) Icons.Filled.LibraryAdd else Icons.Filled.Block,
                actions = buildList {
                    if (include) {
                        for (weight in WeightSteps) {
                            add(MenuAction("Weight ${weightLabel(weight)}", detail = weightHint(weight), selected = weight == row.weight) { viewModel.setPickWeight(i, weight) })
                        }
                    }
                    add(MenuAction("Remove", Icons.Filled.Delete, danger = true) { viewModel.removePick(include, i) })
                },
            )
        }
    }
    item(key = "add-pick-$include") {
        SettingsButtons {
            OsdButton("Add show", onClick = { viewModel.openShows(include) }, icon = Icons.Filled.Add)
        }
    }
}

@Composable
private fun FieldPage(viewModel: ChannelLineupViewModel, trail: List<String>, modifier: Modifier) {
    val firstFocus = rememberInitialFocus()
    SettingsPage(title = "Add rule", trail = trail, about = "What should the rule look at?", modifier = modifier) {
        BuilderFields.forEachIndexed { i, field ->
            item(key = field.name) {
                LinkRow(
                    title = fieldLabel(field),
                    summary = fieldHint(field),
                    onClick = { viewModel.chooseField(field) },
                    modifier = if (i == 0) Modifier.focusRequester(firstFocus) else Modifier,
                )
            }
        }
    }
}

@Composable
private fun OpPage(page: BuilderPage.Op, viewModel: ChannelLineupViewModel, trail: List<String>, modifier: Modifier) {
    val firstFocus = rememberInitialFocus()
    SettingsPage(title = fieldLabel(page.field), trail = trail + "Add rule", about = "How should it compare?", modifier = modifier) {
        Rule.opsFor(page.field).forEachIndexed { i, op ->
            item(key = op.name) {
                LinkRow(
                    title = "${fieldLabel(page.field)} ${opLabel(page.field, op)}…",
                    onClick = { viewModel.chooseOp(op) },
                    modifier = if (i == 0) Modifier.focusRequester(firstFocus) else Modifier,
                )
            }
        }
    }
}

@Composable
private fun ChoosePage(page: BuilderPage.Choose, state: LineupUiState, viewModel: ChannelLineupViewModel, trail: List<String>, modifier: Modifier) {
    val firstFocus = rememberInitialFocus()
    val single = page.op in SinglePickOps
    val from = page.from
    SettingsPage(
        title = if (from == null) "${fieldLabel(page.field)} ${opLabel(page.field, page.op)}…" else "${fieldLabel(page.field)} ${opLabel(page.field, page.op)} ${from.label} and…",
        trail = trail + "Add rule",
        about = when {
            page.op == RuleOp.BETWEEN && from == null -> "Pick where the range starts."
            page.op == RuleOp.BETWEEN -> "Pick where the range ends."
            single -> "Pick one."
            else -> "Pick one or more, then add the rule."
        },
        modifier = modifier,
    ) {
        item(key = "query") {
            AnstelleTextField(
                value = page.query,
                onValueChange = viewModel::setQuery,
                label = "Filter",
                isLast = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = settingsEdge, vertical = 8.dp),
            )
        }
        if (!single) {
            item(key = "confirm") {
                SettingsButtons {
                    OsdButton(
                        "Add rule (${page.selected.size})",
                        onClick = viewModel::confirmRule,
                        icon = Icons.Filled.Check,
                        primary = true,
                        modifier = Modifier.focusRequester(firstFocus),
                    )
                }
            }
        }
        item(key = "error") { state.error?.let { SettingsNotice(it, isError = true) } }
        if (state.options.isEmpty()) item(key = "none") { SettingsNotice("Nothing in your library has one.") }
        itemsIndexed(state.options, key = { _, option -> "option-${option.value}" }) { i, option ->
            val count = "%,d %s".format(option.count, if (option.count == 1) "item" else "items")
            if (single) {
                OptionRow(
                    title = option.label,
                    summary = count,
                    selected = option.value == from?.value,
                    onClick = { viewModel.toggleValue(option) },
                    modifier = if (i == 0) Modifier.focusRequester(firstFocus) else Modifier,
                )
            } else {
                CheckRow(
                    title = option.label,
                    summary = count,
                    checked = option.value in page.selected,
                    onClick = { viewModel.toggleValue(option) },
                )
            }
        }
    }
}

/** Typing what to look for: keywords, or text a value contains. Everything else is picked from the library. */
@Composable
private fun TypePage(page: BuilderPage.Type, state: LineupUiState, viewModel: ChannelLineupViewModel, trail: List<String>, modifier: Modifier) {
    val firstFocus = rememberInitialFocus()
    SettingsPage(
        title = "${fieldLabel(page.field)} ${opLabel(page.field, page.op)}…",
        trail = trail + "Add rule",
        about = if (page.field == RuleField.KEYWORD) {
            "Words or phrases, separated by commas. Searches titles and tags."
        } else {
            "Matches anything that contains this text."
        },
        modifier = modifier,
    ) {
        item(key = "first") {
            AnstelleTextField(
                value = page.first,
                onValueChange = { viewModel.setTyped(it, page.second) },
                label = if (page.field == RuleField.KEYWORD) "Keywords" else "Text",
                isLast = true,
                onDone = viewModel::confirmRule,
                modifier = Modifier.fillMaxWidth().padding(horizontal = settingsEdge, vertical = 4.dp).focusRequester(firstFocus),
            )
        }
        item(key = "error") { state.error?.let { SettingsNotice(it, isError = true) } }
        item(key = "confirm") {
            SettingsButtons {
                OsdButton("Add rule", onClick = viewModel::confirmRule, icon = Icons.Filled.Check, primary = true)
            }
        }
    }
}

@Composable
private fun ShowsPage(page: BuilderPage.Shows, state: LineupUiState, viewModel: ChannelLineupViewModel, trail: List<String>, modifier: Modifier) {
    val firstFocus = rememberInitialFocus()
    SettingsPage(
        title = if (page.include) "Always include" else "Never include",
        trail = trail,
        about = if (page.include) "Always plays on this channel, whatever the rules say." else "Never plays on this channel.",
        modifier = modifier,
    ) {
        item(key = "query") {
            AnstelleTextField(
                value = page.query,
                onValueChange = viewModel::setQuery,
                label = "Search",
                isLast = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = settingsEdge, vertical = 8.dp).focusRequester(firstFocus),
            )
        }
        if (state.options.isEmpty()) item(key = "none") { SettingsNotice("No matching shows or movies.") }
        itemsIndexed(state.options, key = { _, option -> "show-${option.value}" }) { _, option ->
            OptionRow(
                title = option.label,
                summary = "%,d %s".format(option.count, if (option.count == 1) "item" else "episodes"),
                selected = false,
                onClick = { viewModel.pickShow(option.value) },
            )
        }
    }
}

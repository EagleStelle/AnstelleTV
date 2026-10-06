package tv.anstelle.feature.settings.channels

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Scale
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
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
import tv.anstelle.core.data.ProfileRepository
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
import tv.anstelle.core.theme.components.FocusTile
import tv.anstelle.feature.settings.servers.MessageText
import tv.anstelle.feature.settings.servers.SectionLabel
import tv.anstelle.feature.settings.servers.SettingsPage
import tv.anstelle.feature.settings.servers.rememberInitialFocus
import javax.inject.Inject

/** Where the builder is. Rules are added in three steps: field, operator, values. */
sealed interface BuilderPage {
    data object Main : BuilderPage

    /** Choosing what a new rule tests. A [group] equal to the group count starts a new group. */
    data class Field(val group: Int) : BuilderPage

    data class Op(val group: Int, val field: RuleField) : BuilderPage

    /** Choosing values from a list: genres, networks, shows, types… */
    data class Choose(val group: Int, val field: RuleField, val op: RuleOp, val selected: Set<String> = emptySet(), val query: String = "") : BuilderPage

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
    val summary: String? = null,
    val page: BuilderPage = BuilderPage.Main,
    /** Choices for the Choose and Shows pages, filtered by the query. */
    val options: List<ValueOption> = emptyList(),
    val error: String? = null,
    val busy: Boolean = false,
    val loaded: Boolean = false,
    /** Set once saved: the channel's id. */
    val savedChannelId: Long? = null,
)

@HiltViewModel
class ChannelLineupViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val profiles: ProfileRepository,
    private val repository: ChannelRepository,
) : ViewModel() {
    /** Matches the `channelId` argument of the navigation route; 0 builds a new channel. */
    private val channelId: Long = savedState.get<Long>("channelId") ?: 0L

    private val _state = MutableStateFlow(LineupUiState(isNew = channelId == 0L))
    val state: StateFlow<LineupUiState> = _state.asStateFlow()

    private var catalog = Catalog(emptyList())
    private var lineup = Lineup.EMPTY
    private var summaryJob: Job? = null

    init {
        viewModelScope.launch {
            catalog = repository.catalog()
            if (channelId != 0L) {
                lineup = repository.lineup(channelId)
                val name = repository.observeChannel(channelId).first()?.name.orEmpty()
                _state.update { it.copy(name = name) }
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
        val typed = isNumeric(page.field, op) || page.field == RuleField.KEYWORD || op == RuleOp.CONTAINS
        go(if (typed) BuilderPage.Type(page.group, page.field, op) else BuilderPage.Choose(page.group, page.field, op))
    }

    /** Toggles a value; a rule that takes one value is added at once. */
    fun toggleValue(value: String) {
        val page = _state.value.page as? BuilderPage.Choose ?: return
        if (page.op == RuleOp.EQUALS) {
            add(page.group, Rule(page.field, page.op, listOf(value)))
            return
        }
        val selected = if (value in page.selected) page.selected - value else page.selected + value
        go(page.copy(selected = selected))
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

    fun cycleGroupWeight(group: Int) {
        lineup = lineup.copy(groups = lineup.groups.mapIndexed { i, g -> if (i == group) g.copy(weight = nextWeight(g.weight)) else g })
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

    fun cyclePickWeight(index: Int) {
        lineup = lineup.copy(includes = lineup.includes.mapIndexed { i, p -> if (i == index) p.copy(weight = nextWeight(p.weight)) else p })
        changed()
    }

    /** Steps back one page; false on the main page. */
    fun back(): Boolean {
        val previous = when (val page = _state.value.page) {
            BuilderPage.Main -> return false
            is BuilderPage.Field, is BuilderPage.Shows -> BuilderPage.Main
            is BuilderPage.Op -> BuilderPage.Field(page.group)
            is BuilderPage.Choose -> BuilderPage.Op(page.group, page.field)
            is BuilderPage.Type -> BuilderPage.Op(page.group, page.field)
        }
        go(previous)
        return true
    }

    fun save() {
        val current = _state.value
        if (current.busy) return
        if (lineup.isEmpty) return fail("Add a rule or a show first.")
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val id = if (channelId == 0L) {
                    val profileId = profiles.ensureActiveProfile().id
                    repository.create(profileId, current.name.ifBlank { "New channel" }, lineup, PlaybackOrder.EPISODE_ORDER, 1)
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
                groups = lineup.groups.map { group -> GroupRow(group.rules.map { describe(it, titleOf) }, group.weight) },
                includes = lineup.includes.map { PickRow(titleOf(it.remoteId) ?: "Not in your library", it.weight) },
                excludes = lineup.excludes.map { PickRow(titleOf(it.remoteId) ?: "Not in your library", it.weight) },
            )
        }
        val snapshot = lineup
        summaryJob?.cancel()
        summaryJob = viewModelScope.launch {
            val summary = withContext(Dispatchers.Default) { matchSummary(LineupMatcher(snapshot).match(catalog)) }
            _state.update { it.copy(summary = summary) }
        }
    }

    private fun optionsFor(page: BuilderPage): List<ValueOption> {
        val (all, query, selected) = when (page) {
            is BuilderPage.Choose -> Triple(choices(page.field), page.query, page.selected)
            is BuilderPage.Shows -> Triple(catalog.shows.map { ValueOption(it.remoteId, it.title, it.itemCount) }, page.query, emptySet())
            else -> return emptyList()
        }
        val needle = query.trim()
        val matching = if (needle.isEmpty()) all else all.filter { it.label.contains(needle, ignoreCase = true) }
        // Picked values stay visible while the list is filtered.
        return (all.filter { it.value in selected } + matching.filter { it.value !in selected }).take(MAX_OPTIONS)
    }

    private fun choices(field: RuleField): List<ValueOption> = when (field) {
        RuleField.MEDIA_TYPE -> Rule.PlayableTypes.map { type ->
            ValueOption(type.name, mediaTypeLabel(type.name), catalog.items.count { it.row.type == type })
        }.filter { it.count > 0 }
        RuleField.SERIES -> catalog.shows.filter { catalog.isSeries(it.remoteId) }.map { ValueOption(it.remoteId, it.title, it.itemCount) }
        else -> catalog.values(field).map { ValueOption(it.value, it.value, it.count) }
    }

    private companion object {
        const val MAX_OPTIONS = 200
    }
}

/** Builds a channel's lineup: rule groups (any group may match), and shows always or never included. */
@Composable
fun ChannelLineupScreen(
    onSaved: (channelId: Long, isNew: Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ChannelLineupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.savedChannelId) { state.savedChannelId?.let { onSaved(it, state.isNew) } }
    androidx.activity.compose.BackHandler { if (!viewModel.back()) onBack() }
    if (!state.loaded) return

    when (val page = state.page) {
        BuilderPage.Main -> MainPage(state, viewModel, modifier)
        is BuilderPage.Field -> FieldPage(viewModel, modifier)
        is BuilderPage.Op -> OpPage(page, viewModel, modifier)
        is BuilderPage.Choose -> ChoosePage(page, state, viewModel, modifier)
        is BuilderPage.Type -> TypePage(page, state, viewModel, modifier)
        is BuilderPage.Shows -> ShowsPage(page, state, viewModel, modifier)
    }
}

@Composable
private fun MainPage(state: LineupUiState, viewModel: ChannelLineupViewModel, modifier: Modifier) {
    val firstFocus = rememberInitialFocus()
    SettingsPage(
        title = if (state.isNew) "New channel" else "Lineup",
        subtitle = "An item airs when it matches every rule of any one group. Weights make shows air more or less often.",
        modifier = modifier,
    ) {
        item(key = "summary") { MessageText("Matches: ${state.summary ?: "counting…"}") }
        if (state.isNew) {
            item(key = "name") {
                AnstelleTextField(
                    value = state.name,
                    onValueChange = viewModel::setName,
                    label = "Channel name",
                    isLast = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        // Focus starts on the first rule, or on adding one; not on the name, which would open the keyboard.
        state.groups.forEachIndexed { g, group ->
            groupItems(g, group, viewModel, firstFocus.takeIf { g == 0 })
        }
        item(key = "add-group") {
            FocusTile(
                title = if (state.groups.isEmpty()) "Add a rule" else "Add another group (or)",
                subtitle = if (state.groups.isEmpty()) "Genre, network, year, keyword, show…" else "Items matching this group air too.",
                icon = Icons.Filled.AddCircle,
                onClick = viewModel::addGroup,
                modifier = Modifier.fillMaxWidth().let { if (state.groups.isEmpty()) it.focusRequester(firstFocus) else it },
            )
        }
        picks("Always include", state.includes, include = true, viewModel)
        picks("Never include", state.excludes, include = false, viewModel)
        item(key = "error") { state.error?.let { MessageText(it, isError = true) } }
        item(key = "save") {
            FocusTile(
                title = if (state.busy) "Saving…" else if (state.isNew) "Create channel" else "Save lineup",
                subtitle = if (state.isNew) null else "The airing on now and the next one stay; the rest of the schedule follows the new lineup.",
                icon = Icons.Filled.Check,
                onClick = viewModel::save,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun LazyListScope.groupItems(g: Int, group: GroupRow, viewModel: ChannelLineupViewModel, focus: FocusRequester?) {
    item(key = "group-$g") { SectionLabel(if (g == 0) "Group 1" else "Or group ${g + 1}") }
    group.rules.forEachIndexed { r, text ->
        item(key = "rule-$g-$r") {
            FocusTile(
                title = text,
                subtitle = "Select to remove.",
                icon = Icons.Filled.FilterAlt,
                onClick = { viewModel.removeRule(g, r) },
                modifier = Modifier.fillMaxWidth().let { if (focus != null && r == 0) it.focusRequester(focus) else it },
            )
        }
    }
    item(key = "add-rule-$g") {
        FocusTile(
            title = "Add a rule to this group (and)",
            icon = Icons.Filled.Add,
            onClick = { viewModel.addRule(g) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    item(key = "weight-$g") {
        FocusTile(
            title = "Weight ${weightLabel(group.weight)}",
            subtitle = "How often this group's shows are picked. Select to change.",
            icon = Icons.Filled.Scale,
            onClick = { viewModel.cycleGroupWeight(g) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    item(key = "remove-$g") {
        FocusTile(
            title = "Remove this group",
            icon = Icons.Filled.Delete,
            onClick = { viewModel.removeGroup(g) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun LazyListScope.picks(label: String, rows: List<PickRow>, include: Boolean, viewModel: ChannelLineupViewModel) {
    item(key = "picks-$include") { SectionLabel(label) }
    rows.forEachIndexed { i, row ->
        item(key = "pick-$include-$i") {
            FocusTile(
                title = row.title,
                subtitle = if (include) "Weight ${weightLabel(row.weight)}. Select to change." else "Select to remove.",
                icon = if (include) Icons.Filled.LibraryAdd else Icons.Filled.Block,
                onClick = { if (include) viewModel.cyclePickWeight(i) else viewModel.removePick(include = false, index = i) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (include) {
            item(key = "unpick-$i") {
                FocusTile(
                    title = "Remove ${row.title}",
                    icon = Icons.Filled.RemoveCircleOutline,
                    onClick = { viewModel.removePick(include = true, index = i) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
    item(key = "add-pick-$include") {
        FocusTile(
            title = "Add a show or movie",
            icon = Icons.Filled.Add,
            onClick = { viewModel.openShows(include) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun FieldPage(viewModel: ChannelLineupViewModel, modifier: Modifier) {
    val firstFocus = rememberInitialFocus()
    SettingsPage(title = "Add a rule", subtitle = "What should the rule look at?", modifier = modifier) {
        BuilderFields.forEachIndexed { i, field ->
            item(key = field.name) {
                FocusTile(
                    title = fieldLabel(field),
                    subtitle = fieldHint(field),
                    onClick = { viewModel.chooseField(field) },
                    modifier = Modifier.fillMaxWidth().let { if (i == 0) it.focusRequester(firstFocus) else it },
                )
            }
        }
    }
}

@Composable
private fun OpPage(page: BuilderPage.Op, viewModel: ChannelLineupViewModel, modifier: Modifier) {
    val firstFocus = rememberInitialFocus()
    SettingsPage(title = fieldLabel(page.field), subtitle = "How should it compare?", modifier = modifier) {
        Rule.opsFor(page.field).forEachIndexed { i, op ->
            item(key = op.name) {
                FocusTile(
                    title = "${fieldLabel(page.field)} ${opLabel(page.field, op)}…",
                    onClick = { viewModel.chooseOp(op) },
                    modifier = Modifier.fillMaxWidth().let { if (i == 0) it.focusRequester(firstFocus) else it },
                )
            }
        }
    }
}

@Composable
private fun ChoosePage(page: BuilderPage.Choose, state: LineupUiState, viewModel: ChannelLineupViewModel, modifier: Modifier) {
    val firstFocus = rememberInitialFocus()
    val single = page.op == RuleOp.EQUALS
    SettingsPage(
        title = "${fieldLabel(page.field)} ${opLabel(page.field, page.op)}…",
        subtitle = if (single) "Pick one." else "Pick one or more, then add the rule.",
        modifier = modifier,
    ) {
        item(key = "query") {
            AnstelleTextField(
                value = page.query,
                onValueChange = viewModel::setQuery,
                label = "Filter",
                isLast = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (!single) {
            item(key = "confirm") {
                FocusTile(
                    title = "Add the rule (${page.selected.size} picked)",
                    icon = Icons.Filled.Check,
                    onClick = viewModel::confirmRule,
                    modifier = Modifier.fillMaxWidth().focusRequester(firstFocus),
                )
            }
        }
        item(key = "error") { state.error?.let { MessageText(it, isError = true) } }
        if (state.options.isEmpty()) item(key = "none") { MessageText("Nothing in your library has one.") }
        itemsIndexed(state.options, key = { _, option -> "option-${option.value}" }) { i, option ->
            FocusTile(
                title = option.label,
                subtitle = "%,d %s".format(option.count, if (option.count == 1) "item" else "items"),
                icon = if (option.value in page.selected) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
                onClick = { viewModel.toggleValue(option.value) },
                modifier = Modifier.fillMaxWidth().let { if (single && i == 0) it.focusRequester(firstFocus) else it },
            )
        }
    }
}

@Composable
private fun TypePage(page: BuilderPage.Type, state: LineupUiState, viewModel: ChannelLineupViewModel, modifier: Modifier) {
    val firstFocus = rememberInitialFocus()
    val numeric = isNumeric(page.field, page.op)
    val unit = when (page.field) {
        RuleField.DECADE -> " (like 1980)"
        RuleField.OFFICIAL_RATING -> " (rating score, roughly an age)"
        else -> ""
    }
    SettingsPage(
        title = "${fieldLabel(page.field)} ${opLabel(page.field, page.op)}…",
        subtitle = when {
            page.field == RuleField.KEYWORD -> "Words or phrases, separated by commas. Titles, show titles and tags are searched."
            numeric -> null
            else -> "Matches values that contain this text."
        },
        modifier = modifier,
    ) {
        item(key = "first") {
            AnstelleTextField(
                value = page.first,
                onValueChange = { viewModel.setTyped(it, page.second) },
                label = (if (page.op == RuleOp.BETWEEN) "From" else "Value") + unit,
                keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text,
                isLast = page.op != RuleOp.BETWEEN,
                onDone = viewModel::confirmRule,
                modifier = Modifier.fillMaxWidth().focusRequester(firstFocus),
            )
        }
        if (page.op == RuleOp.BETWEEN) {
            item(key = "second") {
                AnstelleTextField(
                    value = page.second,
                    onValueChange = { viewModel.setTyped(page.first, it) },
                    label = "To$unit",
                    keyboardType = KeyboardType.Number,
                    isLast = true,
                    onDone = viewModel::confirmRule,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item(key = "error") { state.error?.let { MessageText(it, isError = true) } }
        item(key = "confirm") {
            FocusTile(
                title = "Add the rule",
                icon = Icons.Filled.Check,
                onClick = viewModel::confirmRule,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ShowsPage(page: BuilderPage.Shows, state: LineupUiState, viewModel: ChannelLineupViewModel, modifier: Modifier) {
    val firstFocus = rememberInitialFocus()
    SettingsPage(
        title = if (page.include) "Always include" else "Never include",
        subtitle = if (page.include) "The show or movie airs on this channel whatever the rules say." else "The show or movie never airs on this channel.",
        modifier = modifier,
    ) {
        item(key = "query") {
            AnstelleTextField(
                value = page.query,
                onValueChange = viewModel::setQuery,
                label = "Search",
                isLast = true,
                modifier = Modifier.fillMaxWidth().focusRequester(firstFocus),
            )
        }
        if (state.options.isEmpty()) item(key = "none") { MessageText("No matching shows or movies.") }
        itemsIndexed(state.options, key = { _, option -> "show-${option.value}" }) { _, option ->
            FocusTile(
                title = option.label,
                subtitle = "%,d %s".format(option.count, if (option.count == 1) "item" else "episodes"),
                onClick = { viewModel.pickShow(option.value) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

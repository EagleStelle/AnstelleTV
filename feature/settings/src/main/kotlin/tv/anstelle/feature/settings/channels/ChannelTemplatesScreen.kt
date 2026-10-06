package tv.anstelle.feature.settings.channels

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tv.anstelle.core.data.ProfileRepository
import tv.anstelle.core.data.channels.ChannelRepository
import tv.anstelle.core.data.channels.PlannedChannel
import tv.anstelle.core.theme.LocalIsTv
import tv.anstelle.core.theme.components.FocusTile
import tv.anstelle.feature.settings.servers.MessageText
import tv.anstelle.feature.settings.servers.SettingsPage
import javax.inject.Inject

@Immutable
data class TemplateRow(val id: String, val name: String, val details: String)

@Immutable
data class TemplatesUiState(
    val templates: List<TemplateRow> = emptyList(),
    val loading: Boolean = true,
    val busy: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class ChannelTemplatesViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    private val repository: ChannelRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(TemplatesUiState())
    val state: StateFlow<TemplatesUiState> = _state.asStateFlow()

    private var planned: List<PlannedChannel> = emptyList()

    init {
        viewModelScope.launch {
            planned = repository.availableTemplates(profiles.ensureActiveProfile().id)
            publish()
        }
    }

    fun add(templateId: String) = add(planned.filter { it.template.id == templateId })

    fun addAll() = add(planned)

    private fun add(chosen: List<PlannedChannel>) {
        if (chosen.isEmpty() || _state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val ids = repository.addTemplates(profiles.ensureActiveProfile().id, chosen)
            val added = chosen.map { it.template.id }.toSet()
            planned = planned.filter { it.template.id !in added }
            publish("Added ${ids.size} ${if (ids.size == 1) "channel" else "channels"}.")
        }
    }

    private fun publish(message: String? = null) {
        _state.value = TemplatesUiState(
            templates = planned.map { plan ->
                val template = plan.template
                TemplateRow(
                    id = template.id,
                    name = template.name,
                    details = listOf(template.category, matchSummary(plan.match), template.description)
                        .filter { it.isNotBlank() }
                        .joinToString(" · "),
                )
            },
            loading = false,
            message = message,
        )
    }
}

/** Template channels the library has enough content for and the profile doesn't have yet. */
@Composable
fun ChannelTemplatesScreen(
    modifier: Modifier = Modifier,
    viewModel: ChannelTemplatesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val firstFocus = remember { FocusRequester() }
    // The list arrives after matching, so focus waits for it.
    if (LocalIsTv.current) LaunchedEffect(state.loading) { if (!state.loading) runCatching { firstFocus.requestFocus() } }
    SettingsPage(
        title = "Add from templates",
        subtitle = "Each template is a ready-made channel. Only templates your library has enough content for are listed.",
        modifier = modifier,
    ) {
        item(key = "status") {
            MessageText(
                when {
                    state.loading -> "Matching templates with your library…"
                    state.message != null -> state.message!!
                    state.templates.isEmpty() -> "Every template that fits your library is already a channel."
                    else -> "${state.templates.size} templates fit your library."
                },
            )
        }
        if (state.templates.isNotEmpty()) {
            item(key = "all") {
                FocusTile(
                    title = if (state.busy) "Adding…" else "Add all ${state.templates.size}",
                    icon = Icons.Filled.AutoAwesome,
                    onClick = viewModel::addAll,
                    modifier = Modifier.fillMaxWidth().focusRequester(firstFocus),
                )
            }
        }
        items(state.templates, key = { it.id }) { template ->
            FocusTile(
                title = template.name,
                subtitle = template.details,
                icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                onClick = { viewModel.add(template.id) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

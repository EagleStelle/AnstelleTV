package tv.anstelle.feature.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertChart
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.anstelle.core.common.TimeSource
import tv.anstelle.core.data.ProfileRepository
import tv.anstelle.core.data.WebServerController
import tv.anstelle.core.data.backup.Backup
import tv.anstelle.core.data.backup.BackupException
import tv.anstelle.core.data.backup.BackupRepository
import tv.anstelle.core.data.backup.BackupSummary
import tv.anstelle.core.data.backup.NearbyDevices
import tv.anstelle.core.data.backup.Peer
import tv.anstelle.core.data.backup.PeerException
import tv.anstelle.core.data.backup.PeerTransfer
import tv.anstelle.core.data.backup.replaceExplanation
import tv.anstelle.core.data.backup.summary
import tv.anstelle.core.model.ProfileRole
import tv.anstelle.core.theme.components.ConfirmSheet
import tv.anstelle.core.theme.components.EditSheet
import tv.anstelle.core.theme.components.LinkRow
import tv.anstelle.core.theme.components.NoticeSheet
import tv.anstelle.core.theme.components.OsdButton
import tv.anstelle.core.theme.components.PinEntry
import tv.anstelle.core.theme.components.SettingsButtons
import tv.anstelle.core.theme.components.SettingsNotice
import tv.anstelle.core.theme.components.SettingsPage
import tv.anstelle.core.theme.components.StatusLine
import tv.anstelle.core.theme.components.rememberInitialFocus
import tv.anstelle.core.theme.components.rememberSettingsSheets
import tv.anstelle.core.theme.components.section
import tv.anstelle.feature.settings.servers.ago
import javax.inject.Inject

/** Where copying a backup in has got to. */
@Immutable
sealed interface BackupStep {
    data object Idle : BackupStep

    /**
     * [peer] shows a PIN to type on the PIN pad. [attempt] clears the pad after a wrong one, which
     * [problem] names; while [copying], the pad waits.
     */
    data class Pin(
        val peer: Peer,
        val requestId: String,
        val attempt: Int = 0,
        val problem: String? = null,
        val copying: Boolean = false,
    ) : BackupStep

    /** [backup] came in from [from] and waits for the answer to "replace everything?". */
    data class Confirm(val backup: Backup, val from: String) : BackupStep
}

@Immutable
data class BackupUiState(
    /** What a backup of this device holds, and when one was last taken. */
    val summary: BackupSummary = BackupSummary(),
    /** Restoring replaces every profile, the owner's too, so only the owner does it (or a device with no profile yet). */
    val canRestore: Boolean = false,
    /** Other devices serving web control, as they are found. */
    val peers: List<Peer> = emptyList(),
    val nowUtcMs: Long = 0,
    val loaded: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class BackupViewModel @Inject constructor(
    savedState: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val backups: BackupRepository,
    private val transfer: PeerTransfer,
    nearby: NearbyDevices,
    profiles: ProfileRepository,
    private val webServer: WebServerController,
    time: TimeSource,
) : ViewModel() {
    /** Opened before any profile exists, from adding the first server: only copying in makes sense. */
    val firstRun: Boolean = savedState.get<Boolean>("firstRun") ?: false

    private val canRestore = if (firstRun) flowOf(true) else profiles.observeActiveProfile().map { it.role == ProfileRole.OWNER }

    val state: StateFlow<BackupUiState> = combine(
        if (firstRun) flowOf(BackupSummary()) else backups.observeSummary(),
        canRestore,
        // Only someone who may copy a backup in looks for devices to copy from.
        canRestore.flatMapLatest { if (it) nearby.observe() else flowOf(emptyList()) },
    ) { summary, restores, peers -> BackupUiState(summary, restores, peers, time.nowUtcMs(), loaded = true) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BackupUiState())

    private val _step = MutableStateFlow<BackupStep>(BackupStep.Idle)
    val step: StateFlow<BackupStep> = _step.asStateFlow()

    private val _message = MutableStateFlow<Pair<String, Boolean>?>(null)

    /** What is going on, or what went wrong (true). */
    val message: StateFlow<Pair<String, Boolean>?> = _message.asStateFlow()

    /** The name a saved backup gets. */
    fun fileName(): String = backups.fileName()

    fun saveTo(uri: Uri) = act("Saving…") {
        val file = backups.export()
        withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(file.json.toByteArray()) } }
            ?: throw BackupException("That file couldn't be written.")
        _message.value = "Saved." to false
    }

    fun readFrom(uri: Uri) = act("Reading the backup…") {
        val text = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }
            ?: throw BackupException("That file couldn't be read.")
        val backup = backups.parse(text)
        _message.value = null
        _step.value = BackupStep.Confirm(backup, "the file")
    }

    /** Asks [peer] to show a PIN to pair with, then opens the PIN pad. */
    fun ask(peer: Peer) = act("Asking ${peer.name} for a PIN…") {
        val pairing = transfer.ask(peer.address)
        _message.value = null
        // A device typed in by address is named by what it calls itself.
        _step.value = BackupStep.Pin(peer.copy(name = pairing.deviceName), pairing.requestId)
    }

    /** A device by the address typed in; returns why it isn't one, or null. */
    fun askAddress(address: String): String? {
        val normalized = PeerTransfer.normalize(address) ?: return "Type an address like 192.168.1.20 or 192.168.1.20:8765."
        ask(Peer(normalized.removePrefix("http://"), normalized))
        return null
    }

    /** Pairs with the [pin] the device shows and copies its backup in. A wrong PIN clears the pad for another try. */
    fun pair(pin: String) {
        val pairing = _step.value as? BackupStep.Pin ?: return
        if (pairing.copying) return
        _step.value = pairing.copy(problem = null, copying = true)
        viewModelScope.launch {
            _step.value = try {
                BackupStep.Confirm(transfer.fetch(pairing.peer.address, pairing.requestId, pin), pairing.peer.name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: PeerException) {
                if (e.wrongPin) {
                    pairing.copy(attempt = pairing.attempt + 1, problem = e.message)
                } else {
                    _message.value = e.message.orEmpty() to true
                    BackupStep.Idle
                }
            } catch (e: Exception) {
                _message.value = (e.message ?: "That didn't work.") to true
                BackupStep.Idle
            }
        }
    }

    /** Leaves the PIN pad; the PIN on the other device runs out by itself. */
    fun cancelPin() {
        if (_step.value is BackupStep.Pin) _step.value = BackupStep.Idle
    }

    /** The question for [BackupStep.Confirm] is open; it holds what it needs. */
    fun asked() {
        if (_step.value is BackupStep.Confirm) _step.value = BackupStep.Idle
    }

    /** Replaces everything with [backup]; the app then starts over on it (see `RestoreSignal`). */
    fun restore(backup: Backup) = act("Restoring…") { backups.restore(backup) }

    /** The problem in [message] is on a sheet now; it no longer shows on the page. */
    fun problemShown() {
        if (_message.value?.second == true) _message.value = null
    }

    /** Where a browser saves the backup, for a device without a file picker. */
    fun noFilePicker() {
        val base = webServer.status.value.urls.firstOrNull()
        _message.value = when (base) {
            null -> "This device can't save files. Turn on Web control, then save the backup from a browser."
            else -> "This device can't save files. In a browser paired with Web control, open $base/backup to save it."
        } to false
    }

    private fun act(status: String, action: suspend () -> Unit) {
        if (_message.value?.let { (text, problem) -> !problem && text.endsWith("…") } == true) return
        _message.value = status to false
        viewModelScope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = (e.message ?: "That didn't work.") to true
            }
        }
    }
}

/**
 * Backup: saves everything this device holds to a file, restores one, or copies it in from a
 * nearby device. The guidance shows what a backup holds and when one was last taken; copying
 * types the other device's PIN on the PIN pad, and restoring asks first, says what comes in,
 * then starts the app over on it.
 */
@Composable
fun BackupScreen(
    modifier: Modifier = Modifier,
    viewModel: BackupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val step by viewModel.step.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    // The other device's PIN goes on the PIN pad, like a profile's.
    (step as? BackupStep.Pin)?.let { pin ->
        PinEntry(
            title = "Copy from ${pin.peer.name}",
            subtitle = "Enter the PIN it shows.",
            message = if (pin.copying) "Copying…" else pin.problem,
            isError = !pin.copying && pin.problem != null,
            enabled = !pin.copying,
            attempt = pin.attempt,
            length = PIN_LENGTH,
            onPin = viewModel::pair,
            onCancel = viewModel::cancelPin,
            modifier = modifier,
        )
        return
    }

    val firstFocus = rememberInitialFocus(ready = state.loaded)
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let(viewModel::saveTo) }
    val opener = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::readFrom) }
    val context = LocalContext.current
    val canSaveFiles = remember { context.hasFilePicker(Intent.ACTION_CREATE_DOCUMENT) }
    // A device that can't open a file never offers restoring one; copying from a device does instead.
    val canOpenFiles = remember { context.hasFilePicker(Intent.ACTION_OPEN_DOCUMENT) }
    fun save() = try {
        if (canSaveFiles) saver.launch(viewModel.fileName()) else viewModel.noFilePicker()
    } catch (e: ActivityNotFoundException) {
        viewModel.noFilePicker()
    }
    fun open() = try {
        opener.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
    } catch (e: ActivityNotFoundException) {
        viewModel.noFilePicker()
    }

    // The address and the question open over the page.
    val sheets = rememberSettingsSheets()
    val enterAddress = remember { FocusRequester() }
    SettingsPage(
        sheets = sheets,
        title = "Backup",
        trail = if (viewModel.firstRun) listOf("Add server") else listOf("Settings"),
        about = if (viewModel.firstRun) {
            "Take over another AnstelleTV's channels, profiles, themes and settings, from the device itself or a saved backup. " +
                "A device to copy from needs its Web control on."
        } else {
            "A backup holds the channels, profiles, themes, settings and dashboard history, and restoring one replaces " +
                "everything here. A device to copy from needs its Web control on." +
                if (canOpenFiles) "" else " To restore a saved backup, open Backup in a browser paired with Web control."
        },
        aside = if (viewModel.firstRun) null else ({ BackupReadout(state.summary, state.nowUtcMs) }),
        modifier = modifier,
    ) {
        // A problem asks for an answer, so it opens over the page (see below); only progress stays in the list.
        item(key = "message") { message?.takeUnless { it.second }?.let { (text, _) -> SettingsNotice(text, isError = false) } }
        // The file verbs side by side (user decision): saving one, and restoring one where this device can open files.
        val restoresFiles = state.canRestore && canOpenFiles
        if (!viewModel.firstRun || restoresFiles) {
            item(key = "files") {
                SettingsButtons {
                    if (!viewModel.firstRun) {
                        OsdButton("Save backup", onClick = ::save, icon = Icons.Filled.FileDownload, primary = true, modifier = Modifier.focusRequester(firstFocus))
                    }
                    if (restoresFiles) OsdButton("Restore from file", onClick = ::open, icon = Icons.Filled.FileUpload, danger = true)
                }
            }
        }
        if (state.canRestore) {
            section("Nearby devices")
            val peers = state.peers
            items(peers, key = { "peer-${it.address}" }) { peer ->
                LinkRow(
                    title = peer.name,
                    summary = peer.address.removePrefix("http://"),
                    icon = Icons.Filled.Devices,
                    onClick = { viewModel.ask(peer) },
                    modifier = if (viewModel.firstRun && peer == peers.first()) Modifier.focusRequester(firstFocus) else Modifier,
                )
            }
            item(key = "address") {
                SettingsButtons {
                    OsdButton(
                        "Enter address",
                        onClick = {
                            sheets.show(
                                EditSheet(
                                    title = "Device address",
                                    about = "The address the other device's Web control shows, like 192.168.1.20:8765.",
                                    label = "Address",
                                    value = "",
                                    keyboardType = KeyboardType.Uri,
                                    filter = { it.take(64) },
                                    onSave = viewModel::askAddress,
                                ),
                                from = enterAddress,
                            )
                        },
                        icon = Icons.Filled.Link,
                        modifier = Modifier.focusRequester(enterAddress).let {
                            if (viewModel.firstRun && peers.isEmpty()) it.focusRequester(firstFocus) else it
                        },
                    )
                }
            }
        }
    }

    LaunchedEffect(message) {
        val (text, problem) = message ?: return@LaunchedEffect
        if (!problem) return@LaunchedEffect
        sheets.show(NoticeSheet(title = "That didn't work", about = text))
        viewModel.problemShown()
    }

    LaunchedEffect(step) {
        val current = step as? BackupStep.Confirm ?: return@LaunchedEffect
        sheets.show(
            ConfirmSheet(
                title = "Replace everything here?",
                about = current.backup.summary().replaceExplanation(current.from),
                confirm = "Replace",
                icon = Icons.Filled.SettingsBackupRestore,
                onConfirm = { viewModel.restore(current.backup) },
            ),
        )
        viewModel.asked()
    }
}

/**
 * What a backup of this device holds, the way the other settings readouts read: when one was last
 * taken as the status line, profiles, channels and custom themes as figures, then its servers,
 * groups and the dashboards' days.
 */
@Composable
internal fun BackupReadout(summary: BackupSummary, nowUtcMs: Long) {
    val saved = summary.savedUtcMs
    ReadoutFrame {
        StatusLine(onAir = saved != null, label = if (saved != null) "Saved" else "Not saved yet", text = saved?.let { ago(it, nowUtcMs) }.orEmpty())
        ReadoutFigures {
            ReadoutFigure(summary.channels, if (summary.channels == 1) "channel" else "channels", lead = true)
            ReadoutFigure(summary.profiles, if (summary.profiles == 1) "profile" else "profiles")
            ReadoutFigure(summary.themes, if (summary.themes == 1) "custom theme" else "custom themes")
        }
        ReadoutRule()
        ReadoutFacts {
            ReadoutFact(Icons.Filled.Dns, "${summary.servers}", if (summary.servers == 1) "server" else "servers")
            ReadoutFact(Icons.Filled.Folder, "${summary.groups}", if (summary.groups == 1) "group" else "groups")
            ReadoutFact(Icons.Filled.InsertChart, "${summary.historyDays}", if (summary.historyDays == 1) "day of history" else "days of history")
        }
    }
}

/** The home's line under Backup: when one was last taken. */
internal fun backupSummary(summary: BackupSummary, nowUtcMs: Long): String =
    summary.savedUtcMs?.let { "Saved ${ago(it, nowUtcMs)}" } ?: "Not saved yet"

/** Whether a real file picker answers [action]: Google TV only has a stub that says no app can. */
private fun Context.hasFilePicker(action: String): Boolean {
    val intent = Intent(action).addCategory(Intent.CATEGORY_OPENABLE).setType(if (action == Intent.ACTION_CREATE_DOCUMENT) "application/json" else "*/*")
    val found = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) ?: return false
    return !found.activityInfo.packageName.endsWith(".frameworkpackagestubs")
}

private const val PIN_LENGTH = 6

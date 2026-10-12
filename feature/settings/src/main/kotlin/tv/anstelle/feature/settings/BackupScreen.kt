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
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Tv
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
import tv.anstelle.core.data.backup.summary
import tv.anstelle.core.model.ProfileRole
import tv.anstelle.core.theme.components.ConfirmSheet
import tv.anstelle.core.theme.components.EditSheet
import tv.anstelle.core.theme.components.LinkRow
import tv.anstelle.core.theme.components.OsdButton
import tv.anstelle.core.theme.components.SettingsButtons
import tv.anstelle.core.theme.components.SettingsNotice
import tv.anstelle.core.theme.components.SettingsPage
import tv.anstelle.core.theme.components.TextRow
import tv.anstelle.core.theme.components.foot
import tv.anstelle.core.theme.components.rememberInitialFocus
import tv.anstelle.core.theme.components.rememberSettingsSheets
import tv.anstelle.core.theme.components.section
import javax.inject.Inject

/** Where copying a backup in has got to. */
@Immutable
sealed interface BackupStep {
    data object Idle : BackupStep

    /** [peer] shows a PIN to type in; [problem] says why the last one didn't do. */
    data class Pin(val peer: Peer, val requestId: String, val problem: String? = null) : BackupStep

    /** [backup] came in from [from] and waits for the answer to "replace everything?". */
    data class Confirm(val backup: Backup, val from: String) : BackupStep
}

@Immutable
data class BackupUiState(
    /** What a backup of this device holds. */
    val summary: BackupSummary = BackupSummary(),
    /** Restoring replaces every profile, the owner's too, so only the owner does it (or a device with no profile yet). */
    val canRestore: Boolean = false,
    /** Other devices serving web control, as they are found. */
    val peers: List<Peer> = emptyList(),
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
) : ViewModel() {
    /** Opened before any profile exists, from adding the first server: only copying in makes sense. */
    val firstRun: Boolean = savedState.get<Boolean>("firstRun") ?: false

    private val canRestore = if (firstRun) flowOf(true) else profiles.observeActiveProfile().map { it.role == ProfileRole.OWNER }

    val state: StateFlow<BackupUiState> = combine(
        if (firstRun) flowOf(BackupSummary()) else backups.observeSummary(),
        canRestore,
        // Only someone who may copy a backup in looks for devices to copy from.
        canRestore.flatMapLatest { if (it) nearby.observe() else flowOf(emptyList()) },
    ) { summary, restores, peers -> BackupUiState(summary, restores, peers, loaded = true) }
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

    /** The pairing waiting for its PIN; it outlives its sheet. */
    private var asked: BackupStep.Pin? = null

    /** Asks [peer] to show a PIN to pair with. */
    fun ask(peer: Peer) = act("Asking ${peer.name} for a PIN…") {
        val pairing = transfer.ask(peer.address)
        _message.value = null
        // A device typed in by address is named by what it calls itself.
        _step.value = BackupStep.Pin(peer.copy(name = pairing.deviceName), pairing.requestId).also { asked = it }
    }

    /** A device by the address typed in; returns why it isn't one, or null. */
    fun askAddress(address: String): String? {
        val normalized = PeerTransfer.normalize(address) ?: return "Type an address like 192.168.1.20 or 192.168.1.20:8765."
        ask(Peer(normalized.removePrefix("http://"), normalized))
        return null
    }

    /** Pairs with the [pin] the device shows and copies its backup in. */
    fun pair(pin: String) {
        val pairing = asked ?: return
        asked = null
        act("Copying from ${pairing.peer.name}…") {
            try {
                val backup = transfer.fetch(pairing.peer.address, pairing.requestId, pin)
                _message.value = null
                _step.value = BackupStep.Confirm(backup, pairing.peer.name)
            } catch (e: PeerException) {
                if (!e.wrongPin) throw e
                // The same PIN is still up on the other device: ask for it again.
                _message.value = null
                _step.value = pairing.copy(problem = e.message).also { asked = it }
            }
        }
    }

    /** The sheet for [step] is open; it holds what it needs. */
    fun shown() {
        _step.value = BackupStep.Idle
    }

    /** Replaces everything with [backup]; the app then starts over on it (see `RestoreSignal`). */
    fun restore(backup: Backup) = act("Restoring…") { backups.restore(backup) }

    /** Where a browser saves the backup, for a device without a file picker. */
    fun noFilePicker() {
        val base = webServer.status.value.urls.firstOrNull()
        _message.value = when (base) {
            null -> "This device can't save files. Turn on Web control, then save the backup from a browser."
            else -> "This device can't save files. In a browser paired with Web control, open $base/api/backup/export to save it."
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
 * Backup: saves everything this device holds to a file, restores one, or copies it in from another
 * device on the network. The guidance shows what a backup holds; restoring asks first and says
 * what comes in, and the app then starts over on it.
 */
@Composable
fun BackupScreen(
    modifier: Modifier = Modifier,
    viewModel: BackupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val step by viewModel.step.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
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

    // The steps open their sheets over the page: the PIN, then the question.
    val sheets = rememberSettingsSheets()
    SettingsPage(
        sheets = sheets,
        title = "Backup",
        trail = if (viewModel.firstRun) listOf("Add server") else listOf("Settings"),
        about = if (viewModel.firstRun) {
            "Copy everything from another AnstelleTV: its channels, profiles, themes and settings. Its Web control must be on."
        } else {
            "A backup holds the channels, profiles, themes, settings and dashboard history. Restoring one replaces " +
                "everything here, and servers this device isn't signed in to need signing in again."
        },
        aside = if (viewModel.firstRun) null else ({ BackupReadout(state.summary) }),
        modifier = modifier,
    ) {
        item(key = "message") { message?.let { (text, problem) -> SettingsNotice(text, isError = problem) } }
        if (!viewModel.firstRun) {
            item(key = "save") {
                SettingsButtons {
                    OsdButton("Save backup", onClick = ::save, icon = Icons.Filled.FileDownload, primary = true, modifier = Modifier.focusRequester(firstFocus))
                }
            }
        }
        if (state.canRestore) {
            section("Copy from a device")
            val peers = state.peers
            items(peers, key = { "peer-${it.address}" }) { peer ->
                LinkRow(
                    title = peer.name,
                    summary = peer.address.removePrefix("http://"),
                    icon = Icons.Filled.Tv,
                    onClick = { viewModel.ask(peer) },
                    modifier = if (viewModel.firstRun && peer == peers.first()) Modifier.focusRequester(firstFocus) else Modifier,
                )
            }
            if (peers.isEmpty()) {
                item(key = "looking") { SettingsNotice("Looking for devices with Web control on…") }
            }
            item(key = "address") {
                TextRow(
                    title = "Other device",
                    value = "",
                    summary = "By its address",
                    label = "Address",
                    about = "The address its Web control shows, like 192.168.1.20:8765.",
                    keyboardType = KeyboardType.Uri,
                    icon = Icons.Filled.Devices,
                    onSave = viewModel::askAddress,
                    modifier = if (viewModel.firstRun && peers.isEmpty()) Modifier.focusRequester(firstFocus) else Modifier,
                )
            }
            if (canOpenFiles) foot { OsdButton("Restore from file", onClick = ::open, icon = Icons.Filled.Restore, danger = true) }
        }
    }

    LaunchedEffect(step) {
        when (val current = step) {
            is BackupStep.Pin -> {
                sheets.show(
                    EditSheet(
                        title = "PIN",
                        about = current.problem ?: "Enter the PIN ${current.peer.name} shows now.",
                        label = "PIN",
                        value = "",
                        keyboardType = KeyboardType.NumberPassword,
                        filter = { text -> text.filter(Char::isDigit).take(PIN_LENGTH) },
                        onSave = { pin -> if (pin.length == PIN_LENGTH) null.also { viewModel.pair(pin) } else "The PIN has $PIN_LENGTH digits." },
                    ),
                )
                viewModel.shown()
            }
            is BackupStep.Confirm -> {
                sheets.show(
                    ConfirmSheet(
                        title = "Replace everything here?",
                        about = "${brings(current.backup.summary())} from ${current.from} replace what this device holds. " +
                            "Servers this device isn't signed in to need signing in again.",
                        confirm = "Replace",
                        icon = Icons.Filled.Restore,
                        onConfirm = { viewModel.restore(current.backup) },
                    ),
                )
                viewModel.shown()
            }
            BackupStep.Idle -> Unit
        }
    }
}

/** What a backup of this device holds: profiles, channels and themes, then its servers, groups and history. */
@Composable
internal fun BackupReadout(summary: BackupSummary) {
    ReadoutFrame {
        ReadoutFigures {
            ReadoutFigure(summary.profiles, if (summary.profiles == 1) "profile" else "profiles", lead = true)
            ReadoutFigure(summary.channels, if (summary.channels == 1) "channel" else "channels")
            ReadoutFigure(summary.themes, if (summary.themes == 1) "custom theme" else "custom themes")
        }
        ReadoutRule()
        ReadoutFacts {
            ReadoutFact(Icons.Filled.Dns, "${summary.servers}", if (summary.servers == 1) "server" else "servers")
            ReadoutFact(Icons.Filled.Folder, "${summary.groups}", if (summary.groups == 1) "group" else "groups")
            ReadoutFact(Icons.Filled.History, "${summary.historyDays}", if (summary.historyDays == 1) "day of history" else "days of history")
        }
    }
}

/** Whether a real file picker answers [action]: Google TV only has a stub that says no app can. */
private fun Context.hasFilePicker(action: String): Boolean {
    val intent = Intent(action).addCategory(Intent.CATEGORY_OPENABLE).setType(if (action == Intent.ACTION_CREATE_DOCUMENT) "application/json" else "*/*")
    val found = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) ?: return false
    return !found.activityInfo.packageName.endsWith(".frameworkpackagestubs")
}

/** "1 profile", "12 profiles". */
internal fun counted(count: Int, noun: String) = if (count == 1) "1 $noun" else "%,d ${noun}s".format(count)

/** What a backup brings, leaving out what it has none of: "1 profile, 94 channels and 2 custom themes". */
private fun brings(summary: BackupSummary): String {
    val parts = buildList {
        if (summary.profiles > 0) add(counted(summary.profiles, "profile"))
        if (summary.channels > 0) add(counted(summary.channels, "channel"))
        if (summary.themes > 0) add(counted(summary.themes, "custom theme"))
        if (summary.historyDays > 0) add(if (summary.historyDays == 1) "1 day of history" else "${summary.historyDays} days of history")
    }
    return if (parts.size < 2) parts.joinToString() else parts.dropLast(1).joinToString(", ") + " and " + parts.last()
}

private const val PIN_LENGTH = 6

package de.nfcmanager.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.nfcmanager.app.ui.emulation.EmulationScreen
import de.nfcmanager.app.ui.emulation.EmulationViewModel
import de.nfcmanager.app.ui.library.LibraryScreen
import de.nfcmanager.app.ui.library.LibraryViewModel
import de.nfcmanager.app.ui.library.TagDetailScreen
import de.nfcmanager.app.ui.scan.ScanScreen
import de.nfcmanager.app.ui.scan.ScanViewModel

@Composable
fun NfcManagerRoot() {
    val appVm: AppViewModel = viewModel()
    val scanVm: ScanViewModel = viewModel()
    val libVm: LibraryViewModel = viewModel()
    val emuVm: EmulationViewModel = viewModel()

    val tab by appVm.tab.collectAsStateWithLifecycle()
    val caps by appVm.capabilities.collectAsStateWithLifecycle()
    val themeMode by appVm.settings.themeMode.collectAsStateWithLifecycle()
    val dynamic by appVm.settings.dynamicColor.collectAsStateWithLifecycle()
    val scanState by scanVm.ui.collectAsStateWithLifecycle()
    val tags by libVm.tags.collectAsStateWithLifecycle()
    val total by libVm.total.collectAsStateWithLifecycle()
    val query by libVm.query.collectAsStateWithLifecycle()
    val sort by libVm.sort.collectAsStateWithLifecycle()
    val openId by libVm.openId.collectAsStateWithLifecycle()
    val detail by libVm.detail.collectAsStateWithLifecycle()
    val emuStatus by emuVm.status.collectAsStateWithLifecycle()
    val candidates by emuVm.candidates.collectAsStateWithLifecycle()
    val log by emuVm.log.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    var showSettings by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        appVm.messages.collect { snackbar.showSnackbar(it) }
    }
    val inDetail = tab == Tab.LIBRARY && openId != null
    BackHandler(enabled = inDetail) { libVm.open(null) }

    val exportOne = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val id = libVm.openId.value
        if (uri != null && id != null) libVm.exportTo(uri, setOf(id))
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            inDetail -> detail?.entity?.name ?: "Eintrag"
                            tab == Tab.SCAN -> "NFC-Manager"
                            else -> tab.label
                        },
                    )
                },
                navigationIcon = {
                    if (inDetail) {
                        IconButton(onClick = { libVm.open(null) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") }
                    }
                },
                actions = {
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, "Einstellungen") }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.values().forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { appVm.selectTab(t) },
                        icon = { Icon(painterResource(t.icon), contentDescription = null) },
                        label = { Text(t.label, maxLines = 1) },
                    )
                }
            }
        },
    ) { padding ->
        when (tab) {
            Tab.SCAN -> ScanScreen(
                caps = caps,
                state = scanState,
                contentPadding = padding,
                onSave = scanVm::save,
                onCancelWrite = scanVm::cancelWrite,
                onDismissWriteMessage = scanVm::clearWriteMessage,
                onClear = scanVm::clearResult,
            )
            Tab.LIBRARY -> if (openId == null) {
                LibraryScreen(
                    tags = tags,
                    total = total,
                    query = query,
                    sort = sort,
                    contentPadding = padding,
                    activeTagId = if (emuStatus.running) emuStatus.activeTagId else -1L,
                    onQuery = { libVm.query.value = it },
                    onSort = { libVm.sort.value = it },
                    onOpen = libVm::open,
                    onCreate = libVm::createEntry,
                    onExport = { uri -> libVm.exportTo(uri, null) },
                    onImport = libVm::importFrom,
                )
            } else {
                val tag = detail
                TagDetailScreen(
                    tag = tag,
                    contentPadding = padding,
                    isActive = tag != null && emuStatus.running && emuStatus.activeTagId == tag.id,
                    onEmulate = {
                        if (tag != null && libVm.activateEmulation(tag)) {
                            emuVm.refresh()
                            appVm.selectTab(Tab.EMULATE)
                        }
                    },
                    onWrite = {
                        val message = tag?.entity?.ndefMessage
                        if (tag != null && message != null && message.isNotEmpty()) {
                            scanVm.armWrite(tag.entity.name, message)
                            appVm.selectTab(Tab.SCAN)
                        } else {
                            appVm.messages.tryEmit("Kein NDEF-Inhalt zum Schreiben vorhanden")
                        }
                    },
                    onUpdateMeta = { name, symbol, color, note -> tag?.let { libVm.updateMeta(it.id, name, symbol, color, note) } },
                    onReplaceMessage = { message -> tag?.let { libVm.replaceMessage(it.id, message) } },
                    onExportOne = { exportOne.launch("tag-${tag?.entity?.name ?: "export"}.json".replace('/', '-')) },
                    onDelete = { tag?.let { libVm.delete(it.id) } },
                )
            }
            Tab.EMULATE -> EmulationScreen(
                caps = caps,
                status = emuStatus,
                candidates = candidates,
                log = log,
                contentPadding = padding,
                onRefresh = emuVm::refresh,
                onActivate = emuVm::activate,
                onDeactivate = emuVm::deactivate,
                onClearLog = emuVm::clearLog,
            )
        }
    }

    if (showSettings) {
        SettingsDialog(
            themeMode = themeMode,
            dynamicColor = dynamic,
            onThemeMode = appVm.settings::setThemeMode,
            onDynamicColor = appVm.settings::setDynamicColor,
            onDismiss = { showSettings = false },
        )
    }
}

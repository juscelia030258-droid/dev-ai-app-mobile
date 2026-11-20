package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Api
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.outlined.Api
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.ui.MainViewModel
import com.example.ui.components.StatusPill
import com.example.ui.screens.AgentsScreen
import com.example.ui.screens.ApiAndQoderScreen
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.ModelsScreen
import com.example.ui.screens.MultiAgentScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.ExtensionsScreen
import com.example.ui.screens.CalibrationScreen
import com.example.ui.theme.LocalDevAiTheme
import com.example.ui.theme.MultiAgentAmber
import com.example.ui.theme.StatusOnline
import kotlinx.serialization.Serializable

@Serializable data object DashboardRoute
@Serializable data object AgentsRoute
@Serializable data object ModelsRoute
@Serializable data object MultiAgentRoute
@Serializable data object ApiQoderRoute
@Serializable data object SettingsRoute
@Serializable data object ExtensionsRoute
@Serializable data object CalibrationRoute

private data class NavItem(
    val route: Any,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

private val NAV_ITEMS = listOf(
    NavItem(DashboardRoute, "Painel", Icons.Filled.Dashboard, Icons.Outlined.Dashboard, "nav_dashboard"),
    NavItem(AgentsRoute, "Agentes", Icons.Filled.Psychology, Icons.Outlined.Psychology, "nav_agents"),
    NavItem(ModelsRoute, "Modelos", Icons.Filled.Memory, Icons.Outlined.Memory, "nav_models"),
    NavItem(MultiAgentRoute, "Multi", Icons.Filled.Hub, Icons.Outlined.Hub, "nav_multiagent"),
    NavItem(ApiQoderRoute, "API", Icons.Filled.Api, Icons.Outlined.Api, "nav_api"),
    NavItem(ExtensionsRoute, "Ext", Icons.Filled.Extension, Icons.Outlined.Extension, "nav_extensions"),
    NavItem(CalibrationRoute, "Lab", Icons.Filled.School, Icons.Outlined.School, "nav_calibration"),
    NavItem(SettingsRoute, "Config", Icons.Filled.Settings, Icons.Outlined.Settings, "nav_settings")
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: MainViewModel = viewModel()
            val settings by viewModel.settings.collectAsStateWithLifecycle()

            LocalDevAiTheme(darkTheme = settings.darkThemeEnabled) {
                LocalDevAiRoot(viewModel = viewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalDevAiRoot(viewModel: MainViewModel) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val snackbarHostState = remember { SnackbarHostState() }

    val memoryState by viewModel.memoryState.collectAsStateWithLifecycle()
    val networkState by viewModel.networkState.collectAsStateWithLifecycle()
    val serverStatus by viewModel.serverStatus.collectAsStateWithLifecycle()
    val agentStates by viewModel.agentStates.collectAsStateWithLifecycle()
    val queueDepth by viewModel.queueDepth.collectAsStateWithLifecycle()
    val downloadState by viewModel.downloadState.collectAsStateWithLifecycle()
    val multiAgentLiveState by viewModel.multiAgentLiveState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val models by viewModel.models.collectAsStateWithLifecycle()
    val agentConfigs by viewModel.agentConfigs.collectAsStateWithLifecycle()
    val agentHistory by viewModel.agentHistory.collectAsStateWithLifecycle()
    val multiAgentRuns by viewModel.multiAgentRuns.collectAsStateWithLifecycle()
    val apiKeys by viewModel.apiKeys.collectAsStateWithLifecycle()
    val connectedDevices by viewModel.connectedDevices.collectAsStateWithLifecycle()
    val apiLogs by viewModel.apiLogs.collectAsStateWithLifecycle()
    val primaryKeyPlaintext by viewModel.primaryKeyPlaintext.collectAsStateWithLifecycle()
    val newlyCreatedDeviceKey by viewModel.newlyCreatedDeviceKey.collectAsStateWithLifecycle()
    val agentConsoles by viewModel.agentConsoles.collectAsStateWithLifecycle()
    val multiAgentImageUri by viewModel.multiAgentImageUri.collectAsStateWithLifecycle()
    val diagnosticsResults by viewModel.diagnosticsResults.collectAsStateWithLifecycle()
    val isRunningDiagnostics by viewModel.isRunningDiagnostics.collectAsStateWithLifecycle()
    val bannerMessage by viewModel.uiBannerMessage.collectAsStateWithLifecycle()
    val calibrationState by viewModel.calibrationState.collectAsStateWithLifecycle()

    LaunchedEffect(bannerMessage) {
        val msg = bannerMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearBanner()
        }
    }

    val navigateTo: (Any) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 700.dp

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            topBar = {
                Column {
                    TopAppBar(
                        title = {
                            Text(
                                text = "LOCAL DEV AI",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        actions = {
                            Box(modifier = Modifier.padding(end = 16.dp)) {
                                StatusPill(
                                    label = if (serverStatus.isRunning) ":${serverStatus.port} Online" else "Offline",
                                    color = if (serverStatus.isRunning) StatusOnline else MultiAgentAmber
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background
                        )
                    )
                    HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                }
            },
            bottomBar = {
                if (!useRail) {
                    Column {
                        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surface,
                            tonalElevation = 0.dp
                        ) {
                            val navItemColors = NavigationBarItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                selectedIconColor = MaterialTheme.colorScheme.onSurface,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            NAV_ITEMS.forEach { item ->
                                val selected = currentDestination?.hasRoute(item.route::class) == true
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { navigateTo(item.route) },
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                            contentDescription = item.label
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = item.label,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    },
                                    colors = navItemColors,
                                    modifier = Modifier.testTag(item.testTag)
                                )
                            }
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useRail) {
                    NavigationRail(
                        modifier = Modifier.fillMaxHeight(),
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        NAV_ITEMS.forEach { item ->
                            val selected = currentDestination?.hasRoute(item.route::class) == true
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateTo(item.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                        contentDescription = item.label
                                    )
                                },
                                label = { Text(item.label) },
                                modifier = Modifier.testTag(item.testTag)
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    NavHost(
                        navController = navController,
                        startDestination = DashboardRoute,
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth()
                            .widthIn(max = 760.dp)
                    ) {
                        composable<DashboardRoute> {
                            DashboardScreen(
                                memoryState = memoryState,
                                networkState = networkState,
                                serverStatus = serverStatus,
                                agentStates = agentStates,
                                agentConfigs = agentConfigs,
                                models = models,
                                multiAgentState = multiAgentLiveState,
                                primaryKeyPlaintext = primaryKeyPlaintext,
                                queueDepth = queueDepth,
                                onStartServer = viewModel::startApiServer,
                                onStopServer = viewModel::stopApiServer,
                                onUnloadModels = viewModel::unloadAllModels,
                                onNavigateToAgents = { navigateTo(AgentsRoute) },
                                onNavigateToModels = { navigateTo(ModelsRoute) },
                                onNavigateToApi = { navigateTo(ApiQoderRoute) },
                                onNavigateToMultiAgent = { navigateTo(MultiAgentRoute) },
                                onBannerMessage = viewModel::showBanner
                            )
                        }
                    composable<AgentsRoute> {
                        AgentsScreen(
                            agentStates = agentStates,
                            agentConfigs = agentConfigs,
                            models = models,
                            consoles = agentConsoles,
                            history = agentHistory,
                            onToggleEnabled = viewModel::toggleAgentEnabled,
                            onAssignModel = viewModel::assignModelToAgent,
                            onUpdateConfig = viewModel::updateAgentConfig,
                            onSelectVisionImage = viewModel::setVisionImageUri,
                            onRunPrompt = viewModel::runAgentConsolePrompt,
                            onStopPrompt = viewModel::stopAgentConsole,
                            onBannerMessage = viewModel::showBanner
                        )
                    }
                    composable<ModelsRoute> {
                        ModelsScreen(
                            models = models,
                            agentConfigs = agentConfigs,
                            presets = viewModel.recommendedPresets,
                            downloadState = downloadState,
                            memoryState = memoryState,
                            onImportLocalModel = viewModel::importLocalModel,
                            onAttachMmproj = viewModel::attachMmproj,
                            onDownloadPreset = viewModel::downloadPreset,
                            onDownloadCustomUrl = viewModel::downloadCustomUrl,
                            onCancelDownload = viewModel::cancelModelDownload,
                            onAssignToAgent = viewModel::assignModelToAgent,
                            onLoadModel = viewModel::preloadModel,
                            onUnloadAll = viewModel::unloadAllModels,
                            onDeleteModel = viewModel::deleteModel
                        )
                    }
                    composable<MultiAgentRoute> {
                        MultiAgentScreen(
                            liveState = multiAgentLiveState,
                            selectedImageUri = multiAgentImageUri,
                            recentRuns = multiAgentRuns,
                            onSelectImageUri = viewModel::setMultiAgentImageUri,
                            onRunTask = viewModel::runMultiAgentTask,
                            onCancelTask = viewModel::cancelMultiAgentTask,
                            onBannerMessage = viewModel::showBanner
                        )
                    }
                    composable<ApiQoderRoute> {
                        ApiAndQoderScreen(
                            serverStatus = serverStatus,
                            networkState = networkState,
                            configuredPort = settings.serverPort,
                            securityBackendName = viewModel.securityBackendName,
                            primaryKeyPlaintext = primaryKeyPlaintext,
                            newlyCreatedDeviceKey = newlyCreatedDeviceKey,
                            apiKeys = apiKeys,
                            connectedDevices = connectedDevices,
                            apiLogs = apiLogs,
                            onStartServer = viewModel::startApiServer,
                            onStopServer = viewModel::stopApiServer,
                            onRefreshNetwork = viewModel::refreshNetworkIp,
                            onRegeneratePrimary = viewModel::regeneratePrimaryKey,
                            onRevokePrimary = viewModel::revokePrimaryKey,
                            onCreateDeviceKey = viewModel::createAdditionalKey,
                            onDismissNewDeviceKey = viewModel::dismissNewlyCreatedDeviceKey,
                            onToggleKeyActive = viewModel::toggleKeyActive,
                            onDeleteAdditionalKey = viewModel::deleteAdditionalKey,
                            onToggleDeviceBlocked = viewModel::toggleDeviceBlocked,
                            onClearLogs = viewModel::clearLogs,
                            onBannerMessage = viewModel::showBanner
                        )
                    }
                    composable<ExtensionsRoute> {
                        ExtensionsScreen(enabled = settings.enabledExtensions, onToggle = viewModel::toggleExtension)
                    }
                    composable<CalibrationRoute> {
                        CalibrationScreen(
                            state = calibrationState,
                            durations = calibrationDurations,
                            onDurationChange = viewModel::setCalibrationDuration,
                            onStart = viewModel::startCalibration,
                            onStop = viewModel::stopCalibration
                        )
                    }
                    composable<SettingsRoute> {
                        SettingsScreen(
                            settings = settings,
                            memoryState = memoryState,
                            diagnostics = diagnosticsResults,
                            isRunningDiagnostics = isRunningDiagnostics,
                            onUpdatePort = viewModel::updateServerPort,
                            onUpdateRamLimit = viewModel::updateRamLimitMb,
                            onUpdateLanOnly = viewModel::updateLanOnlyMode,
                            onUpdateRateLimit = viewModel::updateRateLimit,
                            onUpdateMaxRevisions = viewModel::updateMaxRevisions,
                            onUpdateTimeout = viewModel::updateMultiAgentTimeout,
                            onUpdateAutoUnload = viewModel::updateAutoUnload,
                            onUpdateIdleUnloadMinutes = viewModel::updateIdleUnloadMinutes,
                            onUpdateThermalGuard = viewModel::updateThermalGuard,
                            onUpdateDefaultMaxTokens = viewModel::updateDefaultMaxTokens,
                            onUpdateAutoStartServer = viewModel::updateAutoStartServer,
                            onUpdateDarkTheme = viewModel::updateDarkTheme,
                            onClearCaches = viewModel::clearTemporaryCaches,
                            onResetDefaults = viewModel::resetSettingsToDefaults,
                            onRunDiagnostics = viewModel::runDiagnostics
                        )
                    }
                    }
                }
            }
        }
    }
}

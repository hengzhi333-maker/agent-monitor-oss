package com.agentmonitor.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.agentmonitor.app.ui.MonitorViewModel
import com.agentmonitor.app.ui.screens.AgentsOverviewScreen
import com.agentmonitor.app.ui.screens.AgentSessionsScreen
import com.agentmonitor.app.ui.screens.AlertsScreen
import com.agentmonitor.app.ui.screens.BackupScreen
import com.agentmonitor.app.ui.screens.ConversationScreen
import com.agentmonitor.app.ui.screens.DashboardScreen
import com.agentmonitor.app.ui.screens.DiagnosticsScreen
import com.agentmonitor.app.ui.screens.DeviceManagementScreen
import com.agentmonitor.app.ui.screens.FilesScreen
import com.agentmonitor.app.ui.screens.HostEditScreen
import com.agentmonitor.app.ui.screens.QrScannerScreen
import com.agentmonitor.app.ui.screens.SecurityCenterScreen
import com.agentmonitor.app.ui.screens.SettingsScreen
import com.agentmonitor.app.ui.screens.WorkbenchHubScreen
import com.agentmonitor.app.ui.screens.WorkbenchListScreen
import com.agentmonitor.app.ui.screens.WorkbenchScreen
import com.agentmonitor.app.ui.theme.Accent
import com.agentmonitor.app.ui.theme.AgentMonitorTheme
import com.agentmonitor.app.ui.theme.Bg
import com.agentmonitor.app.ui.theme.BgCard
import com.agentmonitor.app.ui.theme.TextPrimary
import com.agentmonitor.app.ui.theme.TextSecondary

class MainActivity : ComponentActivity() {
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
        MonitorEngine.init(applicationContext)
        handleIncomingIntent(intent)
        MonitorEngine.start()
        startMonitorService()
        setContent {
            AgentMonitorTheme {
                AppRoot(
                    vm = viewModel(factory = MonitorViewModel.Factory())
                )
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun startMonitorService() {
        val intent = Intent(this, MonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, intent)
        } else {
            startService(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        intent?.data?.let { MonitorEngine.importHostUri(it) }
    }
}

@Composable
private fun AppRoot(vm: MonitorViewModel) {
    val nav = rememberNavController()
    val state by vm.ui.collectAsState()
    val repo = vm.repository()
    val backStackEntry by nav.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val topLevelRoutes = listOf("dashboard", "agents", "workbenchHub", "files", "settings")

    Scaffold(
        containerColor = Bg,
        bottomBar = {
            if (currentRoute in topLevelRoutes) {
                NavigationBar(containerColor = BgCard, contentColor = TextPrimary) {
                    val items = listOf(
                        HomeNavItem("dashboard", "主面板", Icons.Default.Dashboard),
                        HomeNavItem("agents", "Agent", Icons.Default.Psychology),
                        HomeNavItem("workbenchHub", "工作台", Icons.Default.Terminal),
                        HomeNavItem("files", "文件", Icons.Default.Folder),
                        HomeNavItem("settings", "设置", Icons.Default.Settings)
                    )
                    items.forEach { item ->
                        NavigationBarItem(
                            selected = currentRoute == item.route,
                            onClick = {
                                nav.navigate(item.route) {
                                    popUpTo("dashboard") { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    item.icon,
                                    contentDescription = item.label,
                                    tint = if (currentRoute == item.route) Accent else TextSecondary
                                )
                            },
                            label = {
                                Text(item.label, color = if (currentRoute == item.route) TextPrimary else TextSecondary)
                            }
                        )
                    }
                }
            }
        }
    ) { shellPad ->
        NavHost(
            navController = nav,
            startDestination = "dashboard",
            modifier = Modifier.padding(bottom = if (currentRoute in topLevelRoutes) shellPad.calculateBottomPadding() else shellPad.calculateBottomPadding())
        ) {
            composable("dashboard") {
                DashboardScreen(
                    state = state,
                    repo = repo,
                    onAddHost = { nav.navigate("host/new") },
                    onEditHost = { id -> nav.navigate("host/$id") },
                    onRetry = { id -> vm.retry(id) },
                    onOpenAgentSessions = { hostId, agentId ->
                        nav.navigate("agent/${Uri.encode(hostId)}/${Uri.encode(agentId)}/sessions")
                    },
                    onOpenSession = { hostId, agentId, sessionId ->
                        nav.navigate("session/${Uri.encode(hostId)}/${Uri.encode(agentId)}/${Uri.encode(sessionId)}")
                    },
                    onOpenWorkbench = { hostId, agentId ->
                        nav.navigate("workbench/${Uri.encode(hostId)}/${Uri.encode(agentId)}")
                    },
                    onOpenDiagnostics = { hostId -> nav.navigate("diagnostics/${Uri.encode(hostId)}") },
                    onOpenSecurity = { hostId -> nav.navigate("security/${Uri.encode(hostId)}") },
                    onOpenAlerts = { nav.navigate("alerts") }
                )
            }
            composable("agents") {
                AgentsOverviewScreen(
                    state = state,
                    onOpenAgentSessions = { hostId, agentId ->
                        nav.navigate("agent/${Uri.encode(hostId)}/${Uri.encode(agentId)}/sessions")
                    },
                    onOpenSession = { hostId, agentId, sessionId ->
                        nav.navigate("session/${Uri.encode(hostId)}/${Uri.encode(agentId)}/${Uri.encode(sessionId)}")
                    },
                    onOpenWorkbench = { hostId, agentId ->
                        nav.navigate("workbench/${Uri.encode(hostId)}/${Uri.encode(agentId)}")
                    }
                )
            }
            composable("workbenchHub") {
                WorkbenchHubScreen(
                    state = state,
                    onOpenWorkbench = { hostId, agentId ->
                        nav.navigate("workbench/${Uri.encode(hostId)}/${Uri.encode(agentId)}")
                    }
                )
            }
            composable("files") {
                FilesScreen(hosts = vm.hosts(), repo = repo)
            }
            composable("settings") {
                SettingsScreen(
                    state = state,
                    onAddHost = { nav.navigate("host/new") },
                    onEditHost = { id -> nav.navigate("host/$id") },
                    onOpenDiagnostics = { id -> nav.navigate("diagnostics/${Uri.encode(id)}") },
                    onOpenSecurity = { id -> nav.navigate("security/${Uri.encode(id)}") },
                    onOpenBackup = { nav.navigate("backup") },
                    onRetry = { id -> vm.retry(id) },
                    onCleanupUsbHosts = { vm.cleanupUsbHosts() }
                )
            }
        composable("host/new") {
            HostEditScreen(
                existing = null,
                onSave = { vm.addOrUpdateHost(it); nav.popBackStack() },
                onDelete = null,
                onBack = { nav.popBackStack() }
            )
        }
        composable("host/{id}") { entry ->
            val id = entry.arguments?.getString("id")
            val cfg = vm.hosts().find { it.id == id }
            HostEditScreen(
                existing = cfg,
                onSave = { vm.addOrUpdateHost(it); nav.popBackStack() },
                onDelete = { delId -> vm.removeHost(delId); nav.popBackStack() },
                onBack = { nav.popBackStack() }
            )
        }
        composable("alerts") {
            AlertsScreen(
                alerts = state.alerts,
                onClear = { vm.clearAlerts() },
                onBack = { nav.popBackStack() }
            )
        }
        composable("backup") {
            BackupScreen(
                exportHosts = { password -> vm.exportHosts(password) },
                importHosts = { raw, password -> vm.importHosts(raw, password) },
                importHostUri = { raw -> vm.importHostUri(raw) },
                onScan = { nav.navigate("scan") },
                onBack = { nav.popBackStack() }
            )
        }
        composable("scan") {
            QrScannerScreen(
                onScanned = { raw ->
                    if (vm.importHostUri(raw)) {
                        nav.popBackStack("dashboard", false)
                    } else {
                        nav.popBackStack()
                    }
                },
                onBack = { nav.popBackStack() }
            )
        }
        composable("diagnostics/{hostId}") { entry ->
            val hostId = entry.arguments?.getString("hostId").orEmpty()
            val host = vm.hosts().find { it.id == hostId }
            DiagnosticsScreen(
                host = host,
                repo = repo,
                onBack = { nav.popBackStack() }
            )
        }
        composable("security/{hostId}") { entry ->
            val hostId = entry.arguments?.getString("hostId").orEmpty()
            val host = vm.hosts().find { it.id == hostId }
            SecurityCenterScreen(
                host = host,
                repo = repo,
                onHostUpdated = { vm.addOrUpdateHost(it) },
                onOpenDevices = { nav.navigate("devices/${Uri.encode(hostId)}") },
                onBack = { nav.popBackStack() }
            )
        }
        composable("devices/{hostId}") { entry ->
            val hostId = entry.arguments?.getString("hostId").orEmpty()
            val host = vm.hosts().find { it.id == hostId }
            DeviceManagementScreen(
                host = host,
                repo = repo,
                onBack = { nav.popBackStack() }
            )
        }
        composable("agent/{hostId}/{agentId}/sessions") { entry ->
            val hostId = entry.arguments?.getString("hostId").orEmpty()
            val agentId = entry.arguments?.getString("agentId").orEmpty()
            val host = vm.hosts().find { it.id == hostId }
            AgentSessionsScreen(
                host = host,
                agentId = agentId,
                repo = repo,
                onOpenSession = { sessionId ->
                    nav.navigate("session/${Uri.encode(hostId)}/${Uri.encode(agentId)}/${Uri.encode(sessionId)}")
                },
                onBack = { nav.popBackStack() }
            )
        }
        composable("session/{hostId}/{agentId}/{sessionId}") { entry ->
            val hostId = entry.arguments?.getString("hostId").orEmpty()
            val agentId = entry.arguments?.getString("agentId").orEmpty()
            val sessionId = entry.arguments?.getString("sessionId").orEmpty()
            val host = vm.hosts().find { it.id == hostId }
            ConversationScreen(
                host = host,
                agentId = agentId,
                sessionId = sessionId,
                repo = repo,
                onBack = { nav.popBackStack() }
            )
        }
        composable("workbench/{hostId}/{agentId}") { entry ->
            val hostId = entry.arguments?.getString("hostId").orEmpty()
            val agentId = entry.arguments?.getString("agentId").orEmpty()
            val host = vm.hosts().find { it.id == hostId }
            WorkbenchListScreen(
                host = host,
                agentId = agentId,
                repo = repo,
                onOpenSession = { sessionId ->
                    nav.navigate("workbench/${Uri.encode(hostId)}/${Uri.encode(agentId)}/${Uri.encode(sessionId)}")
                },
                onBack = { nav.popBackStack() }
            )
        }
        composable("workbench/{hostId}/{agentId}/{sessionId}") { entry ->
            val hostId = entry.arguments?.getString("hostId").orEmpty()
            val sessionId = entry.arguments?.getString("sessionId").orEmpty()
            val host = vm.hosts().find { it.id == hostId }
            WorkbenchScreen(
                host = host,
                sessionId = sessionId,
                repo = repo,
                liveEvents = vm.workbenchEvents(hostId),
                onBack = { nav.popBackStack() }
            )
        }
    }
    }
}

private data class HomeNavItem(
    val route: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

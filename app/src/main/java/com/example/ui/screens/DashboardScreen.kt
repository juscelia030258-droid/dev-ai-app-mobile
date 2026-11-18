package com.example.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.agent_engine.MultiAgentLiveState
import com.example.api_server.ApiServerStatus
import com.example.api_server.NetworkAddressState
import com.example.core.model.AgentRole
import com.example.core.model.AgentRuntimeState
import com.example.memory.MemoryStatusState
import com.example.monitoring.data.AgentConfigEntity
import com.example.monitoring.data.LocalModelEntity
import com.example.ui.components.MonoCopyBox
import com.example.ui.components.ObsidianCard
import com.example.ui.components.RamProgressMeter
import com.example.ui.components.SectionHeader
import com.example.ui.components.StatusPill
import com.example.ui.components.agentAccentColor
import com.example.ui.components.runtimeStateLabel
import com.example.ui.theme.MonoFontFamily
import com.example.ui.theme.MultiAgentAmber
import com.example.ui.theme.StatusDanger
import com.example.ui.theme.StatusOnline

@Composable
fun DashboardScreen(
    memoryState: MemoryStatusState,
    networkState: NetworkAddressState,
    serverStatus: ApiServerStatus,
    agentStates: Map<AgentRole, AgentRuntimeState>,
    agentConfigs: List<AgentConfigEntity>,
    models: List<LocalModelEntity>,
    multiAgentState: MultiAgentLiveState,
    primaryKeyPlaintext: String?,
    queueDepth: Int,
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
    onUnloadModels: () -> Unit,
    onNavigateToAgents: () -> Unit,
    onNavigateToModels: () -> Unit,
    onNavigateToApi: () -> Unit,
    onNavigateToMultiAgent: () -> Unit,
    onBannerMessage: (String) -> Unit
) {
    val profile = memoryState.profile
    val configMap = agentConfigs.associateBy { it.roleId }
    val modelMap = models.associateBy { it.id }

    val notificationPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        onStartServer()
    }

    val startWithPermissionCheck = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            onStartServer()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Minimal Hardware Identity & Telemetry Strip
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${profile.manufacturer} ${profile.modelName} • ${profile.abi}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = MonoFontFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "${profile.androidVersionLabel} • ${profile.thermalStatusLabel}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                ObsidianCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TelemetryCell(
                            label = "Requisições",
                            value = serverStatus.totalRequestsHandled.toString(),
                            modifier = Modifier.weight(1f)
                        )
                        TelemetryCell(
                            label = "RAM Livre OS",
                            value = "${profile.availableRamMb} MB",
                            modifier = Modifier.weight(1f)
                        )
                        TelemetryCell(
                            label = "Fila Local",
                            value = queueDepth.toString(),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // Server & LAN Quick Connect Card
        item {
            ObsidianCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Servidor API Local (OpenAI)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (networkState.isWifiConnected) {
                                "${networkState.interfaceName ?: "wlan0"} • ${serverStatus.activeConnections} conexões ativas"
                            } else {
                                "Conecte ao Wi-Fi local para acesso LAN"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    StatusPill(
                        label = if (serverStatus.isRunning) "Ativo" else "Inativo",
                        color = if (serverStatus.isRunning) StatusOnline else MultiAgentAmber
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                MonoCopyBox(
                    label = "Endpoint OpenAI",
                    value = networkState.buildBaseUrl(serverStatus.port),
                    onCopySuccess = onBannerMessage
                )
                if (!primaryKeyPlaintext.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    val masked = "${primaryKeyPlaintext.take(12)}••••${primaryKeyPlaintext.takeLast(4)}"
                    MonoCopyBox(
                        label = "Chave API Permanente",
                        value = masked,
                        copyValue = primaryKeyPlaintext,
                        onCopySuccess = onBannerMessage
                    )
                }

                if (!serverStatus.lastError.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = serverStatus.lastError,
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusDanger
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = { if (serverStatus.isRunning) onStopServer() else startWithPermissionCheck() },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("dashboard_server_toggle_button"),
                        colors = if (serverStatus.isRunning) {
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = StatusDanger
                            )
                        } else {
                            ButtonDefaults.buttonColors()
                        }
                    ) {
                        Icon(
                            imageVector = if (serverStatus.isRunning) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (serverStatus.isRunning) "Parar" else "Iniciar Servidor")
                    }
                    OutlinedButton(
                        onClick = onNavigateToApi,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Qoder & Chaves")
                    }
                }
            }
        }

        // Memory & Active Model Card
        item {
            ObsidianCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Memória & Runtime",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Disco livre: ${profile.freeStorageMb / 1024} GB",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                RamProgressMeter(
                    usedMb = memoryState.estimatedModelsRamMb,
                    budgetMb = memoryState.userConfiguredRamLimitMb,
                    totalPhysicalMb = profile.totalRamMb
                )

                val loaded = memoryState.loadedModels.firstOrNull()
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = loaded?.modelName ?: "Nenhum modelo em memória",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = loaded?.let { "${it.runtimeBackend} • ~${it.estimatedFootprintMb} MB" }
                                ?: "${models.size} modelo(s) disponíveis no armazenamento",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (loaded != null) {
                        TextButton(onClick = onUnloadModels) {
                            Text("Descarregar")
                        }
                    } else {
                        TextButton(onClick = onNavigateToModels) {
                            Text("Gerenciar")
                        }
                    }
                }
            }
        }

        // Active Multi-Agent Task Banner
        if (multiAgentState.isRunning || multiAgentState.finalOutput.isNotBlank()) {
            item {
                ObsidianCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Filled.Hub, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(
                                text = "Orquestração Multiagente",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        StatusPill(
                            label = multiAgentState.currentPhase,
                            color = if (multiAgentState.isRunning) MultiAgentAmber else StatusOnline
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = multiAgentState.originalRequest.take(120),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    TextButton(
                        onClick = onNavigateToMultiAgent,
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("Ver fluxo (${multiAgentState.steps.size} etapas)")
                    }
                }
            }
        }

        // Unified Grouped Agents List
        item {
            SectionHeader(
                title = "INTELIGÊNCIAS LOCAIS",
                trailing = {
                    TextButton(onClick = onNavigateToAgents) {
                        Text("Configurar")
                    }
                }
            )
        }

        item {
            ObsidianCard {
                AgentRole.entries.forEachIndexed { index, role ->
                    val cfg = configMap[role.id]
                    val assignedModel = cfg?.assignedModelId?.let { modelMap[it] }
                    val rtState = if (cfg?.enabled == false) AgentRuntimeState.DISABLED else (agentStates[role] ?: AgentRuntimeState.IDLE)
                    val (stateLabel, stateColor) = runtimeStateLabel(rtState)
                    val accent = agentAccentColor(role)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onNavigateToAgents)
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(accent)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = role.displayName,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = assignedModel?.let { "${it.name} • ${it.quantization}" }
                                        ?: "Sem modelo associado",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (assignedModel != null) MaterialTheme.colorScheme.onSurfaceVariant else MultiAgentAmber,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            StatusPill(label = stateLabel, color = stateColor)
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    if (index < AgentRole.entries.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun TelemetryCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontFamily = MonoFontFamily,
            fontWeight = FontWeight.SemiBold
        )
    }
}

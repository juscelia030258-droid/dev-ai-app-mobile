package com.example.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LaptopMac
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material3.minimumInteractiveComponentSize
import com.example.api_server.ApiServerStatus
import com.example.api_server.NetworkAddressState
import com.example.core.model.AgentRole
import com.example.monitoring.data.ApiKeyEntity
import com.example.monitoring.data.ApiRequestLogEntity
import com.example.monitoring.data.ConnectedDeviceEntity
import com.example.security.KeyPermission
import com.example.ui.components.MonoCopyBox
import com.example.ui.components.ObsidianCard
import com.example.ui.components.SectionHeader
import com.example.ui.components.StatusPill
import com.example.ui.components.copyToClipboard
import com.example.ui.theme.CodingCyan
import com.example.ui.theme.MonoFontFamily
import com.example.ui.theme.MultiAgentAmber
import com.example.ui.theme.StatusDanger
import com.example.ui.theme.StatusOnline
import com.example.ui.theme.VisionEmerald
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ApiAndQoderScreen(
    serverStatus: ApiServerStatus,
    networkState: NetworkAddressState,
    configuredPort: Int,
    securityBackendName: String,
    primaryKeyPlaintext: String?,
    newlyCreatedDeviceKey: Pair<String, String>?,
    apiKeys: List<ApiKeyEntity>,
    connectedDevices: List<ConnectedDeviceEntity>,
    apiLogs: List<ApiRequestLogEntity>,
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
    onRefreshNetwork: () -> Unit,
    onRegeneratePrimary: () -> Unit,
    onRevokePrimary: () -> Unit,
    onCreateDeviceKey: (String, KeyPermission) -> Unit,
    onDismissNewDeviceKey: () -> Unit,
    onToggleKeyActive: (ApiKeyEntity) -> Unit,
    onDeleteAdditionalKey: (String) -> Unit,
    onToggleDeviceBlocked: (ConnectedDeviceEntity) -> Unit,
    onClearLogs: () -> Unit,
    onBannerMessage: (String) -> Unit
) {
    val context = LocalContext.current
    var revealPrimary by remember { mutableStateOf(false) }
    var confirmRegenerateDialog by remember { mutableStateOf(false) }
    var confirmRevokeDialog by remember { mutableStateOf(false) }
    var showCreateKeyDialog by remember { mutableStateOf(false) }

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

    val primaryKeyEntity = apiKeys.firstOrNull { it.isPrimary }
    val additionalKeys = apiKeys.filter { !it.isPrimary }
    val activePort = if (serverStatus.isRunning) serverStatus.port else configuredPort
    val baseUrl = networkState.buildBaseUrl(activePort)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Server State & LAN Discovery
        item {
            ObsidianCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Servidor API Local",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "${networkState.interfaceName ?: "wlan0"} • Porta $activePort",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    StatusPill(
                        label = if (serverStatus.isRunning) "Ativo" else "Desligado",
                        color = if (serverStatus.isRunning) StatusOnline else MultiAgentAmber
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                MonoCopyBox(
                    label = "Base URL OpenAI-Compatível",
                    value = baseUrl,
                    onCopySuccess = onBannerMessage
                )

                if (serverStatus.isRunning && serverStatus.port != configuredPort) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Nota: porta configurada ($configuredPort) difere da porta ativa (${serverStatus.port}).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MultiAgentAmber
                    )
                }

                if (!serverStatus.lastError.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Erro do servidor: ${serverStatus.lastError}",
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusDanger
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { if (serverStatus.isRunning) onStopServer() else startWithPermissionCheck() },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("api_server_toggle_button"),
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
                    OutlinedButton(onClick = onRefreshNetwork) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Atualizar IP", modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Atualizar IP")
                    }
                }
            }
        }

        // Permanent Primary API Key
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
                        Icon(Icons.Filled.Key, contentDescription = null, modifier = Modifier.size(18.dp))
                        Column {
                            Text(
                                text = "Chave API Principal Permanente",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = securityBackendName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    val active = primaryKeyEntity?.isActive == true
                    StatusPill(
                        label = if (active) "Ativa" else "Revogada",
                        color = if (active) StatusOnline else StatusDanger
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                val displayedKey = when {
                    primaryKeyPlaintext.isNullOrBlank() -> primaryKeyEntity?.keyPrefix ?: "Gerando..."
                    revealPrimary -> primaryKeyPlaintext
                    else -> "${primaryKeyPlaintext.take(12)}••••${primaryKeyPlaintext.takeLast(4)}"
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    MonoCopyBox(
                        label = "API Key (persiste após reiniciar)",
                        value = displayedKey,
                        copyValue = primaryKeyPlaintext ?: displayedKey,
                        onCopySuccess = onBannerMessage,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { revealPrimary = !revealPrimary },
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(
                            imageVector = if (revealPrimary) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (revealPrimary) "Ocultar chave" else "Revelar chave"
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = { confirmRegenerateDialog = true },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("regenerate_primary_key_button")
                    ) {
                        Text("Regenerar")
                    }
                    OutlinedButton(
                        onClick = { confirmRevokeDialog = true },
                        enabled = primaryKeyEntity?.isActive == true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("revoke_primary_key_button")
                    ) {
                        Text("Revogar", color = StatusDanger)
                    }
                }
            }
        }

        // Qoder Integration Instructions
        item {
            ObsidianCard {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Filled.LaptopMac, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(
                        text = "Qoder & Clientes OpenAI",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Configure o provedor OpenAI-Compatible com os identificadores abaixo:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))

                MonoCopyBox("1. Base URL", baseUrl, onBannerMessage)
                Spacer(modifier = Modifier.height(6.dp))
                MonoCopyBox("2. Model ID (Coding)", AgentRole.CODING.apiModelId, onBannerMessage)
                Spacer(modifier = Modifier.height(6.dp))
                MonoCopyBox("3. Model ID (Reasoning)", AgentRole.REASONING.apiModelId, onBannerMessage)
                Spacer(modifier = Modifier.height(6.dp))
                MonoCopyBox("4. Model ID (Multiagente)", AgentRole.MULTIAGENT_MODEL_ID, onBannerMessage)
                Spacer(modifier = Modifier.height(6.dp))
                MonoCopyBox("5. Model ID (Vision)", AgentRole.VISION.apiModelId, onBannerMessage)

                Spacer(modifier = Modifier.height(10.dp))
                val curlCmd = """curl -sS "$baseUrl/chat/completions" -H "Authorization: Bearer ${primaryKeyPlaintext ?: "SUA_CHAVE"}" -H "Content-Type: application/json" -d '{"model":"local-dev-coding","messages":[{"role":"user","content":"Crie uma função Kotlin thread-safe"}],"stream":false}'"""
                MonoCopyBox(
                    label = "Teste rápido cURL (MacBook Terminal)",
                    value = curlCmd,
                    onCopySuccess = onBannerMessage
                )
            }
        }

        // Additional Device API Keys
        item {
            SectionHeader(
                title = "CHAVES ADICIONAIS DE DISPOSITIVOS (${additionalKeys.size})",
                trailing = {
                    OutlinedButton(
                        onClick = { showCreateKeyDialog = true },
                        modifier = Modifier.testTag("create_device_key_button")
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Nova Chave")
                    }
                }
            )
        }

        if (additionalKeys.isEmpty()) {
            item {
                ObsidianCard {
                    Text(
                        text = "Crie chaves dedicadas (ex: 'MacBook Air', 'Qoder') com escopo granular e revogação independente.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(additionalKeys, key = { it.id }) { keyItem ->
                val perm = runCatching { KeyPermission.valueOf(keyItem.permissions) }
                    .getOrDefault(KeyPermission.ALL_AGENTS)
                ObsidianCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(keyItem.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = "${keyItem.keyPrefix} • ${perm.label}",
                                fontFamily = MonoFontFamily,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Acessos: ${keyItem.requestCount} • Último IP: ${keyItem.lastClientIp ?: "Nunca"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        StatusPill(
                            label = if (keyItem.isActive) "Ativa" else "Revogada",
                            color = if (keyItem.isActive) StatusOnline else StatusDanger
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { onToggleKeyActive(keyItem) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (keyItem.isActive) "Revogar" else "Reativar")
                        }
                        IconButton(
                            onClick = { onDeleteAdditionalKey(keyItem.id) },
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = "Excluir chave", tint = StatusDanger)
                        }
                    }
                }
            }
        }

        // Connected Devices
        item {
            SectionHeader(title = "DISPOSITIVOS CONECTADOS (${connectedDevices.size})")
        }

        if (connectedDevices.isEmpty()) {
            item {
                ObsidianCard {
                    Text(
                        text = "Nenhum dispositivo externo se autenticou na API ainda.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(connectedDevices, key = { it.deviceFingerprint }) { dev ->
                ObsidianCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(dev.clientName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = "IP: ${dev.ipAddress} • Chave: ${dev.keyName} • Reqs: ${dev.totalRequests}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedButton(onClick = { onToggleDeviceBlocked(dev) }) {
                            Text(
                                text = if (dev.isBlocked) "Desbloquear" else "Bloquear",
                                color = if (dev.isBlocked) StatusOnline else StatusDanger
                            )
                        }
                    }
                }
            }
        }

        // Request Logs
        item {
            SectionHeader(
                title = "LOGS SEGUROS DA API (${apiLogs.size})",
                trailing = if (apiLogs.isNotEmpty()) {
                    {
                        TextButton(onClick = onClearLogs) {
                            Text("Limpar")
                        }
                    }
                } else null
            )
        }

        if (apiLogs.isEmpty()) {
            item {
                ObsidianCard {
                    Text(
                        text = "Nenhuma requisição registrada ainda. Chamadas autenticadas ou bloqueadas aparecerão aqui com IP, latência e código HTTP.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        items(apiLogs.take(25), key = { it.id }) { log ->
            val timeStr = remember(log.timestamp) {
                SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(log.timestamp))
            }
            val codeColor = when {
                log.statusCode in 200..299 -> StatusOnline
                log.statusCode == 401 || log.statusCode == 403 -> StatusDanger
                else -> MultiAgentAmber
            }
            ObsidianCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "$timeStr • ${log.method} ${log.path}",
                        fontFamily = MonoFontFamily,
                        style = MaterialTheme.typography.labelMedium
                    )
                    StatusPill(label = "${log.statusCode} • ${log.latencyMs}ms", color = codeColor)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "IP: ${log.remoteIp} | Modelo: ${log.modelRequested ?: "-"} | ${log.summary}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (confirmRegenerateDialog) {
        AlertDialog(
            onDismissRequest = { confirmRegenerateDialog = false },
            title = { Text("Regenerar Chave Principal?") },
            text = { Text("A chave atual deixará de funcionar imediatamente e clientes como o Qoder precisarão ser atualizados com a nova chave.") },
            confirmButton = {
                Button(onClick = {
                    confirmRegenerateDialog = false
                    onRegeneratePrimary()
                }) { Text("Regenerar") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRegenerateDialog = false }) { Text("Cancelar") }
            }
        )
    }

    if (confirmRevokeDialog) {
        AlertDialog(
            onDismissRequest = { confirmRevokeDialog = false },
            title = { Text("Revogar Chave Principal?") },
            text = { Text("Todas as requisições com a chave principal retornarão HTTP 401 até que você gere uma nova chave.") },
            confirmButton = {
                Button(
                    onClick = {
                        confirmRevokeDialog = false
                        onRevokePrimary()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StatusDanger)
                ) { Text("Revogar") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRevokeDialog = false }) { Text("Cancelar") }
            }
        )
    }

    if (showCreateKeyDialog) {
        CreateDeviceKeyDialog(
            onDismiss = { showCreateKeyDialog = false },
            onCreate = { name, perm ->
                showCreateKeyDialog = false
                onCreateDeviceKey(name, perm)
            }
        )
    }

    if (newlyCreatedDeviceKey != null) {
        AlertDialog(
            onDismissRequest = onDismissNewDeviceKey,
            title = { Text("Chave Criada: ${newlyCreatedDeviceKey.first}") },
            text = {
                Column {
                    Text("Copie e configure esta chave no dispositivo autorizado:")
                    Spacer(modifier = Modifier.height(10.dp))
                    MonoCopyBox(
                        label = "Nova API Key",
                        value = newlyCreatedDeviceKey.second,
                        accent = VisionEmerald,
                        onCopySuccess = onBannerMessage
                    )
                }
            },
            confirmButton = {
                Button(onClick = onDismissNewDeviceKey) { Text("Concluir") }
            }
        )
    }
}

@Composable
private fun CreateDeviceKeyDialog(
    onDismiss: () -> Unit,
    onCreate: (String, KeyPermission) -> Unit
) {
    var name by remember { mutableStateOf("MacBook Air Qoder") }
    var permission by remember { mutableStateOf(KeyPermission.ALL_AGENTS) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nova Chave de Dispositivo") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nome do dispositivo / cliente") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Permissões:", style = MaterialTheme.typography.labelMedium)
                KeyPermission.entries.forEach { perm ->
                    FilterChip(
                        selected = permission == perm,
                        onClick = { permission = perm },
                        label = { Text(perm.label) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(name, permission) },
                enabled = name.isNotBlank()
            ) { Text("Criar Chave") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}

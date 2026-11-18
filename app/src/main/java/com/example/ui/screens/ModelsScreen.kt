package com.example.ui.screens

import android.net.Uri
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material3.minimumInteractiveComponentSize
import com.example.core.model.AgentRole
import com.example.core.model.CompatibilityStatus
import com.example.core.model.DownloadProgressState
import com.example.core.model.RecommendedModelPreset
import com.example.memory.MemoryStatusState
import com.example.monitoring.data.AgentConfigEntity
import com.example.monitoring.data.LocalModelEntity
import com.example.ui.components.ObsidianCard
import com.example.ui.components.SectionHeader
import com.example.ui.components.StatusPill
import com.example.ui.components.agentAccentColor
import com.example.ui.theme.CodingCyan
import com.example.ui.theme.MultiAgentAmber
import com.example.ui.theme.StatusDanger
import com.example.ui.theme.StatusOnline
import com.example.ui.theme.VisionEmerald

@Composable
fun ModelsScreen(
    models: List<LocalModelEntity>,
    agentConfigs: List<AgentConfigEntity>,
    presets: List<RecommendedModelPreset>,
    downloadState: DownloadProgressState?,
    memoryState: MemoryStatusState,
    onImportLocalModel: (Uri, Uri?, String?, AgentRole?, Boolean) -> Unit,
    onAttachMmproj: (String, Uri) -> Unit,
    onDownloadPreset: (RecommendedModelPreset) -> Unit,
    onDownloadCustomUrl: (String, String, String?, AgentRole?, Boolean) -> Unit,
    onCancelDownload: () -> Unit,
    onAssignToAgent: (AgentRole, String?) -> Unit,
    onLoadModel: (AgentRole, LocalModelEntity) -> Unit,
    onUnloadAll: () -> Unit,
    onDeleteModel: (String) -> Unit
) {
    var pendingAttachModelId by remember { mutableStateOf<String?>(null) }
    var showCustomUrlDialog by remember { mutableStateOf(false) }

    val modelFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            onImportLocalModel(uri, null, null, null, false)
        }
    }

    val mmprojFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        val targetId = pendingAttachModelId
        pendingAttachModelId = null
        if (uri != null && targetId != null) {
            onAttachMmproj(targetId, uri)
        }
    }

    val loadedIds = memoryState.loadedModels.map { it.modelId }.toSet()
    val roleByModelId = agentConfigs
        .filter { it.assignedModelId != null }
        .groupBy { it.assignedModelId!! }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Import & Download Header Actions
        item {
            ObsidianCard {
                Text(
                    text = "Gerenciador de Modelos Locais (ARM64)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Importe arquivos .gguf ou .litertlm ou baixe modelos compatíveis offline.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = { modelFilePicker.launch(arrayOf("*/*")) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("import_local_model_button")
                    ) {
                        Icon(Icons.Filled.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Importar")
                    }
                    OutlinedButton(
                        onClick = { showCustomUrlDialog = true },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("download_custom_url_button")
                    ) {
                        Icon(Icons.Filled.AddLink, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Baixar URL")
                    }
                }
            }
        }

        // Active Download Progress Banner
        if (downloadState != null) {
            item {
                ObsidianCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (downloadState.isDownloading) {
                                "Baixando ${downloadState.fileName} (${downloadState.progressPercent}%)"
                            } else {
                                "Download: ${downloadState.fileName}"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        if (downloadState.isDownloading) {
                            TextButton(
                                onClick = onCancelDownload,
                                modifier = Modifier.testTag("cancel_download_button")
                            ) {
                                Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp), tint = StatusDanger)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Cancelar", color = StatusDanger)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    if (downloadState.isDownloading) {
                        LinearProgressIndicator(
                            progress = { (downloadState.progressPercent / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        val mbDone = downloadState.bytesRead / (1024L * 1024L)
                        val mbTotal = if (downloadState.totalBytes > 0) "${downloadState.totalBytes / (1024L * 1024L)} MB" else "..."
                        Text(
                            text = "$mbDone MB / $mbTotal",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    downloadState.error?.let {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(text = it, style = MaterialTheme.typography.bodySmall, color = StatusDanger)
                    }
                }
            }
        }

        // Installed Models List
        item {
            SectionHeader(title = "MODELOS INSTALADOS (${models.size})")
        }

        if (models.isEmpty()) {
            item {
                ObsidianCard {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Filled.Memory, contentDescription = null, tint = CodingCyan)
                        Text(
                            text = "Nenhum modelo local instalado ainda. Importe um arquivo .gguf/.litertlm ou baixe um dos modelos recomendados abaixo.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(models, key = { it.id }) { model ->
                val isLoaded = loadedIds.contains(model.id)
                val compatEnum = runCatching { CompatibilityStatus.valueOf(model.compatibilityStatus) }
                    .getOrDefault(CompatibilityStatus.COMPATIBLE)
                val compatColor = when (compatEnum) {
                    CompatibilityStatus.COMPATIBLE -> StatusOnline
                    CompatibilityStatus.HIGH_MEMORY_WARNING -> MultiAgentAmber
                    else -> StatusDanger
                }
                val assignedRoles = roleByModelId[model.id].orEmpty().mapNotNull { cfg ->
                    AgentRole.entries.find { it.id == cfg.roleId }
                }

                var assignMenuExpanded by remember { mutableStateOf(false) }

                ObsidianCard(
                    borderColor = if (isLoaded) StatusOnline.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outline
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = model.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "${model.sizeBytes / (1024L * 1024L)} MB • ~${model.estimatedRamMb} MB RAM • ${model.quantization}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(
                            onClick = { onDeleteModel(model.id) },
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = "Remover modelo ${model.name}",
                                tint = StatusDanger
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusPill(label = compatEnum.label, color = compatColor)
                        if (model.supportsVision) {
                            StatusPill(label = "Visão Multimodal", color = VisionEmerald)
                        }
                        if (isLoaded) {
                            StatusPill(label = "Carregado na RAM", color = StatusOnline)
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = model.compatibilityDetail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (assignedRoles.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Associado a: ${assignedRoles.joinToString { it.displayName }}",
                            style = MaterialTheme.typography.labelMedium,
                            color = CodingCyan
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                if (isLoaded) onUnloadAll()
                                else onLoadModel(assignedRoles.firstOrNull() ?: AgentRole.CODING, model)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.Memory, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (isLoaded) "Descarregar" else "Carregar")
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            OutlinedButton(
                                onClick = { assignMenuExpanded = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Associar IA")
                            }
                            DropdownMenu(
                                expanded = assignMenuExpanded,
                                onDismissRequest = { assignMenuExpanded = false }
                            ) {
                                AgentRole.entries.forEach { role ->
                                    val isAssignedHere = assignedRoles.contains(role)
                                    val disabledVision = role == AgentRole.VISION && !model.supportsVision
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                when {
                                                    isAssignedHere -> "Desassociar de ${role.displayName}"
                                                    disabledVision -> "${role.displayName} (requer visão)"
                                                    else -> "Usar na ${role.displayName}"
                                                }
                                            )
                                        },
                                        enabled = isAssignedHere || !disabledVision,
                                        onClick = {
                                            onAssignToAgent(role, if (isAssignedHere) null else model.id)
                                            assignMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    if (model.format == "GGUF" && !model.supportsVision) {
                        Spacer(modifier = Modifier.height(6.dp))
                        TextButton(
                            onClick = {
                                pendingAttachModelId = model.id
                                mmprojFilePicker.launch(arrayOf("*/*"))
                            }
                        ) {
                            Text("Anexar projetor multimodal (mmproj*.gguf)")
                        }
                    }
                }
            }
        }

        // Recommended ARM64 Presets
        item {
            SectionHeader(title = "MODELOS RECOMENDADOS (GALAXY A56 ARM64)")
        }

        items(presets, key = { it.id }) { preset ->
            val accent = agentAccentColor(preset.recommendedRole)
            ObsidianCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = preset.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "${preset.estimatedSizeMb} MB disco • ~${preset.estimatedRamMb} MB RAM • ${preset.format.extension.uppercase()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    StatusPill(label = preset.recommendedRole.displayName, color = accent)
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = preset.notes,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { onDownloadPreset(preset) },
                    enabled = downloadState?.isDownloading != true,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Baixar • ${preset.recommendedRole.displayName}")
                }
            }
        }
    }

    if (showCustomUrlDialog) {
        CustomModelUrlDialog(
            onDismiss = { showCustomUrlDialog = false },
            onConfirm = { name, url, mmprojUrl, role, isVision ->
                showCustomUrlDialog = false
                onDownloadCustomUrl(name, url, mmprojUrl, role, isVision)
            }
        )
    }
}

@Composable
private fun CustomModelUrlDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String, String?, AgentRole?, Boolean) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var mmprojUrl by remember { mutableStateOf("") }
    var selectedRole by remember { mutableStateOf(AgentRole.CODING) }
    var supportsVision by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Baixar Modelo por URL HTTPS") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nome do modelo") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("URL .gguf ou .litertlm (HTTPS)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = mmprojUrl,
                    onValueChange = { mmprojUrl = it },
                    label = { Text("URL mmproj opcional (para Visão GGUF)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = supportsVision,
                        onCheckedChange = { supportsVision = it }
                    )
                    Text("Suporta entrada visual multimodal", style = MaterialTheme.typography.bodySmall)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AgentRole.entries.forEach { role ->
                        FilterChip(
                            selected = selectedRole == role,
                            onClick = { selectedRole = role },
                            label = {
                                Text(
                                    text = role.displayName.replace(" AI", ""),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name, url, mmprojUrl.ifBlank { null }, selectedRole, supportsVision) },
                enabled = url.startsWith("https://")
            ) {
                Text("Baixar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}

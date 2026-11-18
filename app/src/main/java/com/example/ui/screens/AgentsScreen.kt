package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material3.minimumInteractiveComponentSize
import coil.compose.AsyncImage
import com.example.core.model.AgentRole
import com.example.core.model.AgentRuntimeState
import com.example.core.model.ModelFormat
import com.example.core.model.RuntimeBackend
import com.example.monitoring.data.AgentConfigEntity
import com.example.monitoring.data.AgentHistoryEntity
import com.example.monitoring.data.LocalModelEntity
import com.example.ui.AgentConsoleState
import com.example.ui.components.ObsidianCard
import com.example.ui.components.SectionHeader
import com.example.ui.components.SettingsSliderRow
import com.example.ui.components.StatusPill
import com.example.ui.components.agentAccentColor
import com.example.ui.components.copyToClipboard
import com.example.ui.components.runtimeStateLabel
import com.example.ui.theme.MonoFontFamily
import com.example.ui.theme.MultiAgentAmber
import com.example.ui.theme.StatusDanger
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentsScreen(
    agentStates: Map<AgentRole, AgentRuntimeState>,
    agentConfigs: List<AgentConfigEntity>,
    models: List<LocalModelEntity>,
    consoles: Map<AgentRole, AgentConsoleState>,
    history: List<AgentHistoryEntity>,
    onToggleEnabled: (AgentRole, Boolean) -> Unit,
    onAssignModel: (AgentRole, String?) -> Unit,
    onUpdateConfig: (AgentConfigEntity) -> Unit,
    onSelectVisionImage: (Uri?) -> Unit,
    onRunPrompt: (AgentRole, String) -> Unit,
    onStopPrompt: (AgentRole) -> Unit,
    onBannerMessage: (String) -> Unit
) {
    val context = LocalContext.current
    var selectedRole by remember { mutableStateOf(AgentRole.CODING) }
    val roles = AgentRole.entries
    val selectedIdx = roles.indexOf(selectedRole)

    val configMap = agentConfigs.associateBy { it.roleId }
    val currentConfig = configMap[selectedRole.id] ?: AgentConfigEntity(roleId = selectedRole.id)
    val assignedModel = currentConfig.assignedModelId?.let { id -> models.find { it.id == id } }
    val consoleState = consoles[selectedRole] ?: AgentConsoleState()
    val accent = agentAccentColor(selectedRole)
    val rtState = if (!currentConfig.enabled) AgentRuntimeState.DISABLED else (agentStates[selectedRole] ?: AgentRuntimeState.IDLE)
    val (stateLabel, stateColor) = runtimeStateLabel(rtState)

    var promptInput by remember(selectedRole) { mutableStateOf("") }
    var customSystemPrompt by remember(selectedRole, currentConfig.systemPromptOverride) {
        mutableStateOf(currentConfig.systemPromptOverride.orEmpty())
    }
    var showParams by remember { mutableStateOf(false) }
    var modelDropdownExpanded by remember { mutableStateOf(false) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) onSelectVisionImage(uri)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(
            selectedTabIndex = selectedIdx,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            roles.forEach { role ->
                val icon = when (role) {
                    AgentRole.CODING -> Icons.Filled.Code
                    AgentRole.VISION -> Icons.Filled.Visibility
                    AgentRole.REASONING -> Icons.Filled.Psychology
                }
                Tab(
                    selected = role == selectedRole,
                    onClick = { selectedRole = role },
                    text = { Text(role.displayName.replace(" AI", "")) },
                    icon = { Icon(icon, contentDescription = role.displayName, modifier = Modifier.size(18.dp)) }
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Agent Header & Activation
            item {
                ObsidianCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = selectedRole.displayName,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = selectedRole.apiModelId,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = currentConfig.enabled,
                            onCheckedChange = { onToggleEnabled(selectedRole, it) },
                            modifier = Modifier.testTag("agent_enable_switch_${selectedRole.id}")
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = selectedRole.specialty,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StatusPill(label = stateLabel, color = stateColor)
                        if (assignedModel != null) {
                            StatusPill(
                                label = "~${assignedModel.estimatedRamMb} MB RAM",
                                color = accent
                            )
                        }
                    }
                }
            }

            // Model & Runtime Selection Card
            item {
                ObsidianCard {
                    Text(
                        text = "Modelo & Runtime",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    val compatibleModels = if (selectedRole == AgentRole.VISION) {
                        models.filter { it.supportsVision }
                    } else {
                        models
                    }

                    ExposedDropdownMenuBox(
                        expanded = modelDropdownExpanded,
                        onExpandedChange = { modelDropdownExpanded = !modelDropdownExpanded }
                    ) {
                        OutlinedTextField(
                            value = assignedModel?.name ?: if (compatibleModels.isEmpty()) {
                                if (selectedRole == AgentRole.VISION) "Nenhum modelo multimodal instalado"
                                else "Nenhum modelo local instalado"
                            } else {
                                "Selecione um modelo local"
                            },
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Modelo de ${selectedRole.displayName}") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelDropdownExpanded) },
                            modifier = Modifier
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                                .fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = modelDropdownExpanded,
                            onDismissRequest = { modelDropdownExpanded = false }
                        ) {
                            compatibleModels.forEach { mdl ->
                                DropdownMenuItem(
                                    text = {
                                        Text("${mdl.name} (${mdl.quantization} • ~${mdl.estimatedRamMb} MB)")
                                    },
                                    onClick = {
                                        onAssignModel(selectedRole, mdl.id)
                                        modelDropdownExpanded = false
                                    }
                                )
                            }
                            if (compatibleModels.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("Importe ou baixe modelos na aba Modelos") },
                                    onClick = { modelDropdownExpanded = false }
                                )
                            }
                        }
                    }

                    if (selectedRole == AgentRole.VISION && models.isNotEmpty() && compatibleModels.isEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "A Vision AI exige um modelo multimodal real (.gguf com mmproj ou .litertlm multimodal). Modelos apenas textuais não são exibidos aqui.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MultiAgentAmber
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Backend de execução:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val availableBackends = if (assignedModel?.format == ModelFormat.LITERT_LM.name) {
                            listOf(RuntimeBackend.LITERT_LM_CPU, RuntimeBackend.LITERT_LM_GPU)
                        } else {
                            listOf(RuntimeBackend.LLAMA_CPP)
                        }
                        availableBackends.forEach { rt ->
                            FilterChip(
                                selected = currentConfig.runtimeBackend == rt.name,
                                onClick = {
                                    onUpdateConfig(currentConfig.copy(runtimeBackend = rt.name))
                                },
                                label = { Text(rt.label, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = { showParams = !showParams },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (showParams) "Ocultar Parâmetros" else "Configurar Parâmetros (Ctx/Temp/Threads)")
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = if (showParams) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = null
                        )
                    }

                    AnimatedVisibility(visible = showParams) {
                        Column(
                            modifier = Modifier.padding(top = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            SettingsSliderRow(
                                label = "Temperatura",
                                valueText = "%.2f".format(currentConfig.temperature),
                                value = currentConfig.temperature,
                                onValueChange = {
                                    onUpdateConfig(currentConfig.copy(temperature = it))
                                },
                                valueRange = 0.0f..1.2f
                            )

                            SettingsSliderRow(
                                label = "Janela de Contexto",
                                valueText = "${currentConfig.contextLength} tokens",
                                value = currentConfig.contextLength.toFloat(),
                                onValueChange = {
                                    onUpdateConfig(currentConfig.copy(contextLength = ((it / 512f).roundToInt() * 512).coerceIn(1024, 8192)))
                                },
                                valueRange = 1024f..8192f
                            )

                            SettingsSliderRow(
                                label = "Limite de saída (Max Tokens)",
                                valueText = "${currentConfig.maxTokens} tokens",
                                value = currentConfig.maxTokens.toFloat(),
                                onValueChange = {
                                    onUpdateConfig(currentConfig.copy(maxTokens = ((it / 256f).roundToInt() * 256).coerceIn(256, 4096)))
                                },
                                valueRange = 256f..4096f,
                                modifier = Modifier.testTag("agent_max_tokens_slider_${selectedRole.id}")
                            )

                            SettingsSliderRow(
                                label = "Top-P (Amostragem Nuclear)",
                                valueText = "%.2f".format(currentConfig.topP),
                                value = currentConfig.topP,
                                onValueChange = {
                                    onUpdateConfig(currentConfig.copy(topP = it.coerceIn(0.1f, 1.0f)))
                                },
                                valueRange = 0.1f..1.0f,
                                modifier = Modifier.testTag("agent_top_p_slider_${selectedRole.id}")
                            )

                            SettingsSliderRow(
                                label = "Top-K",
                                valueText = "${currentConfig.topK}",
                                value = currentConfig.topK.toFloat(),
                                onValueChange = {
                                    onUpdateConfig(currentConfig.copy(topK = ((it / 5f).roundToInt() * 5).coerceIn(5, 100)))
                                },
                                valueRange = 5f..100f,
                                modifier = Modifier.testTag("agent_top_k_slider_${selectedRole.id}")
                            )

                            SettingsSliderRow(
                                label = "Threads CPU ARM64",
                                valueText = "${currentConfig.threads}",
                                value = currentConfig.threads.toFloat(),
                                onValueChange = {
                                    onUpdateConfig(currentConfig.copy(threads = it.roundToInt().coerceIn(1, 6)))
                                },
                                valueRange = 1f..6f,
                                steps = 4
                            )

                            Spacer(modifier = Modifier.height(4.dp))
                            OutlinedTextField(
                                value = customSystemPrompt,
                                onValueChange = { customSystemPrompt = it },
                                label = { Text("System Prompt personalizado (opcional)") },
                                minLines = 2,
                                maxLines = 4,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        onUpdateConfig(
                                            currentConfig.copy(
                                                systemPromptOverride = customSystemPrompt.trim().takeIf { it.isNotBlank() }
                                            )
                                        )
                                        onBannerMessage("System prompt de ${selectedRole.displayName} salvo.")
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Salvar Prompt")
                                }
                                if (!currentConfig.systemPromptOverride.isNullOrBlank()) {
                                    TextButton(
                                        onClick = {
                                            customSystemPrompt = ""
                                            onUpdateConfig(currentConfig.copy(systemPromptOverride = null))
                                            onBannerMessage("System prompt restaurado ao padrão.")
                                        }
                                    ) {
                                        Text("Restaurar Padrão")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Interactive Local Console
            item {
                ObsidianCard {
                    Text(
                        text = "Console Direto — ${selectedRole.displayName}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    if (selectedRole == AgentRole.VISION) {
                        if (assignedModel == null || !assignedModel.supportsVision) {
                            Text(
                                text = "Associe um modelo multimodal real acima para ativar a Vision AI.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MultiAgentAmber
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = {
                                    photoPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                modifier = Modifier.testTag("vision_pick_image_button")
                            ) {
                                Icon(Icons.Filled.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (consoleState.selectedImageUri == null) "Selecionar Imagem" else "Trocar Imagem")
                            }
                            if (consoleState.selectedImageUri != null) {
                                OutlinedButton(
                                    onClick = { onSelectVisionImage(null) },
                                    modifier = Modifier.testTag("vision_clear_image_button")
                                ) {
                                    Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Limpar")
                                }
                            }
                        }
                        if (consoleState.selectedImageUri != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            AsyncImage(
                                model = consoleState.selectedImageUri,
                                contentDescription = "Imagem selecionada para Vision AI",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(170.dp)
                                    .clip(MaterialTheme.shapes.small),
                                contentScale = ContentScale.Crop
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    OutlinedTextField(
                        value = promptInput,
                        onValueChange = { promptInput = it },
                        label = {
                            Text(
                                when (selectedRole) {
                                    AgentRole.CODING -> "Descreva o código, bug ou refatoração..."
                                    AgentRole.VISION -> "O que você deseja analisar nesta imagem?"
                                    AgentRole.REASONING -> "Informe um problema complexo para planejar e analisar..."
                                }
                            )
                        },
                        minLines = 3,
                        maxLines = 6,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("agent_console_input_${selectedRole.id}")
                    )

                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = {
                                if (consoleState.isGenerating) onStopPrompt(selectedRole)
                                else onRunPrompt(selectedRole, promptInput)
                            },
                            enabled = currentConfig.enabled && (consoleState.isGenerating || promptInput.isNotBlank()),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("agent_run_button_${selectedRole.id}"),
                            colors = if (consoleState.isGenerating) {
                                ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = StatusDanger
                                )
                            } else {
                                ButtonDefaults.buttonColors()
                            }
                        ) {
                            Icon(
                                imageVector = if (consoleState.isGenerating) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (consoleState.isGenerating) "Interromper" else "Executar Localmente")
                        }
                    }

                    if (!consoleState.error.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = consoleState.error,
                            style = MaterialTheme.typography.bodySmall,
                            color = StatusDanger
                        )
                    }

                    if (consoleState.output.isNotBlank()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Saída do modelo local:",
                                style = MaterialTheme.typography.labelMedium,
                                color = accent
                            )
                            IconButton(
                                onClick = {
                                    copyToClipboard(context, "Saída ${selectedRole.displayName}", consoleState.output) {
                                        onBannerMessage("Resposta copiada para a área de transferência.")
                                    }
                                },
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = "Copiar saída do agente")
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = consoleState.output,
                            fontFamily = MonoFontFamily,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            // Per-Agent History
            val roleHistory = history.filter { it.roleId == selectedRole.id }
            item {
                SectionHeader(title = "HISTÓRICO RECENTE — ${selectedRole.displayName.uppercase()} (${roleHistory.size})")
            }

            if (roleHistory.isEmpty()) {
                item {
                    ObsidianCard {
                        Text(
                            text = "Nenhuma execução registrada para ${selectedRole.displayName} ainda.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(roleHistory.take(15), key = { it.id }) { item ->
                    ObsidianCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            StatusPill(
                                label = "${item.source} • ${item.durationMs} ms",
                                color = if (item.success) accent else StatusDanger
                            )
                            Text(
                                text = item.modelIdUsed,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Prompt: ${item.promptPreview}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = item.responsePreview,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

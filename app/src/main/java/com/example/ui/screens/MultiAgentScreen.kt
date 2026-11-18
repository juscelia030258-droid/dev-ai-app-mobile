package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import com.example.agent_engine.MultiAgentLiveState
import com.example.monitoring.data.MultiAgentRunEntity
import com.example.ui.components.ObsidianCard
import com.example.ui.components.SectionHeader
import com.example.ui.components.StatusPill
import com.example.ui.components.copyToClipboard
import com.example.ui.theme.CodingCyan
import com.example.ui.theme.MonoFontFamily
import com.example.ui.theme.MultiAgentAmber
import com.example.ui.theme.ReasoningIndigo
import com.example.ui.theme.StatusDanger
import com.example.ui.theme.StatusOnline

@Composable
fun MultiAgentScreen(
    liveState: MultiAgentLiveState,
    selectedImageUri: Uri?,
    recentRuns: List<MultiAgentRunEntity>,
    onSelectImageUri: (Uri?) -> Unit,
    onRunTask: (String) -> Unit,
    onCancelTask: () -> Unit,
    onBannerMessage: (String) -> Unit
) {
    val context = LocalContext.current
    var prompt by remember { mutableStateOf("") }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        onSelectImageUri(uri)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Launcher Card
        item {
            ObsidianCard {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Filled.Hub, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(
                        text = "Sistema Multiagente Coordenado",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "A Reasoning AI planeja, delega à Vision AI e Coding AI em troca segura de memória e audita o resultado final.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("Descreva a tarefa complexa para as três IAs...") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("multiagent_prompt_input")
                )

                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = {
                            imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                    ) {
                        Icon(Icons.Filled.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (selectedImageUri == null) "Anexar Imagem" else "Trocar Imagem")
                    }
                    if (selectedImageUri != null) {
                        OutlinedButton(onClick = { onSelectImageUri(null) }) {
                            Text("Remover")
                        }
                    }
                }

                if (selectedImageUri != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    AsyncImage(
                        model = selectedImageUri,
                        contentDescription = "Imagem anexada à tarefa multiagente",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                            .clip(MaterialTheme.shapes.small),
                        contentScale = ContentScale.Crop
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = { onRunTask(prompt) },
                    enabled = !liveState.isRunning && prompt.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("multiagent_execute_button")
                ) {
                    if (liveState.isRunning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (liveState.isRunning) liveState.currentPhase else "Executar Orquestração Multiagente")
                }

                if (liveState.isRunning) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onCancelTask,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("multiagent_cancel_button")
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp), tint = StatusDanger)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Cancelar Orquestração", color = StatusDanger)
                    }
                }
            }
        }

        // Live Execution Trace
        if (liveState.currentTaskId != null) {
            item {
                ObsidianCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Execução Atual (${liveState.currentTaskId})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        StatusPill(
                            label = if (liveState.isRunning) liveState.currentPhase else "Revisões: ${liveState.revisionCount}",
                            color = if (liveState.error != null) StatusDanger else if (liveState.isRunning) MultiAgentAmber else StatusOnline
                        )
                    }

                    if (liveState.originalRequest.isNotBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Solicitação: ${liveState.originalRequest}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    liveState.plan?.let { plan ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Planejamento: ${plan.goalSummary}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = ReasoningIndigo
                        )
                        Text(
                            text = "Agentes selecionados: Reasoning${if (plan.useVision) " + Vision" else ""}${if (plan.useCoding) " + Coding" else ""}",
                            style = MaterialTheme.typography.labelMedium,
                            color = CodingCyan
                        )
                    }

                    liveState.error?.let { err ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Erro: $err",
                            style = MaterialTheme.typography.bodySmall,
                            color = StatusDanger
                        )
                    }
                }
            }

            items(liveState.steps, key = { it.stepIndex }) { step ->
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
                            Icon(
                                imageVector = if (step.passedReview) Icons.Filled.CheckCircle else Icons.Filled.Refresh,
                                contentDescription = null,
                                tint = if (step.passedReview) StatusOnline else MultiAgentAmber,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "#${step.stepIndex} ${step.phase} • ${step.agentRole}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Text(
                            text = "${step.durationMs} ms",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Instrução: ${step.instruction}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = step.outputSummary,
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (!step.revisionNote.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Revisão solicitada: ${step.revisionNote}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MultiAgentAmber
                        )
                    }
                }
            }

            if (liveState.finalOutput.isNotBlank()) {
                item {
                    ObsidianCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Resposta Final Consolidada",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = StatusOnline
                            )
                            IconButton(
                                onClick = {
                                    copyToClipboard(context, "Resposta Multiagente", liveState.finalOutput) {
                                        onBannerMessage("Resposta multiagente copiada.")
                                    }
                                },
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = "Copiar resposta consolidada")
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = liveState.finalOutput,
                            fontFamily = MonoFontFamily,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        // Historical Multi-Agent Runs
        item {
            SectionHeader(title = "HISTÓRICO MULTIAGENTE (${recentRuns.size})")
        }

        if (recentRuns.isEmpty() && liveState.currentTaskId == null) {
            item {
                ObsidianCard {
                    Text(
                        text = "Nenhuma execução multiagente registrada ainda. Fluxo automático: 1) Reasoning planeja -> 2) Vision analisa imagens (se houver) -> 3) Coding gera código -> 4) Reasoning audita e consolida.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        items(recentRuns, key = { it.taskId }) { run ->
            val ok = run.status == "COMPLETED"
            ObsidianCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusPill(
                        label = "${run.source} • ${run.status}",
                        color = if (ok) StatusOnline else StatusDanger
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${run.totalDurationMs} ms • ${run.revisionCount} rev.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (run.finalResponse.isNotBlank()) {
                            IconButton(
                                onClick = {
                                    copyToClipboard(context, "Execução Multiagente", run.finalResponse) {
                                        onBannerMessage("Resultado copiado.")
                                    }
                                },
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = "Copiar resultado histórico", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = run.originalRequest,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                if (!run.errorMessage.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = StatusDanger, modifier = Modifier.size(14.dp))
                        Text(run.errorMessage, style = MaterialTheme.typography.bodySmall, color = StatusDanger)
                    }
                } else if (run.finalResponse.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = run.finalResponse.take(300),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

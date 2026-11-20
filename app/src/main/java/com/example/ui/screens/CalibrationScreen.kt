package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.core.model.AgentRole
import com.example.ui.CalibrationUiState

@Composable
fun CalibrationScreen(
    state: CalibrationUiState,
    durations: Map<AgentRole, Int>,
    modes: Map<AgentRole, CalibrationMode>,
    onModeChange: (AgentRole, CalibrationMode) -> Unit,
    onDurationChange: (AgentRole, Int) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val agents = listOf(
        Triple(AgentRole.VISION, "Análise de interface, hierarquia visual, efeitos e comparação de sistemas.", 1..60),
        Triple(AgentRole.REASONING, "Lógica, planejamento, verificação de premissas e casos-limite.", 1..90),
        Triple(AgentRole.CODING, "Depuração, correção de bugs, sistemas operacionais e shells.", 1..120)
    )
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("AI Calibration Lab", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Escolha o tempo máximo de cada agente. O aplicativo calibra um modelo por vez e encerra cada etapa ao concluir os exercícios ou atingir o limite escolhido.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        agents.forEach { (role, detail, range) ->
            val minutes = durations[role] ?: 20
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(role.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("• " + minutes + " min", color = MaterialTheme.colorScheme.primary)
                    }
                    Text(detail, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CalibrationMode.entries.forEach { option ->
                            if (mode == option) {
                                Button(onClick = { onModeChange(role, option) }, enabled = !state.isRunning) {
                                    Text(option.title)
                                }
                            } else {
                                OutlinedButton(onClick = { onModeChange(role, option) }, enabled = !state.isRunning) {
                                    Text(option.title)
                                }
                            }
                        }
                    }
                    Text(mode.description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (mode == CalibrationMode.ULTIMATE) {
                        Text("Duração fixa: 5 horas", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    } else {
                        Slider(
                            value = minutes.toFloat(),
                            onValueChange = { onDurationChange(role, it.toInt().coerceIn(range.first, range.last)) },
                            valueRange = range.first.toFloat()..range.last.toFloat(),
                            steps = range.last - range.first - 1,
                            enabled = !state.isRunning
                        )
                        Text("De " + range.first + " a " + range.last + " minutos", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (state.isRunning) {
            Text(state.currentCase, fontWeight = FontWeight.SemiBold)
            LinearProgressIndicator(
                progress = { if (state.totalCases == 0) 0f else state.completedCases.toFloat() / state.totalCases },
                modifier = Modifier.fillMaxWidth()
            )
            Text(state.completedCases.toString() + " / " + state.totalCases + " exercícios")
            if (state.latestOutput.isNotBlank()) {
                Card(Modifier.fillMaxWidth()) {
                    Text(state.latestOutput, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
            OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("Interromper calibração") }
        } else {
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("Iniciar calibração completa") }
        }
        state.error?.let { Text("Aviso: " + it, color = MaterialTheme.colorScheme.error) }
        if (state.report.isNotBlank()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("Relatório da sessão", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(state.report, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Text(
            "O modo Ultimate reserva 5 horas para cada IA selecionada. O tempo é um limite, não uma garantia de que o Android manterá o processo ativo. A calibração usa exemplos e instruções; não altera os pesos dos modelos.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

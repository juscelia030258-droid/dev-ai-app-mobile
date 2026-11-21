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
import com.example.ui.CalibrationMode
import com.example.ui.CalibrationUiState

@Composable
fun CalibrationScreen(
    state: CalibrationUiState,
    durations: Map<AgentRole, Int>,
    modes: Map<AgentRole, CalibrationMode>,
    onModeChange: (AgentRole, CalibrationMode) -> Unit,
    onDurationChange: (AgentRole, Int) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onResume: () -> Unit,
    onDiscardCheckpoint: () -> Unit,
    onBenchmark: () -> Unit
) {
    val agents = listOf(
        Triple(AgentRole.CODING, "Firmware, menus funcionais, configurações, Kotlin, Python, depuração e Linux no Ultimate.", 1..120),
        Triple(AgentRole.REASONING, "Lógica, tecnologia, investigação, planejamento e colaboração entre agentes.", 1..90),
        Triple(AgentRole.VISION, "Interfaces, leitura visual, relações espaciais e revisão de imagens.", 1..60)
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
            val mode = modes[role] ?: CalibrationMode.NORMAL
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(role.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "• " + if (mode == CalibrationMode.ULTIMATE) "5 horas" else "$minutes min",
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(detail, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CalibrationMode.entries.forEach { option ->
                            if (mode == option) {
                                Button(onClick = { onModeChange(role, option) }, enabled = !state.isRunning && !state.isBenchmarkRunning) {
                                    Text(option.title)
                                }
                            } else {
                                OutlinedButton(onClick = { onModeChange(role, option) }, enabled = !state.isRunning && !state.isBenchmarkRunning) {
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
                            enabled = !state.isRunning && !state.isBenchmarkRunning
                        )
                        Text("De " + range.first + " a " + range.last + " minutos", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (state.isBenchmarkRunning) {
            Text("Benchmark separado — " + state.benchmarkProgress + " / 9 casos", fontWeight = FontWeight.SemiBold)
            LinearProgressIndicator(progress = { (state.benchmarkProgress.toFloat() / 9f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            state.thermalMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
        state.benchmarkError?.let {
            Text("Benchmark: " + it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (state.isRunning) {
            Text(state.currentCase, fontWeight = FontWeight.SemiBold)
            if (state.totalCases > 0) {
                LinearProgressIndicator(
                    progress = { (state.completedCases.toFloat() / state.totalCases).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(state.completedCases.toString() + " / " + state.totalCases + " exercícios")
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(state.completedCases.toString() + " exercícios concluídos — sessão Ultimate")
            }
            if (state.latestOutput.isNotBlank()) {
                Card(Modifier.fillMaxWidth()) {
                    Text(state.latestOutput, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
            state.thermalMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("Pausar e salvar progresso") }
        } else if (!state.isBenchmarkRunning) {
            if (state.canResume) {
                Text(
                    state.currentCase.ifBlank { "Existe uma sessão SACE que pode ser retomada." },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Button(onClick = onResume, modifier = Modifier.fillMaxWidth()) { Text("Retomar sessão salva") }
                OutlinedButton(onClick = onDiscardCheckpoint, modifier = Modifier.fillMaxWidth()) { Text("Descartar sessão salva") }
            }
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth(), enabled = !state.canResume) {
                Text(if (state.canResume) "Descarte a sessão para iniciar outra" else "Iniciar calibração completa")
            }
        }
        if (state.benchmarkReport.isNotBlank()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("Resultado do benchmark", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(state.benchmarkReport, style = MaterialTheme.typography.bodySmall)
                }
            }
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
        OutlinedButton(onClick = onBenchmark, modifier = Modifier.fillMaxWidth(), enabled = !state.isRunning && !state.isBenchmarkRunning) { Text("Executar benchmark (9 testes)") }
        Text(
            "O modo Ultimate reserva 5 horas para cada IA selecionada. O tempo é um limite, não uma garantia de que o Android manterá o processo ativo. A calibração usa exemplos e instruções; não altera os pesos dos modelos.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

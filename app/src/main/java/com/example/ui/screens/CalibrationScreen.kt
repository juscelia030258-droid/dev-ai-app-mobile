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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("AI Calibration Lab", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Bateria local de exercícios para avaliar cada agente em sequência, um modelo por vez. Os exemplos aprovados são anexados ao prompt especializado como referências persistentes.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        listOf(
            Triple(AgentRole.VISION, "20 min", "Análise de interface, hierarquia visual, efeitos e comparação de sistemas."),
            Triple(AgentRole.REASONING, "45 min", "Lógica, planejamento, verificação de premissas e casos-limite."),
            Triple(AgentRole.CODING, "60 min", "Depuração de código, correção de bugs e identificação de shells e sistemas.")
        ).forEach { (role, duration, detail) ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(role.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("• " + duration, color = MaterialTheme.colorScheme.primary)
                    }
                    Text(detail, style = MaterialTheme.typography.bodySmall)
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
        state.error?.let {
            Text("Aviso: " + it, color = MaterialTheme.colorScheme.error)
        }
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
            "Importante: esta versão não altera os pesos dos modelos. Ela testa as respostas e salva exemplos no prompt de cada agente. A duração mostrada é a janela planejada, não uma promessa de manter o processador ocupado por todo esse tempo.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

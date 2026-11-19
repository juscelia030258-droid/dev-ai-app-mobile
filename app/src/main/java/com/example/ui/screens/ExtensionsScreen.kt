package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ExtensionsScreen(enabled: Set<String>, onToggle: (String, Boolean) -> Unit) {
    val entries = listOf(
        Triple("developer_toolkit", "Developer Toolkit", "Análise de projetos, dependências e depuração."),
        Triple("long_memory", "Long Memory", "Recuperação de decisões e contexto persistente."),
        Triple("web_research", "Web Research", "Pesquisa pública pelo Private Browser, somente leitura."),
        Triple("math_engine", "Math Engine", "Apoio a cálculos e conversões."),
        Triple("system_tools", "System Tools", "Diagnóstico de RAM, modelos e dispositivo."),
        Triple("code_tester", "Code Tester", "Checklist e análise de testes; não executa código arbitrário.")
    )
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Extension Manager", style = MaterialTheme.typography.headlineSmall)
                Text("Ative as ferramentas necessárias. As extensões não carregam modelos adicionais.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(entries, key = { it.first }) { entry ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(entry.second, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Switch(checked = entry.first in enabled, onCheckedChange = { onToggle(entry.first, it) })
                    }
                    Text(entry.third, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

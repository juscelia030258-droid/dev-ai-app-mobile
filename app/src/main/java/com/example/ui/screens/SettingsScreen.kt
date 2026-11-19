package com.example.ui.screens

import android.content.Intent
import android.provider.Settings
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.core.model.DiagnosticCheckResult
import com.example.memory.MemoryStatusState
import com.example.settings.AppSettings
import com.example.ui.components.ObsidianCard
import com.example.ui.components.SectionHeader
import com.example.ui.components.SettingsSliderRow
import com.example.ui.components.SettingsToggleRow
import com.example.ui.components.StatusPill
import com.example.ui.theme.CodingCyan
import com.example.ui.theme.StatusDanger
import com.example.ui.theme.StatusOnline
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    settings: AppSettings,
    memoryState: MemoryStatusState,
    diagnostics: List<DiagnosticCheckResult>,
    isRunningDiagnostics: Boolean,
    onUpdatePort: (Int) -> Unit,
    onUpdateRamLimit: (Long) -> Unit,
    onUpdateLanOnly: (Boolean) -> Unit,
    onUpdateRateLimit: (Int) -> Unit,
    onUpdateMaxRevisions: (Int) -> Unit,
    onUpdateTimeout: (Int) -> Unit,
    onUpdateAutoUnload: (Boolean) -> Unit,
    onUpdateIdleUnloadMinutes: (Int) -> Unit,
    onUpdateThermalGuard: (Boolean) -> Unit,
    onUpdateDefaultMaxTokens: (Int) -> Unit,
    onUpdateAutoStartServer: (Boolean) -> Unit,
    onUpdateDarkTheme: (Boolean) -> Unit,
    onClearCaches: () -> Unit,
    onResetDefaults: () -> Unit,
    onRunDiagnostics: () -> Unit
) {
    val context = LocalContext.current
    var portInput by remember(settings.serverPort) { mutableStateOf(settings.serverPort.toString()) }
    var confirmResetDialog by remember { mutableStateOf(false) }
    val parsedPort = portInput.toIntOrNull()
    val isPortValid = parsedPort != null && parsedPort in 1024..65535

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("settings_lazy_column"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Section 1: Hardware & Runtime
        item {
            SectionHeader(title = "HARDWARE & RUNTIME")
        }

        item {
            ObsidianCard {
                Text(
                    text = "RAM do modelo ativo & Estabilidade",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Física: ${memoryState.profile.totalRamMb} MB • Orçamento seguro: ${memoryState.profile.safeInferenceBudgetMb} MB",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))

                SettingsSliderRow(
                    label = "Teto de RAM para o modelo ativo",
                    valueText = "${settings.ramLimitMb} MB",
                    value = settings.ramLimitMb.toFloat(),
                    onValueChange = { onUpdateRamLimit(((it / 128f).roundToInt() * 128L)) },
                    valueRange = 1024f..6144f,
                    helperText = "As IAs são carregadas uma por vez. Ao trocar de agente, o modelo anterior é descarregado; o limite não soma a RAM dos três modelos. A proteção dinâmica do Android continua ativa."
                )

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(12.dp))

                SettingsToggleRow(
                    title = "Descarregar modelos ociosos (${settings.idleUnloadMinutes} min)",
                    subtitle = "Libera automaticamente a RAM quando nenhuma inferência ocorre após o tempo definido.",
                    checked = settings.autoUnloadIdleModels,
                    onCheckedChange = onUpdateAutoUnload,
                    switchModifier = Modifier.testTag("auto_unload_switch")
                )

                AnimatedVisibility(visible = settings.autoUnloadIdleModels) {
                    Column(modifier = Modifier.padding(top = 12.dp)) {
                        SettingsSliderRow(
                            label = "Tempo de ociosidade",
                            valueText = "${settings.idleUnloadMinutes} min",
                            value = settings.idleUnloadMinutes.toFloat(),
                            onValueChange = { onUpdateIdleUnloadMinutes(it.roundToInt().coerceIn(1, 30)) },
                            valueRange = 1f..30f,
                            modifier = Modifier.testTag("idle_unload_minutes_slider")
                        )
                    }
                }
            }
        }

        item {
            ObsidianCard {
                SettingsToggleRow(
                    title = "Proteção térmica automática",
                    subtitle = "Status atual: ${memoryState.profile.thermalStatusLabel}. Bloqueia novas inferências em nível severo ou crítico.",
                    checked = settings.thermalGuardEnabled,
                    onCheckedChange = onUpdateThermalGuard,
                    switchModifier = Modifier.testTag("thermal_guard_switch")
                )
            }
        }

        // Section 2: Network, API & Startup
        item {
            SectionHeader(title = "SERVIDOR API & REDE LAN")
        }

        item {
            ObsidianCard {
                Text(
                    text = "Rede, Porta & Segurança LAN",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = portInput,
                        onValueChange = { portInput = it.filter { ch -> ch.isDigit() }.take(5) },
                        label = { Text("Porta HTTP (1024–65535)") },
                        isError = !isPortValid,
                        supportingText = if (!isPortValid) {
                            { Text("Informe uma porta válida entre 1024 e 65535") }
                        } else null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("settings_port_input")
                    )
                    Button(
                        onClick = {
                            parsedPort?.takeIf { isPortValid }?.let(onUpdatePort)
                        },
                        enabled = isPortValid,
                        modifier = Modifier.testTag("settings_port_apply_button")
                    ) {
                        Text("Aplicar")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                SettingsToggleRow(
                    title = "Restringir à Rede Local (LAN)",
                    subtitle = "Aceita apenas IPs privados Wi-Fi (192.168.x.x, 10.x.x.x, 172.16-31.x.x) e loopback.",
                    checked = settings.lanOnlyMode,
                    onCheckedChange = onUpdateLanOnly
                )
            }
        }

        item {
            ObsidianCard {
                SettingsToggleRow(
                    title = "Iniciar servidor ao abrir o app",
                    subtitle = "Inicia automaticamente o endpoint OpenAI na porta configurada ao abrir o aplicativo.",
                    checked = settings.autoStartServerOnLaunch,
                    onCheckedChange = onUpdateAutoStartServer,
                    switchModifier = Modifier.testTag("auto_start_server_switch")
                )

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(12.dp))

                SettingsSliderRow(
                    label = "Rate limit por chave",
                    valueText = "${settings.rateLimitPerMinute} req/min",
                    value = settings.rateLimitPerMinute.toFloat(),
                    onValueChange = { onUpdateRateLimit((it / 5f).roundToInt() * 5) },
                    valueRange = 10f..240f
                )
            }
        }

        // Section 3: Inference Defaults, Multi-Agent & Appearance
        item {
            SectionHeader(title = "INFERÊNCIA, MULTIAGENTE & INTERFACE")
        }

        item {
            ObsidianCard {
                Text(
                    text = "Parâmetros Globais & Multiagente",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(12.dp))

                SettingsSliderRow(
                    label = "Limite padrão de tokens na API",
                    valueText = "${settings.defaultMaxTokens} tokens",
                    value = settings.defaultMaxTokens.toFloat(),
                    onValueChange = { onUpdateDefaultMaxTokens(((it / 256f).roundToInt() * 256).coerceIn(256, 4096)) },
                    valueRange = 256f..4096f,
                    helperText = "Aplicado a chamadas OpenAI sem o campo max_tokens explícito.",
                    modifier = Modifier.testTag("default_max_tokens_slider")
                )

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(12.dp))

                SettingsSliderRow(
                    label = "Máximo de revisões críticas multiagente",
                    valueText = "${settings.maxMultiAgentRevisions}",
                    value = settings.maxMultiAgentRevisions.toFloat(),
                    onValueChange = { onUpdateMaxRevisions(it.roundToInt()) },
                    valueRange = 0f..4f,
                    steps = 3
                )

                Spacer(modifier = Modifier.height(8.dp))
                SettingsSliderRow(
                    label = "Timeout máximo multiagente",
                    valueText = "${settings.multiAgentTimeoutSec}s",
                    value = settings.multiAgentTimeoutSec.toFloat(),
                    onValueChange = { onUpdateTimeout(((it / 30f).roundToInt() * 30)) },
                    valueRange = 60f..600f
                )
            }
        }

        item {
            ObsidianCard {
                SettingsToggleRow(
                    title = "Tema Obsidian Escuro",
                    subtitle = "Alterna entre o modo escuro corporativo e o tema claro Alabaster.",
                    checked = settings.darkThemeEnabled,
                    onCheckedChange = onUpdateDarkTheme
                )
            }
        }

        // Section 4: Background Execution & Maintenance
        item {
            SectionHeader(title = "SISTEMA & MANUTENÇÃO")
        }

        item {
            ObsidianCard {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Filled.BatteryChargingFull, contentDescription = null, tint = CodingCyan)
                    Text(
                        text = "Segundo Plano & Bateria (Android)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "O servidor usa Foreground Service (specialUse) para permanecer ativo com o app minimizado. Em dispositivos Samsung (One UI), defina a bateria do app como 'Irrestrita' para evitar hibernação agressiva do sistema.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Abrir Configurações de Bateria do Android")
                }
            }
        }

        item {
            ObsidianCard {
                Text(
                    text = "Limpeza & Restauração",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Remova imagens temporárias em cache ou restaure todas as configurações para os valores padrão.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onClearCaches,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("clear_cache_button")
                    ) {
                        Icon(Icons.Filled.CleaningServices, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Limpar Cache")
                    }
                    OutlinedButton(
                        onClick = { confirmResetDialog = true },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("reset_defaults_button")
                    ) {
                        Icon(Icons.Filled.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp), tint = StatusDanger)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Restaurar Padrões", color = StatusDanger)
                    }
                }
            }
        }

        // Section 5: Real On-Device Diagnostics Runner
        item {
            ObsidianCard {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Filled.BugReport, contentDescription = null, tint = StatusOnline)
                    Text(
                        text = "Diagnóstico & Autoteste Real",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Executa verificações reais de Keystore, persistência de chave, guarda de memória, validador GGUF, bloqueio LAN e autenticação HTTP loopback.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onRunDiagnostics,
                    enabled = !isRunningDiagnostics,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("run_diagnostics_button")
                ) {
                    if (isRunningDiagnostics) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Executando verificações...")
                    } else {
                        Text("Executar Bateria de Diagnóstico Real")
                    }
                }
            }
        }

        if (diagnostics.isNotEmpty()) {
            item {
                val passedCount = diagnostics.count { it.passed }
                val allPassed = passedCount == diagnostics.size
                SectionHeader(
                    title = "RESULTADOS DO DIAGNÓSTICO",
                    trailing = {
                        StatusPill(
                            label = "$passedCount/${diagnostics.size} OK",
                            color = if (allPassed) StatusOnline else StatusDanger
                        )
                    }
                )
            }
        }

        items(diagnostics, key = { it.name }) { check ->
            ObsidianCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = if (check.passed) Icons.Filled.CheckCircle else Icons.Filled.Error,
                            contentDescription = null,
                            tint = if (check.passed) StatusOnline else StatusDanger,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(check.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    }
                    StatusPill(
                        label = "${check.durationMs} ms",
                        color = if (check.passed) StatusOnline else StatusDanger
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = check.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Known Limitations & Compatibility Documentation
        item {
            ObsidianCard {
                Text(
                    text = "Compatibilidade & Arquitetura Local",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = """
                        • OpenAI API suportada: GET /v1/models e POST /v1/chat/completions (streaming SSE e resposta completa, incluindo image_url data URI base64).
                        • Execução 100% offline: os agentes operam exclusivamente com os modelos locais instalados, sem acesso a navegador web externo e sem treinamento/alteração de pesos em runtime (adapte o comportamento via System Prompt ou troca de modelo).
                        • Execução sequencial em 8 GB RAM: apenas um modelo é mantido carregado na RAM por vez no Galaxy A56; trocas no fluxo multiagente descarregam o modelo anterior preservando o contexto estruturado.
                        • Visão computacional real: exige modelo multimodal (.gguf com mmproj ou .litertlm multimodal). Modelos textuais são bloqueados na Vision AI.
                    """.trimIndent(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (confirmResetDialog) {
        AlertDialog(
            onDismissRequest = { confirmResetDialog = false },
            title = { Text("Restaurar Padrões?") },
            text = { Text("Todas as configurações globais de porta, limite de RAM, proteção térmica e multiagente retornarão aos valores padrão. Suas chaves API e modelos instalados não serão removidos.") },
            confirmButton = {
                Button(
                    onClick = {
                        confirmResetDialog = false
                        onResetDefaults()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StatusDanger)
                ) {
                    Text("Restaurar")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmResetDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
    }
}

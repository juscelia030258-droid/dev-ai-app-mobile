package com.example.ui

import com.example.core.model.AgentRole

enum class CalibrationMode(val title: String, val description: String) {
    NORMAL("Normal", "Fundamentos, exemplos e exercícios graduais"),
    ADVANCED("Avançado", "Projetos completos, integração e desafios mais difíceis"),
    ANALYSIS("Análise", "Investigar problemas, separar fatos de hipóteses e localizar causas"),
    CORRECTION("Correção", "Encontrar erros, corrigir soluções e explicar as mudanças"),
    ULTIMATE("Ultimate", "Desafio especial de 5 horas, dividido em cinco etapas")
}

data class CalibrationUiState(
    val isRunning: Boolean = false,
    val currentRole: AgentRole? = null,
    val completedCases: Int = 0,
    val totalCases: Int = 9,
    val currentCase: String = "",
    val latestOutput: String = "",
    val report: String = "",
    val error: String? = null,
    val isPaused: Boolean = false,
    val canResume: Boolean = false,
    val thermalMessage: String? = null,
    val isBenchmarkRunning: Boolean = false,
    val benchmarkProgress: Int = 0,
    val benchmarkReport: String = "",
    val benchmarkError: String? = null,
    val currentElapsedMs: Long = 0L,
    val targetDurationMs: Long = 0L
)

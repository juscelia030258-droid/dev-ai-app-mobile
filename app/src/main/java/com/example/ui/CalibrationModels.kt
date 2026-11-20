package com.example.ui

import com.example.core.model.AgentRole

data class CalibrationUiState(
    val isRunning: Boolean = false,
    val currentRole: AgentRole? = null,
    val completedCases: Int = 0,
    val totalCases: Int = 9,
    val currentCase: String = "",
    val latestOutput: String = "",
    val report: String = "",
    val error: String? = null
)

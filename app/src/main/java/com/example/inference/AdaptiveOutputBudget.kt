package com.example.inference

import com.example.core.model.AgentRole

/**
 * Treats the configured output limit as a default, not an absolute ceiling.
 * The budget grows only for tasks that benefit from a longer answer and only
 * when the configured context window has reasonable remaining headroom.
 */
object AdaptiveOutputBudget {
    private const val HARD_RUNTIME_LIMIT = 4096
    private const val CONTEXT_SAFETY_RESERVE = 512

    fun choose(
        role: AgentRole,
        messages: List<ChatTurn>,
        configuredMaxTokens: Int,
        contextLength: Int
    ): Int {
        val base = configuredMaxTokens.coerceIn(64, HARD_RUNTIME_LIMIT)
        if (role == AgentRole.VISION) return base.coerceAtMost(320)

        val request = messages.lastOrNull { it.role.equals("user", ignoreCase = true) }
            ?.text.orEmpty().lowercase()
        val codeTask = listOf(
            "código", "codigo", "implemente", "implementar", "program", "debug", "depurar",
            "corrija", "corrigir", "conserte", "arrume", "crie", "desenvolva", "construir", "refator", "classe", "função", "funcao", "script",
            "arquivo completo", "projeto", "api", "kotlin", "python", "c++", "html", "sql"
        ).any(request::contains) || request.contains("```")

        val detailedTask = codeTask || listOf(
            "explique detalhadamente", "explicação completa", "explicacao completa",
            "passo a passo", "análise completa", "analise completa", "compare detalhadamente",
            "em detalhes", "mais detalhes", "resposta completa", "explique", "explica", "detalhe", "descreva", "por que", "como funciona"
        ).any(request::contains)

        if (!detailedTask) return base

        val multiplier = if (codeTask) 2.5f else 2.0f
        val desired = (base * multiplier).toInt().coerceAtMost(HARD_RUNTIME_LIMIT)
        val estimatedInputTokens = messages.sumOf { (it.text.length + 3) / 4 }
        val available = (contextLength.coerceIn(512, 8192) -
            estimatedInputTokens - CONTEXT_SAFETY_RESERVE).coerceAtLeast(base)

        return maxOf(base, minOf(desired, available, HARD_RUNTIME_LIMIT))
    }
}

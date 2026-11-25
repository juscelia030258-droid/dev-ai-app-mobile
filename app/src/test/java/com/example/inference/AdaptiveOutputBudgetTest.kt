package com.example.inference

import com.example.core.model.AgentRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveOutputBudgetTest {
    @Test
    fun keepsConfiguredLimitForSimpleQuestions() {
        val result = AdaptiveOutputBudget.choose(
            role = AgentRole.CODING,
            messages = listOf(ChatTurn("user", "Qual é a capital do Brasil?")),
            configuredMaxTokens = 1024,
            contextLength = 8192
        )

        assertEquals(1024, result)
    }

    @Test
    fun expandsBudgetForCompleteCodeTasks() {
        val result = AdaptiveOutputBudget.choose(
            role = AgentRole.CODING,
            messages = listOf(ChatTurn("user", "Crie um arquivo completo em Kotlin com implementação e testes.")),
            configuredMaxTokens = 1024,
            contextLength = 8192
        )

        assertTrue(result > 1024)
        assertEquals(2560, result)
    }

    @Test
    fun doesNotExceedAvailableContextHeadroom() {
        val result = AdaptiveOutputBudget.choose(
            role = AgentRole.REASONING,
            messages = listOf(ChatTurn("user", "Explique detalhadamente como funciona este sistema.")),
            configuredMaxTokens = 1024,
            contextLength = 1800
        )

        assertTrue(result <= 1800 - ((messagesLength("Explique detalhadamente como funciona este sistema.") + 3) / 4) - 512)
        assertTrue(result >= 1024)
    }

    @Test
    fun keepsVisionResponsesCompact() {
        val result = AdaptiveOutputBudget.choose(
            role = AgentRole.VISION,
            messages = listOf(ChatTurn("user", "Analise esta interface.")),
            configuredMaxTokens = 1024,
            contextLength = 8192
        )

        assertEquals(320, result)
    }

    private fun messagesLength(text: String): Int = text.length
}

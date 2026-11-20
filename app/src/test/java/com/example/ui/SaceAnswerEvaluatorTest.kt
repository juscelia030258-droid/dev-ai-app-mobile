package com.example.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SaceAnswerEvaluatorTest {
    @Test
    fun parsesBoundedReviewJson() {
        val result = SaceAnswerEvaluator.parse(
            """{"score":82,"confidence":75,"feedback":"Atendeu ao pedido e explicou os limites."}"""
        )
        assertNotNull(result)
        assertEquals(82, result?.score)
        assertEquals(75, result?.confidence)
    }

    @Test
    fun rejectsMissingOrOutOfRangeScores() {
        assertNull(SaceAnswerEvaluator.parse("""{"score":120,"confidence":80,"feedback":"Inválido"}"""))
        assertNull(SaceAnswerEvaluator.parse("Não foi possível avaliar."))
    }

    @Test
    fun promptExplainsThatVisionReviewHasNoImage() {
        val prompt = SaceAnswerEvaluator.buildPrompt("Vision AI", "Descreva a tela", "Há dois botões.")
        assertEquals(true, prompt.contains("imagem anexada"))
        assertEquals(true, prompt.contains("correção técnica/lógica"))
    }
}

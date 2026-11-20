package com.example.ui

import org.json.JSONObject

data class SaceEvaluation(val score: Int, val confidence: Int, val feedback: String)

/**
 * Parses a second-pass model review. This is a quality signal, not proof of correctness:
 * the reviewer may share the evaluated model's blind spots.
 */
object SaceAnswerEvaluator {
    fun buildPrompt(agent: String, exercise: String, answer: String): String = """
        Você é o revisor do SACE. Avalie a resposta usando somente o exercício e a resposta.
        Não elogie por educação. Não invente fatos nem presuma que código foi compilado ou executado.
        Dê nota de 0 a 100 considerando: atendimento ao pedido (30), correção técnica/lógica (35),
        evidências e reconhecimento de limites (20), clareza e ausência de repetição (15).
        Para Vision AI, não há imagem disponível nesta revisão: avalie apenas se a resposta respeita
        limites, separa observação de suposição e evita afirmar detalhes não comprovados.
        Agente: $agent
        EXERCÍCIO:
        ${exercise.take(1800)}
        RESPOSTA:
        ${answer.take(4200)}
        Responda SOMENTE com JSON válido:
        {"score":0,"confidence":0,"feedback":"comentário curto em português"}
        score e confidence são inteiros de 0 a 100. Feedback com no máximo 240 caracteres.
    """.trimIndent()

    fun parse(raw: String): SaceEvaluation? = runCatching {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        require(start >= 0 && end > start)
        val json = JSONObject(raw.substring(start, end + 1))
        val score = json.optInt("score", -1)
        val confidence = json.optInt("confidence", -1)
        require(score in 0..100 && confidence in 0..100)
        SaceEvaluation(score, confidence, json.optString("feedback").take(240))
    }.getOrNull()
}

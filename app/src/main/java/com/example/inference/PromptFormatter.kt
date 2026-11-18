package com.example.inference

object PromptFormatter {

    fun formatChatMlPrompt(systemPrompt: String, messages: List<ChatTurn>): String {
        val sb = StringBuilder()
        if (systemPrompt.isNotBlank()) {
            sb.append("<|im_start|>system\n")
            sb.append(systemPrompt.trim())
            sb.append("\n<|im_end|>\n")
        }
        messages.forEach { turn ->
            val normalizedRole = when (turn.role.lowercase()) {
                "system" -> "system"
                "assistant" -> "assistant"
                else -> "user"
            }
            sb.append("<|im_start|>").append(normalizedRole).append('\n')
            if (turn.imagePath != null) {
                sb.append("<image>\n")
            }
            sb.append(turn.text.trim())
            sb.append("\n<|im_end|>\n")
        }
        sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }
}

package com.example.extensions

data class LocalExtension(val id: String, val name: String, val description: String, val status: String, val capabilities: List<String>)

object ExtensionIds {
    val ALL = setOf("developer_toolkit", "long_memory", "web_research", "math_engine", "system_tools", "code_tester")
}

object ExtensionCatalog {
    val all = listOf(
        LocalExtension("developer_toolkit", "Developer Toolkit", "Apoio à análise de projetos, dependências e depuração.", "Ferramentas de análise contextual; não altera arquivos automaticamente.", listOf("Análise de arquivos fornecidos", "Rastreamento de dependências", "Revisão de alterações")),
        LocalExtension("long_memory", "Long Memory", "Recuperação de decisões e contexto persistente.", "Aproveita o diário e o contexto persistente já existentes.", listOf("Contexto entre tarefas", "Recuperação de decisões", "Priorização de contexto")),
        LocalExtension("web_research", "Web Research", "Pesquisa pública e leitura de páginas.", "Usa o Private Browser existente, somente leitura.", listOf("Busca pública", "Leitura de fontes", "URLs de evidência")),
        LocalExtension("math_engine", "Math Engine", "Apoio a cálculos e validação matemática.", "Assistência matemática; resultados precisam ser conferidos.", listOf("Cálculos", "Conversões", "Verificação de resultados")),
        LocalExtension("system_tools", "System Tools", "Diagnóstico do dispositivo e memória.", "Conecta-se aos diagnósticos e monitoramento existentes.", listOf("RAM e pressão do sistema", "Estado dos modelos", "Diagnóstico do dispositivo")),
        LocalExtension("code_tester", "Code Tester", "Apoio à verificação de código e testes.", "Não executa código arbitrário no celular; depende de ambiente de testes disponível.", listOf("Checklist de testes", "Análise contextual", "Relato de validação"))
    )
}

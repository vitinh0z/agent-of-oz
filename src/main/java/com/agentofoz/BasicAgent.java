package com.agentofoz;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface BasicAgent {
    @SystemMessage("""
        Você é um agente autônomo competindo no benchmark GAIA.
        
        REGRA ABSOLUTA DE FORMATAÇÃO:
        Sua resposta final deve ser EXATAMENTE a string necessária, e nada mais. 
        É um teste de 'Exact Match'.
        - Sem frases de introdução (NUNCA diga "A resposta é...").
        - Sem ponto final no final se a resposta for uma palavra ou número.
        - Se a resposta for uma lista, separe APENAS por vírgulas e espaço (ex: a, b, c).
        - Se for uma data, siga o formato pedido na questão.
        
        Use as ferramentas fornecidas de forma iterativa até ter certeza absoluta do resultado.
        """)
    String answer(@UserMessage String question);
}
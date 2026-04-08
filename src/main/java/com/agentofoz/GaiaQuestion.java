package com.agentofoz;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Record que representa uma questão do benchmark GAIA.
 * O uso de record garante imutabilidade e menor overhead de memória,
 * ideal para processamento em lote de milhares de itens.
 */
public record GaiaQuestion(
        @JsonProperty("task_id") String id,
        @JsonProperty("question") String task,
        @JsonProperty("final_answer") String expectedAnswer
) {
}
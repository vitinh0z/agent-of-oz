package com.agentofoz;

import com.google.common.util.concurrent.RateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class EvaluationService {

    private final BasicAgent agent;
    private final WebClient webClient;
    private static final String SCORING_API_URL = "https://agents-course-unit4-scoring.hf.space";

    @SuppressWarnings("UnstableApiUsage")
    private final RateLimiter rateLimiter = RateLimiter.create(1.0 / 12.0);

    public EvaluationService(BasicAgent agent) {
        this.agent = agent;
        this.webClient = WebClient.builder()
                .baseUrl(SCORING_API_URL)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    private List<GaiaQuestion> fetchQuestions() {
        log.info("Buscando questões da API: {}/questions", SCORING_API_URL);
        
        List<GaiaQuestion> questions = webClient.get()
                .uri("/questions")
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<GaiaQuestion>>() {})
                .block(); 
                
        if (questions == null || questions.isEmpty()) {
            throw new RuntimeException("A lista de questões retornada pela API está vazia ou nula.");
        }
        
        log.info("Foram carregadas {} questões.", questions.size());
        return questions;
    }

    private void submitAnswers(String username, ConcurrentLinkedQueue<Map<String, String>> answersQueue) {
        String spaceId = System.getenv("SPACE_ID");
        String codeLink = (spaceId != null && !spaceId.isBlank())
                ? "https://huggingface.co/spaces/" + spaceId + "/tree/main"
                : "https://github.com/huggingface/agents-course";

        log.info("Enviando {} respostas para avaliação em nome de: {}", answersQueue.size(), username);

        Map<String, Object> submissionPayload = new HashMap<>();
        submissionPayload.put("username", username.trim());
        submissionPayload.put("code_link", codeLink);
        submissionPayload.put("answers", new ArrayList<>(answersQueue));

        Map<String, Object> result = webClient.post()
                .uri("/submit")
                .bodyValue(submissionPayload)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .block();

        log.info("Submissão concluída com sucesso. Resposta da API: {}", result);
    }

    public Map<String, Object> runEvaluationAndSubmit(String username) {
        List<GaiaQuestion> questions;
        try {
            questions = fetchQuestions();
        } catch (Exception e) {
            log.error("Falha ao buscar as questões: {}", e.getMessage());
            return null;
        }

        log.info("Iniciando processamento paralelo para o GAIA Benchmark com {} questões.", questions.size());

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        
        ConcurrentLinkedQueue<Map<String, String>> answersQueue = new ConcurrentLinkedQueue<>();
        
        Instant globalStart = Instant.now();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Void>> futures = questions.stream().map(question -> 
                CompletableFuture.runAsync(() -> 
                    processQuestion(question, successCount, failureCount, answersQueue), executor)
            ).toList();

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }

        Instant globalEnd = Instant.now();
        Duration totalDuration = Duration.between(globalStart, globalEnd);

        log.info("========== RESUMO GAIA BENCHMARK ==========");
        log.info("Total de questões processadas: {}", questions.size());
        log.info("Acertos (Exact Match LOCAL): {}", successCount.get());
        log.info("Falhas LOCAL: {}", failureCount.get());
        log.info("Tempo total: {} segundos", totalDuration.getSeconds());
        log.info("===========================================");

        try {
            submitAnswers(username, answersQueue);
        } catch (Exception e) {
            log.error("Falha ao enviar resultados para a API de avaliação: {}", e.getMessage());
        }
        return Map.of(
            "total", questions.size(),
            "success", successCount.get(),
            "failure", failureCount.get(),
            "duration_seconds", totalDuration.getSeconds()
        );
    }

    private void processQuestion(GaiaQuestion question, AtomicInteger successCount, AtomicInteger failureCount, ConcurrentLinkedQueue<Map<String, String>> answersQueue) {
        Instant qStart = Instant.now();
        
        String agentAnswer = null;
        boolean success = false;

        try {
            // O RateLimiter faz a mágica aqui.
            @SuppressWarnings("UnstableApiUsage")
            double tempoEspera = rateLimiter.acquire(); 
            
            if (tempoEspera > 1.0) {
                log.info("RateLimiter segurou a task {} por {} segundos para não estourar a API", question.id(), String.format("%.1f", tempoEspera));
            }
            log.info("[INÍCIO] Processando Task ID: {} | {}", question.id(), question.task());
            
            agentAnswer = agent.answer(question.task());
            success = true;

        } catch (Exception e) {
            log.error("[ERRO] Falha ao executar questão {}: {}", question.id(), e.getMessage());
            agentAnswer = "AGENT_ERROR: " + e.getMessage();
        }

        long elapsed = Duration.between(qStart, Instant.now()).getSeconds();
        
        answersQueue.add(Map.of(
                "task_id", question.id(),
                "submitted_answer", agentAnswer != null ? agentAnswer : "AGENT_ERROR_OR_TIMEOUT"
        ));

        if (success && agentAnswer != null && question.expectedAnswer() != null) {
            boolean isCorrect = agentAnswer.strip().equalsIgnoreCase(question.expectedAnswer().strip());
            
            if (isCorrect) {
                successCount.incrementAndGet();
                log.info("[ACERTOU] Questão {} em {}s. Resposta: '{}'", question.id(), elapsed, agentAnswer);
            } else {
                failureCount.incrementAndGet();
                log.info("[ERROU] Questão {} em {}s. Resposta Agente: '{}' | Esperado: '{}'", question.id(), elapsed, agentAnswer, question.expectedAnswer());
            }
        } else if (!success) {
            failureCount.incrementAndGet();
            log.warn("[FALHA TÉCNICA] Questão {} abortada ({}s).", question.id(), elapsed);
        }
    }
}
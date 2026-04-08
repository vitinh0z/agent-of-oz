package com.agentofoz;

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

    public EvaluationService(BasicAgent agent) {
        this.agent = agent;
        this.webClient = WebClient.builder()
                .baseUrl(SCORING_API_URL)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /**
     * Busca a lista de questões do endpoint usando WebClient (não-bloqueante).
     */
    private List<GaiaQuestion> fetchQuestions() {
        log.info("Buscando questões da API: {}/questions", SCORING_API_URL);
        
        List<GaiaQuestion> questions = webClient.get()
                .uri("/questions")
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<GaiaQuestion>>() {})
                .block(); // Seguro no contexto de Virtual Threads
                
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
        
        // Fila thread-safe exigida para acumular as respostas em ambiente altamente concorrente
        ConcurrentLinkedQueue<Map<String, String>> answersQueue = new ConcurrentLinkedQueue<>();
        
        Semaphore semaphore = new Semaphore(2);

        Instant globalStart = Instant.now();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Void>> futures = questions.stream().map(question -> 
                CompletableFuture.runAsync(() -> {
                    try {
                        semaphore.acquire();
                        processQuestion(question, successCount, failureCount, answersQueue);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.error("[INTERROMPIDO] Espera do semáforo falhou na questão: {}", question.id());
                    } finally {
                        semaphore.release();
                    }
                }, executor)
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

        // 3. Submit Answers
        try {
            submitAnswers(username, answersQueue);
        } catch (Exception e) {
            log.error("Falha ao enviar resultados para a API de avaliação: {}", e.getMessage());
        }
        return null;
    }


    private void processQuestion(GaiaQuestion question, AtomicInteger successCount, AtomicInteger failureCount, ConcurrentLinkedQueue<Map<String, String>> answersQueue) {
        Instant qStart = Instant.now();
        log.info("[INÍCIO] Questão {}: {}", question.id(), question.task());

        String agentAnswer = null;
        boolean success = false;
        int maxRetries = 3;
        int attempt = 0;

        while (attempt < maxRetries && !success) {
            attempt++;
            try {
                CompletableFuture<String> futureAnswer = CompletableFuture.supplyAsync(
                    () -> agent.answer(question.task())
                );
                
                agentAnswer = futureAnswer.get(120, TimeUnit.SECONDS);
                success = true;

            } catch (TimeoutException e) {
                log.warn("[TIMEOUT] Questão {} excedeu 120s na tentativa {}/{}", question.id(), attempt, maxRetries);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                String errorMsg = cause != null ? cause.getMessage() : e.getMessage();
                
                if (errorMsg != null && errorMsg.contains("429")) {
                    log.warn("[RATE LIMIT - 429] Atingido na questão {}. Backoff 60s antes da tentativa {}/{}", question.id(), attempt + 1, maxRetries);
                    try {
                        Thread.sleep(60000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } else {
                    log.error("[ERRO] Falha ao executar questão {}: {}", question.id(), errorMsg);
                    break; 
                }
            } catch (Exception e) {
                log.error("[ERRO INESPERADO] Questão {}: {}", question.id(), e.getMessage());
                break;
            }
        }

        long elapsed = Duration.between(qStart, Instant.now()).getSeconds();
        
        // Se a chamada do agente falhou completamente após os retries, não podemos deixar o array em branco.
        String finalAnswerToSubmit = (success && agentAnswer != null) ? agentAnswer : "AGENT_ERROR_OR_TIMEOUT";
        
        // Acumula na fila concurrente para a chamada final de submissão
        answersQueue.add(Map.of(
                "task_id", question.id(),
                "submitted_answer", finalAnswerToSubmit
        ));

        // Validação local (se o backend enviar expectedAnswer, o que é o caso)
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
            log.info("[FALHA TÉCNICA] Questão {} abortada após {} tentativas ({}s).", question.id(), attempt, elapsed);
        }
    }
}
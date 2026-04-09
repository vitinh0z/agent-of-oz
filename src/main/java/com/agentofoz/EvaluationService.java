package com.agentofoz;

import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.lang.Nullable;
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
    private final ChatModel chatModel;
    private final ToolGaia toolGaia;
    private final WebClient webClient;
    private static final String SCORING_API_URL = "https://agents-course-unit4-scoring.hf.space";

    public EvaluationService(BasicAgent agent, @Nullable ChatModel chatModel, ToolGaia toolGaia) {
        this.agent = agent;
        this.chatModel = chatModel;
        this.toolGaia = toolGaia;
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

        try (ExecutorService executor = Executors.newFixedThreadPool(1)) {
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
        // Pausa fixa entre questões para não acumular tokens no burst inicial
        try { Thread.sleep(3000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }

        BasicAgent localAgent = (chatModel != null)
                ? AiServices.builder(BasicAgent.class)
                    .chatModel(chatModel)
                    .tools(toolGaia)
                    .chatMemory(MessageWindowChatMemory.withMaxMessages(6))
                    .build()
                : agent;

        Instant qStart = Instant.now();
        log.info("[INÍCIO] Questão {}: {}", question.id(), question.task());

        String agentAnswer = null;
        boolean success = false;
        int maxRetries = 4;
        int attempt = 0;

        while (attempt < maxRetries && !success) {
            attempt++;
            try {
                agentAnswer = localAgent.answer(question.task());
                success = true;

            } catch (Exception e) {
                // Navega até a causa raiz para pegar a mensagem original do Groq
                Throwable root = e;
                while (root.getCause() != null) root = root.getCause();
                String msg = root.getMessage() != null ? root.getMessage() : e.getMessage();
                if (msg == null) msg = "";

                long waitSeconds = 65;
                java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("try again in ([0-9]+(?:\\.[0-9]+)?)s")
                    .matcher(msg);
                if (m.find()) {
                    waitSeconds = (long) Double.parseDouble(m.group(1)) + 5;
                }

                if (msg.contains("rate_limit") || msg.contains("429") || msg.contains("Rate limit")) {
                    log.warn("[429] Tentativa {}/{}. Aguardando {}s...", attempt, maxRetries, waitSeconds);
                    try { Thread.sleep(waitSeconds * 1000L); } catch (InterruptedException ie) { 
                        Thread.currentThread().interrupt();
                        break; 
                    }
                } else {
                    log.error("[ERRO] Questão {}: {}", question.id(), msg);
                    break;
                }
            }
        }

        long elapsed = Duration.between(qStart, Instant.now()).getSeconds();
        
        String finalAnswerToSubmit = (success && agentAnswer != null) ? agentAnswer : "AGENT_ERROR_OR_TIMEOUT";
        
        answersQueue.add(Map.of(
                "task_id", question.id(),
                "submitted_answer", finalAnswerToSubmit
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
            log.info("[FALHA TÉCNICA] Questão {} abortada ({}s).", question.id(), elapsed);
        }
    }
}

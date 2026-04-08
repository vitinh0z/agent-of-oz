package com.agentofoz;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser;
import lombok.extern.slf4j.Slf4j;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class ToolGaia {

    private final String tavilyApiKey;
    private final WebClient webClient;

    public ToolGaia(String tavilyApiKey) {
        this.tavilyApiKey = tavilyApiKey;
        
        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofSeconds(5));

        this.webClient = WebClient.builder()
                .baseUrl("https://api.tavily.com")
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Tool("""
        USE OBRIGATÓRIO para qualquer fato externo verificável: datas, nomes, contagens, 
        títulos, valores numéricos ou dados históricos. NUNCA responda de memória.
        
        ESTRATÉGIA DE QUERY (em ordem de prioridade):
        1. Sempre em INGLÊS.
        2. Máximo 4-5 palavras-chave. Remova artigos, verbos e preposições.
           BOM: 'Mercedes Sosa albums 2000s'  |  RUIM: 'Quais são os álbuns da Mercedes Sosa'
        3. Inclua o tipo de entidade se ambíguo: 'singer', 'film', 'politician'.
        4. Para Wikipedia: adicione 'wikipedia' à query (ex: 'Mercedes Sosa wikipedia discography').
        
        PROTOCOLO DE VERIFICAÇÃO:
        - Se o 1º resultado for vago ou contraditório -> reformule com sinônimos e busque novamente.
        - Para respostas numéricas (contagens, anos): confirme com pelo menos 2 buscas diferentes.
        """)
    public String buscarNaWeb(String query){
        log.info("Buscando na web para: {}", query);

        try {
            Map<String, Object> body = new HashMap<>(8);
            body.put("api_key", tavilyApiKey);
            body.put("query", query);
            body.put("search_depth", "advanced");
            body.put("include_answer", true);
            body.put("max_results", 5);

            Map responseBody = webClient.post()
                    .uri("/search")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (responseBody == null) {
                return "Nenhum resultado encontrado na web";
            }

            StringBuilder stringResults = new StringBuilder();

            String answer = (String) responseBody.get("answer");
            if (answer != null && !answer.isBlank()) {
                stringResults.append("Resposta direta: ").append(answer).append("\n\n");
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> results = (List<Map<String, Object>>) responseBody.get("results");

            if (results != null && !results.isEmpty()) {
                stringResults.append("Fontes:\n");
                for (int i = 0; i<Math.min(results.size(), 5); i++) {
                    Map<String, Object> r = results.get(i);
                    stringResults.append("---\n");
                    stringResults.append("Título: ").append(r.get("title")).append("\n");
                    stringResults.append("URL: ").append(r.get("url")).append("\n");
                    stringResults.append("Trecho: ").append(r.get("content")).append("\n");
                }
            }

            return stringResults.isEmpty()
                    ? "Nenhum resultado encontrado na web"
                    : stringResults.toString();
        } catch (Exception e) {
            log.error("erro na busca: {}", e.getMessage());
            return "Nenhum resultado encontrado na web";
        }
    }

    @Tool("Executa código JavaScript para resolver matemática exata, ordenação complexa ou lógica. Retorna o resultado da última expressão.")
    public String executarCodigo(String scriptJS) {
        try (Context context = Context.create()) {
            context.eval("js", """
            const __start = Date.now();
            function __checkTimeout() { 
                if (Date.now() - __start > 5000) throw new Error('Timeout: script excedeu 5s'); 
            }
        """);
            Value resultado = context.eval("js", scriptJS);
            return resultado.toString();

        } catch (Exception e) {
            return "Erro na execução do script: " + e.getMessage();
        }
    }


    @Tool(
    """
        Use para ler arquivos anexos de questões do GAIA: PDF, Excel, Word ou TXT.
        O caminho do arquivo vem do campo file_path da questão.
        Extraia apenas a informação relevante para responder — não retorne o arquivo inteiro.
    """)
    public String lerFicheiro(String caminhoFicheiro) {
        try {
            Document documento = FileSystemDocumentLoader.loadDocument(
                    caminhoFicheiro,
                    new ApacheTikaDocumentParser()
            );
            return documento.text();
        } catch (Exception e) {
            return "Erro ao ler o ficheiro: " + e.getMessage();
        }
    }
}
package br.com.wagner.wagsyspet.agente.core.pareamento;

import br.com.wagner.wagsyspet.agente.protocolo.ticket.ChavesTicket;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Plano F3 D13 — {@code POST /api/public/agente-impressao/parear} contra um servidor HTTP local que devolve cada resposta que
 * o backend F1 pode dar. Regra de ouro: <b>nenhum retry automático</b> (o endpoint está no bucket de login da loja: 5/min por IP).
 */
@DisplayName("ClientePareamento — POST /parear: request certo, cada resposta do backend mapeada, sem retry")
class ClientePareamentoTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CODIGO = "A".repeat(43);
    private static final String CHAVE = ChavesTicket.exportarPublica(ChavesTicket.gerar().getPublic());

    private HttpServer servidor;
    private String base;
    private final List<JsonNode> corpos = new CopyOnWriteArrayList<>();
    private final List<Map<String, List<String>>> headers = new CopyOnWriteArrayList<>();
    private final AtomicReference<Resposta> resposta = new AtomicReference<>();

    private record Resposta(int status, String corpo, Map<String, String> headers, long atrasoMs) {
        static Resposta json(int status, String corpo) {
            return new Resposta(status, corpo, Map.of("Content-Type", "application/json"), 0);
        }
    }

    @BeforeEach
    void subir() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/api/public/agente-impressao/parear", this::atender);
        servidor.start();
        base = "http://127.0.0.1:" + servidor.getAddress().getPort();
    }

    @AfterEach
    void derrubar() {
        servidor.stop(0);
    }

    private void atender(HttpExchange ex) throws IOException {
        headers.add(ex.getRequestHeaders());
        byte[] corpo = ex.getRequestBody().readAllBytes();
        try {
            corpos.add(JSON.readTree(corpo));
        } catch (IOException e) {
            corpos.add(null);
        }
        Resposta r = resposta.get();
        if (r.atrasoMs() > 0) {
            try {
                Thread.sleep(r.atrasoMs());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        r.headers().forEach((k, v) -> ex.getResponseHeaders().add(k, v));
        byte[] b = r.corpo().getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(r.status(), b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    private ClientePareamento cliente() {
        return cliente(Duration.ofSeconds(5));
    }

    private ClientePareamento cliente(Duration prazo) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
        return new ClientePareamento(http, base, "1.0.0-teste", prazo,
                new IdentidadeDaMaquina("windows", "x64", "CAIXA-01"));
    }

    private static String respostaOk() {
        return "{\"agenteId\":\"3f1c2b6e-0f1a-4a2b-9c3d-000000000001\",\"lojaId\":7,\"credencial\":\"" + "c".repeat(43) + "\","
                + "\"chavePublicaTicket\":\"" + CHAVE + "\",\"origensPermitidas\":[\"https://app.agroease.com.br\"],"
                + "\"portaSugerida\":28421,\"versaoMinima\":\"1.0.0\"}";
    }

    private static String erroBackend(int status, String code, String mensagem, String dataJson) {
        return "{\"timestamp\":\"2026-09-09T12:00:00\",\"status\":" + status + ",\"error\":\"x\",\"message\":\"" + mensagem + "\","
                + "\"path\":\"/api/public/agente-impressao/parear\",\"code\":\"" + code + "\"" + (dataJson == null ? "" : ",\"data\":" + dataJson) + "}";
    }

    private PareamentoException esperaFalha(ClientePareamento c, String codigo) {
        try {
            c.parear(codigo);
        } catch (PareamentoException e) {
            return e;
        }
        throw new AssertionError("esperava PareamentoException");
    }

    @Test
    @DisplayName("200 → Pareamento montado; request = POST JSON {token, versao, so, arch, hostname} com User-Agent AgroEase e sem Authorization")
    void sucesso() throws Exception {
        resposta.set(Resposta.json(200, respostaOk()));
        Pareamento p = cliente().parear(CODIGO);
        assertThat(p.agenteId()).isEqualTo("3f1c2b6e-0f1a-4a2b-9c3d-000000000001");
        assertThat(p.lojaId()).isEqualTo(7L);
        assertThat(p.backendUrl()).isEqualTo(base);

        assertThat(corpos).hasSize(1);
        JsonNode req = corpos.get(0);
        assertThat(req.get("token").asText()).isEqualTo(CODIGO);
        assertThat(req.get("versao").asText()).isEqualTo("1.0.0-teste");
        assertThat(req.get("so").asText()).isEqualTo("windows");
        assertThat(req.get("arch").asText()).isEqualTo("x64");
        assertThat(req.get("hostname").asText()).isEqualTo("CAIXA-01");
        assertThat(req.fieldNames()).toIterable().containsExactlyInAnyOrder("token", "versao", "so", "arch", "hostname");
        Map<String, List<String>> h = headers.get(0);
        assertThat(h.get("Content-type").get(0)).startsWith("application/json");
        assertThat(h.get("User-agent").get(0)).startsWith("AgroEase-Agente/1.0.0-teste");
        assertThat(h.containsKey("Authorization")).isFalse();
        assertThat(h.containsKey("X-tenant-id")).isFalse();
    }

    @Test
    @DisplayName("código mal formado (não são 43 chars base64url) → CODIGO_MAL_FORMADO SEM chamar o servidor (não gasta a cota de login)")
    void codigoMalFormadoNaoChamaServidor() {
        resposta.set(Resposta.json(200, respostaOk()));
        for (String ruim : new String[]{"", "   ", "abc", "A".repeat(42), "A".repeat(44), "A".repeat(42) + "!", null}) {
            PareamentoException e = esperaFalha(cliente(), ruim);
            assertThat(e.motivo()).isEqualTo(PareamentoException.Motivo.CODIGO_MAL_FORMADO);
            assertThat(e.codigoContinuaValido()).isTrue();
        }
        assertThat(corpos).isEmpty();
    }

    @Test
    @DisplayName("código com espaços em volta é aceito (colado do painel) e enviado limpo")
    void codigoComEspacos() throws Exception {
        resposta.set(Resposta.json(200, respostaOk()));
        cliente().parear("  " + CODIGO + "\n");
        assertThat(corpos.get(0).get("token").asText()).isEqualTo(CODIGO);
    }

    @Test
    @DisplayName("404 AGENTE_TOKEN_INVALIDO → CODIGO_INVALIDO (inexistente, usado, expirado ou revogado) — o código NÃO vale mais")
    void codigoInvalido() {
        resposta.set(Resposta.json(404, erroBackend(404, "AGENTE_TOKEN_INVALIDO", "Código inválido ou expirado", null)));
        PareamentoException e = esperaFalha(cliente(), CODIGO);
        assertThat(e.motivo()).isEqualTo(PareamentoException.Motivo.CODIGO_INVALIDO);
        assertThat(e.codigoContinuaValido()).isFalse();
        assertThat(e.getMessage()).containsIgnoringCase("código");
    }

    @Test
    @DisplayName("426 AGENTE_VERSAO_OBSOLETA → VERSAO_OBSOLETA com versaoMinima; o código continua válido (atualiza e repete)")
    void versaoObsoleta() {
        resposta.set(Resposta.json(426, erroBackend(426, "AGENTE_VERSAO_OBSOLETA", "Atualize", "{\"versaoMinima\":\"2.0.0\",\"versaoRecebida\":\"1.0.0-teste\"}")));
        PareamentoException e = esperaFalha(cliente(), CODIGO);
        assertThat(e.motivo()).isEqualTo(PareamentoException.Motivo.VERSAO_OBSOLETA);
        assertThat(e.codigoContinuaValido()).isTrue();
        assertThat(e.versaoMinima()).isEqualTo("2.0.0");
        assertThat(e.getMessage()).contains("2.0.0");
    }

    @Test
    @DisplayName("429 (bucket de login por IP) → LIMITE_TENTATIVAS com Retry-After; código continua válido; UMA chamada só")
    void limite() {
        resposta.set(new Resposta(429, "{\"success\":false,\"message\":\"Muitas tentativas\",\"error\":\"TOO_MANY_REQUESTS\",\"retryAfter\":37}",
                Map.of("Content-Type", "application/json", "Retry-After", "37"), 0));
        PareamentoException e = esperaFalha(cliente(), CODIGO);
        assertThat(e.motivo()).isEqualTo(PareamentoException.Motivo.LIMITE_TENTATIVAS);
        assertThat(e.codigoContinuaValido()).isTrue();
        assertThat(e.retryAfterSegundos()).isEqualTo(37);
        assertThat(e.getMessage()).contains("37");
        assertThat(corpos).hasSize(1);
    }

    @Test
    @DisplayName("503 AGENTE_NAO_CONFIGURADO → SERVIDOR_NAO_CONFIGURADO (avise o suporte); código continua válido")
    void servidorNaoConfigurado() {
        resposta.set(Resposta.json(503, erroBackend(503, "AGENTE_NAO_CONFIGURADO", "sem chave", null)));
        PareamentoException e = esperaFalha(cliente(), CODIGO);
        assertThat(e.motivo()).isEqualTo(PareamentoException.Motivo.SERVIDOR_NAO_CONFIGURADO);
        assertThat(e.codigoContinuaValido()).isTrue();
    }

    @Test
    @DisplayName("400 (validação do request) → REQUISICAO_INVALIDA; 500 → SERVIDOR (o código PODE ter sido gasto: não prometer); 502 HTML do proxy → SERVIDOR")
    void outrosStatus() {
        resposta.set(Resposta.json(400, "{\"status\":400,\"message\":\"Validation Failed\",\"errors\":{\"hostname\":\"caracteres inválidos\"}}"));
        PareamentoException e400 = esperaFalha(cliente(), CODIGO);
        assertThat(e400.motivo()).isEqualTo(PareamentoException.Motivo.REQUISICAO_INVALIDA);
        assertThat(e400.codigoContinuaValido()).isTrue();

        resposta.set(Resposta.json(500, erroBackend(500, "INTERNAL", "boom", null)));
        PareamentoException e500 = esperaFalha(cliente(), CODIGO);
        assertThat(e500.motivo()).isEqualTo(PareamentoException.Motivo.SERVIDOR);
        assertThat(e500.codigoContinuaValido()).isFalse();

        resposta.set(new Resposta(502, "<html><body>Bad Gateway</body></html>", Map.of("Content-Type", "text/html"), 0));
        PareamentoException e502 = esperaFalha(cliente(), CODIGO);
        assertThat(e502.motivo()).isEqualTo(PareamentoException.Motivo.SERVIDOR);
    }

    @Test
    @DisplayName("200 fora do contrato: HTML (portal cativo — não chegou ao backend) → código CONTINUA válido; JSON do backend sem os campos → código CONSUMIDO (adversarial L3-A3)")
    void respostaInvalida() {
        resposta.set(new Resposta(200, "<html>login do wifi</html>", Map.of("Content-Type", "text/html"), 0));
        PareamentoException html = esperaFalha(cliente(), CODIGO);
        assertThat(html.motivo()).isEqualTo(PareamentoException.Motivo.RESPOSTA_INVALIDA);
        assertThat(html.codigoContinuaValido()).isTrue();

        resposta.set(Resposta.json(200, "{\"agenteId\":\"x\",\"lojaId\":7}"));
        PareamentoException json = esperaFalha(cliente(), CODIGO);
        assertThat(json.motivo()).isEqualTo(PareamentoException.Motivo.RESPOSTA_INVALIDA);
        assertThat(json.codigoContinuaValido()).as("veio do backend: o CAS já consumiu o código").isFalse();
        assertThat(json.getMessage()).contains("gere OUTRO");
    }

    @Test
    @DisplayName("3xx (Redirect.NEVER) → RESPOSTA_INVALIDA: o agente não segue redirecionamento para fora do backend")
    void redirecionamento() {
        resposta.set(new Resposta(302, "", Map.of("Location", "https://malicioso.example/parear"), 0));
        assertThat(esperaFalha(cliente(), CODIGO).motivo()).isEqualTo(PareamentoException.Motivo.RESPOSTA_INVALIDA);
    }

    @Test
    @DisplayName("servidor não responde no prazo → REDE (o Railway dorme: prazo de produção é 60 s); o código PODE ter sido consumido (não promete reuso); sem retry")
    void prazo() {
        resposta.set(new Resposta(200, respostaOk(), Map.of("Content-Type", "application/json"), 1500));
        PareamentoException e = esperaFalha(cliente(Duration.ofMillis(300)), CODIGO);
        assertThat(e.motivo()).isEqualTo(PareamentoException.Motivo.REDE);
        assertThat(e.codigoContinuaValido()).isFalse();
        assertThat(e.getMessage()).contains("gere outro");
        assertThat(corpos).hasSizeLessThanOrEqualTo(1);
    }

    @Test
    @DisplayName("porta fechada (backend fora / URL errada) → REDE com a URL na mensagem")
    void portaFechada() {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        ClientePareamento c = new ClientePareamento(http, "http://127.0.0.1:9", "1.0.0", Duration.ofSeconds(2),
                new IdentidadeDaMaquina("linux", "x64", null));
        PareamentoException e = esperaFalha(c, CODIGO);
        assertThat(e.motivo()).isEqualTo(PareamentoException.Motivo.REDE);
        assertThat(e.getMessage()).contains("127.0.0.1:9");
    }

    @Test
    @DisplayName("desvio de relógio pelo header Date (RFC 1123): local − servidor; 3 h atrasado é o caso do RTC em hora local; Date ausente/inválido → vazio")
    void desvioDeRelogio() {
        java.time.Instant local = java.time.Instant.parse("2026-09-09T12:00:00Z");
        assertThat(ClientePareamento.desvioDeRelogio("Wed, 09 Sep 2026 15:00:00 GMT", local)).contains(Duration.ofHours(-3));
        assertThat(ClientePareamento.desvioDeRelogio("Wed, 09 Sep 2026 11:59:30 GMT", local)).contains(Duration.ofSeconds(30));
        assertThat(ClientePareamento.desvioDeRelogio(null, local)).isEmpty();
        assertThat(ClientePareamento.desvioDeRelogio("ontem", local)).isEmpty();
        assertThat(ClientePareamento.descreverDesvio(Duration.ofHours(-3))).isEqualTo("3 h atrasado");
        assertThat(ClientePareamento.descreverDesvio(Duration.ofMinutes(7))).isEqualTo("7 min adiantado");
        assertThat(Duration.ofHours(-3).abs().compareTo(ClientePareamento.DESVIO_RELOGIO_ALERTA)).isPositive();
    }

    @Test
    @DisplayName("URL do backend: só https:// ou http://localhost|127.0.0.1; barra final removida")
    void urlBackend() {
        assertThatThrownBy(() -> new ClientePareamento(HttpClient.newHttpClient(), "http://backend.exemplo.com", "1.0.0", Duration.ofSeconds(1), new IdentidadeDaMaquina("linux", null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClientePareamento(HttpClient.newHttpClient(), "ftp://x", "1.0.0", Duration.ofSeconds(1), new IdentidadeDaMaquina("linux", null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        ClientePareamento c = new ClientePareamento(HttpClient.newHttpClient(), "https://api.exemplo.com.br/", "1.0.0", Duration.ofSeconds(1), new IdentidadeDaMaquina("linux", null, null));
        assertThat(c.backendUrl()).isEqualTo("https://api.exemplo.com.br");
    }
}

package br.com.wagner.wagsyspet.agente.core.pareamento;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

import static br.com.wagner.wagsyspet.agente.core.pareamento.PareamentoException.Motivo;

/**
 * {@code POST /api/public/agente-impressao/parear} (plano F3 D13): troca o código de 1 uso gerado no painel pela identidade
 * do caixa. <b>Sem retry automático</b> — o endpoint compartilha o bucket de login da loja (5/min por IP em produção):
 * repetir sozinho trancaria o login de todos os caixas. Quem repete é o lojista, orientado pela mensagem.
 *
 * <p>Prazo de produção: 60 s (o Railway dorme e o cold start passa de 25 s). {@code Redirect.NEVER}: um 3xx não é seguido —
 * o código nunca vai para outro host.</p>
 */
public final class ClientePareamento {

    private static final Logger log = LoggerFactory.getLogger(ClientePareamento.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    /** Mesma forma do backend ({@code FORMA_CODIGO}): 32 bytes base64url sem padding. */
    private static final Pattern FORMA_CODIGO = Pattern.compile("^[A-Za-z0-9_-]{43}$");
    private static final Pattern URL_BACKEND = Pattern.compile("^(https://[A-Za-z0-9.-]+(:\\d{1,5})?|http://(localhost|127\\.0\\.0\\.1)(:\\d{1,5})?)(/.*)?$");
    public static final String CAMINHO = "/api/public/agente-impressao/parear";
    public static final Duration PRAZO_PADRAO = Duration.ofSeconds(60);

    /** Acima disto o ticket (10 min de vida, teto 1 h no verificador) começa a ser recusado por relógio — avisar o lojista. */
    public static final Duration DESVIO_RELOGIO_ALERTA = Duration.ofMinutes(5);

    private final HttpClient http;
    private final String backendUrl;
    private final String versao;
    private final Duration prazo;
    private final IdentidadeDaMaquina maquina;
    private volatile Duration ultimoDesvioDeRelogio;

    /**
     * @param backendUrl só {@code https://} (ou {@code http://localhost|127.0.0.1} em dev); barra final é removida
     */
    public ClientePareamento(HttpClient http, String backendUrl, String versao, Duration prazo, IdentidadeDaMaquina maquina) {
        this.http = Objects.requireNonNull(http);
        this.backendUrl = normalizarUrl(backendUrl);
        this.versao = Objects.requireNonNull(versao);
        this.prazo = Objects.requireNonNull(prazo);
        this.maquina = Objects.requireNonNull(maquina);
    }

    /** Cliente de produção: connect 10 s, request {@link #PRAZO_PADRAO}, sem redirect. */
    public static ClientePareamento padrao(String backendUrl, String versao) {
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1) // sem negociação h2/h2c: uma chamada só, previsível em proxies de loja
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return new ClientePareamento(http, backendUrl, versao, PRAZO_PADRAO, IdentidadeDaMaquina.destaMaquina());
    }

    static String normalizarUrl(String url) {
        String u = Objects.requireNonNull(url, "backendUrl").trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        if (!URL_BACKEND.matcher(u).matches()) {
            throw new IllegalArgumentException("URL do backend inválida: '" + url + "' (só https://, ou http://localhost em desenvolvimento)");
        }
        return u;
    }

    public String backendUrl() {
        return backendUrl;
    }

    public Pareamento parear(String codigoDigitado) throws PareamentoException {
        String codigo = codigoDigitado == null ? "" : codigoDigitado.trim();
        if (!FORMA_CODIGO.matcher(codigo).matches()) {
            throw new PareamentoException(Motivo.CODIGO_MAL_FORMADO,
                    "O código de pareamento não tem a forma esperada (43 letras/números). Copie de novo do painel da loja.", true);
        }
        ObjectNode corpo = JSON.createObjectNode();
        corpo.put("token", codigo);
        corpo.put("versao", versao);
        corpo.put("so", maquina.so());
        if (maquina.arch() != null) {
            corpo.put("arch", maquina.arch());
        }
        if (maquina.hostname() != null) {
            corpo.put("hostname", maquina.hostname());
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(backendUrl + CAMINHO))
                .timeout(prazo)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("User-Agent", "AgroEase-Agente/" + versao + " (" + maquina.so() + ")")
                .POST(HttpRequest.BodyPublishers.ofString(corpo.toString(), StandardCharsets.UTF_8))
                .build();
        log.info("Pareando com {} (versão {}, {} {})", backendUrl, versao, maquina.so(), maquina.arch());

        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            // a requisição PODE ter chegado e consumido o código antes de o prazo estourar — não prometer reuso (adversarial L3-A3)
            throw new PareamentoException(Motivo.REDE, "O servidor " + backendUrl + " não respondeu em " + prazo.toSeconds()
                    + " s. Confira a internet; se o mesmo código falhar de novo, gere outro no painel.", false, null, null, e);
        } catch (IOException e) {
            throw new PareamentoException(Motivo.REDE, "Não foi possível falar com " + backendUrl + " (" + resumo(e)
                    + "). Confira a internet e a URL do backend; o código continua válido.", true, null, null, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PareamentoException(Motivo.REDE, "Pareamento interrompido.", true, null, null, e);
        }
        ultimoDesvioDeRelogio = desvioDeRelogio(resp.headers().firstValue("Date").orElse(null), Instant.now()).orElse(null);
        if (ultimoDesvioDeRelogio != null && ultimoDesvioDeRelogio.abs().compareTo(DESVIO_RELOGIO_ALERTA) > 0) {
            log.warn("Relógio deste computador está {} em relação ao servidor — tickets podem ser recusados (acerte a data/hora)", descreverDesvio(ultimoDesvioDeRelogio));
        }
        return interpretar(resp);
    }

    /**
     * Diferença relógio local − relógio do servidor, pelo header HTTP {@code Date} (RFC 1123). O verificador do ticket recusa
     * {@code exp − agora > 1 h} (VALIDADE_ABSURDA) — um caixa com relógio ≥ 50 min ATRASADO não imprime e o PWA mostra
     * "formato inválido"; aqui a causa fica visível no pareamento e no {@code --diagnostico} (adversarial F3 L3-A2; a mudança
     * do verificador em si é contrato compartilhado com o backend, fora da F3).
     */
    public static java.util.Optional<Duration> desvioDeRelogio(String headerDate, Instant agoraLocal) {
        if (headerDate == null || headerDate.isBlank()) {
            return java.util.Optional.empty();
        }
        try {
            Instant servidor = java.time.ZonedDateTime.parse(headerDate.trim(), java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            return java.util.Optional.of(Duration.between(servidor, agoraLocal));
        } catch (RuntimeException e) {
            return java.util.Optional.empty();
        }
    }

    public static String descreverDesvio(Duration d) {
        long s = Math.abs(d.getSeconds());
        String quanto = s >= 3600 ? (s / 3600) + " h" : s >= 60 ? (s / 60) + " min" : s + " s";
        return quanto + (d.isNegative() ? " atrasado" : " adiantado");
    }

    /** Desvio medido na última chamada (vazio se o servidor não mandou {@code Date}). */
    public java.util.Optional<Duration> ultimoDesvioDeRelogio() {
        return java.util.Optional.ofNullable(ultimoDesvioDeRelogio);
    }

    private Pareamento interpretar(HttpResponse<String> resp) throws PareamentoException {
        int status = resp.statusCode();
        String corpo = resp.body() == null ? "" : resp.body();
        JsonNode json = tentarJson(corpo);
        String code = json == null ? null : texto(json, "code");
        String mensagemServidor = json == null ? null : texto(json, "message");

        if (status == 200) {
            if (json == null) {
                // HTML/texto no lugar do JSON: portal cativo de Wi-Fi ou proxy — a requisição NÃO chegou ao backend; código continua válido
                throw new PareamentoException(Motivo.RESPOSTA_INVALIDA,
                        "O servidor respondeu algo que não é o esperado (talvez uma página de login de Wi-Fi ou um proxy). Conecte a rede e tente de novo com o mesmo código.", true);
            }
            return Pareamento.daResposta(json, backendUrl, Instant.now());
        }
        if (status >= 300 && status < 400) {
            throw new PareamentoException(Motivo.RESPOSTA_INVALIDA,
                    "O servidor tentou redirecionar o pareamento para outro endereço; por segurança o agente não segue. Confira a URL do backend.", true);
        }
        if (status == 404 && "AGENTE_TOKEN_INVALIDO".equals(code)) {
            throw new PareamentoException(Motivo.CODIGO_INVALIDO,
                    "Código de pareamento inválido, já usado ou expirado. Gere outro código no painel da loja (Configurações → Geral → Impressão de Cupom).", false);
        }
        if (status == 426) {
            String minima = json == null ? null : texto(json.path("data"), "versaoMinima");
            throw new PareamentoException(Motivo.VERSAO_OBSOLETA,
                    "Este agente (" + versao + ") está desatualizado; a loja exige a versão " + (minima == null ? "mais nova" : minima)
                            + ". Baixe a versão atual e pareie de novo com o MESMO código.", true, null, minima, null);
        }
        if (status == 429) {
            Integer retry = retryAfter(resp, json);
            throw new PareamentoException(Motivo.LIMITE_TENTATIVAS,
                    "Muitas tentativas a partir desta rede. Aguarde " + (retry == null ? "um minuto" : retry + " s")
                            + " e tente de novo com o mesmo código (um caixa por vez).", true, retry, null, null);
        }
        if (status == 503 && "AGENTE_NAO_CONFIGURADO".equals(code)) {
            throw new PareamentoException(Motivo.SERVIDOR_NAO_CONFIGURADO,
                    "O servidor ainda não está configurado para o Agente de Impressão. Avise o suporte AgroEase; o código continua válido.", true);
        }
        if (status == 400) {
            throw new PareamentoException(Motivo.REQUISICAO_INVALIDA,
                    "O servidor recusou os dados deste computador" + (mensagemServidor == null ? "" : " (" + mensagemServidor + ")")
                            + ". Avise o suporte AgroEase informando o nome do computador.", true);
        }
        if (status == 404) {
            throw new PareamentoException(Motivo.RESPOSTA_INVALIDA,
                    "O endereço " + backendUrl + CAMINHO + " não existe neste servidor. Confira a URL do backend.", true);
        }
        // 5xx (ou qualquer outro): o servidor falhou — o código PODE ter sido consumido antes da falha
        throw new PareamentoException(Motivo.SERVIDOR,
                "O servidor falhou ao parear (HTTP " + status + "). Tente de novo em instantes; se o código não funcionar mais, gere outro no painel.", false);
    }

    private static Integer retryAfter(HttpResponse<String> resp, JsonNode json) {
        String h = resp.headers().firstValue("Retry-After").orElse(null);
        if (h != null) {
            try {
                return Integer.parseInt(h.trim());
            } catch (NumberFormatException ignored) {
                // data HTTP em vez de segundos — cai no corpo
            }
        }
        if (json != null && json.path("retryAfter").isIntegralNumber()) {
            return json.get("retryAfter").asInt();
        }
        return null;
    }

    private static JsonNode tentarJson(String corpo) {
        if (corpo == null || corpo.isBlank()) {
            return null;
        }
        try {
            JsonNode n = JSON.readTree(corpo);
            return n != null && n.isObject() ? n : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static String texto(JsonNode n, String campo) {
        JsonNode v = n.get(campo);
        return v == null || !v.isTextual() ? null : v.asText();
    }

    private static String resumo(IOException e) {
        String m = e.getMessage();
        String tipo = e.getClass().getSimpleName().toLowerCase(Locale.ROOT).replace("exception", "");
        return m == null || m.isBlank() ? tipo : tipo + ": " + m;
    }
}

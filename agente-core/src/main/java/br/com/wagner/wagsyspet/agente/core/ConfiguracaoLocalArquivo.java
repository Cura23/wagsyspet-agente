package br.com.wagner.wagsyspet.agente.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code config.json} da pasta de dados do agente — em claro (não há segredo aqui), escrita ATÔMICA (tmp + move) para
 * nunca deixar meio-arquivo se o PC desligar no meio. Arquivo ausente ou corrompido = sem seleção (o dono escolhe de
 * novo no painel), nunca derruba o agente.
 *
 * <p><b>Uma verdade só</b> (adversarial F3 A1): toda leitura confere o {@code mtime} do arquivo e relê quando mudou — a
 * bandeja, o servidor WebSocket e a linha de comando ({@code --status}) enxergam a mesma impressora mesmo sendo
 * instâncias/processos diferentes. Sem isto, escolher na bandeja deixava o PWA vendo {@code selecionada: null}.</p>
 *
 * <pre>{ "impressoraSelecionada": "EPSON TM-T20",
 *   "extras": { "impressora": "EPSON TM-T20", "dialeto": "ESCPOS", "gaveta": true, "corte": false, "gavetaPino": 2, "gavetaPulsoMs": 50 } }</pre>
 * {@code extras} (F6-L5) é o opt-in de gaveta/corte: ausente ou com qualquer lixo = desligado; trocar de impressora o apaga.
 */
public final class ConfiguracaoLocalArquivo implements ConfiguracaoLocal {

    private static final Logger log = LoggerFactory.getLogger(ConfiguracaoLocalArquivo.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    static final String CAMPO_IMPRESSORA = "impressoraSelecionada";

    private final Path arquivo;
    static final String CAMPO_EXTRAS = "extras";
    private volatile String impressora;
    private volatile ExtrasImpressao extras;
    /** Conteúdo da última leitura: releitura por CONTEÚDO, não por mtime — duas escritas no mesmo tique do relógio do kernel
     *  (poucos ms) têm o mesmo mtime e a segunda passaria despercebida (achado do F6-L1 no estado.json). O arquivo tem ~50 bytes. */
    private volatile byte[] bytesLidos;

    public ConfiguracaoLocalArquivo(Path arquivo) {
        this.arquivo = Objects.requireNonNull(arquivo);
        recarregarSeMudou();
    }

    public Path arquivo() {
        return arquivo;
    }

    @Override
    public Optional<String> impressoraSelecionada() {
        recarregarSeMudou();
        return Optional.ofNullable(impressora);
    }

    @Override
    public synchronized void impressoraSelecionada(String nome) throws IOException {
        recarregarSeMudou();
        String novo = nome == null || nome.isBlank() ? null : nome;
        // o opt-in de gaveta/corte é daquele hardware: outra impressora nunca o herda
        ExtrasImpressao mantidos = Objects.equals(novo, impressora) ? extras : null;
        gravar(novo, mantidos);
    }

    @Override
    public synchronized void selecionar(String nome, ExtrasImpressao extrasOuNull) throws IOException {
        if (extrasOuNull != null && !extrasOuNull.impressora().equals(nome)) {
            throw new IllegalArgumentException("extras de '" + extrasOuNull.impressora() + "' não valem para '" + nome + "'");
        }
        recarregarSeMudou();
        String novo = nome == null || nome.isBlank() ? null : nome;
        ExtrasImpressao novos = extrasOuNull != null ? extrasOuNull : (Objects.equals(novo, impressora) ? extras : null);
        gravar(novo, novos); // UMA escrita atômica para os dois
    }

    @Override
    public Optional<ExtrasImpressao> extras() {
        recarregarSeMudou();
        return Optional.ofNullable(extras);
    }

    @Override
    public synchronized void extras(ExtrasImpressao novos) throws IOException {
        recarregarSeMudou();
        gravar(impressora, novos);
    }

    private void gravar(String novaImpressora, ExtrasImpressao novosExtras) throws IOException {
        ObjectNode n = JSON.createObjectNode();
        if (novaImpressora != null) {
            n.put(CAMPO_IMPRESSORA, novaImpressora);
        }
        if (novosExtras != null) {
            n.putObject(CAMPO_EXTRAS)
                    .put("impressora", novosExtras.impressora())
                    .put("dialeto", novosExtras.dialeto().name())
                    .put("gaveta", novosExtras.gaveta())
                    .put("corte", novosExtras.corte())
                    .put("gavetaPino", novosExtras.gavetaPino())
                    .put("gavetaPulsoMs", novosExtras.gavetaPulsoMs());
        }
        String json = n.toPrettyString();
        gravarAtomico(json);
        impressora = novaImpressora;
        extras = novosExtras;
        bytesLidos = json.getBytes(StandardCharsets.UTF_8);
    }

    private synchronized void recarregarSeMudou() {
        byte[] atual;
        try {
            atual = Files.exists(arquivo) ? Files.readAllBytes(arquivo) : null;
        } catch (IOException e) {
            log.warn("config.json ilegível em {} ({}); mantendo a última leitura", arquivo, e.toString());
            return;
        }
        if (java.util.Arrays.equals(atual, bytesLidos)) {
            return;
        }
        impressora = ler(atual);
        extras = lerExtras(atual);
        bytesLidos = atual;
    }

    /** Qualquer lixo (tipo errado, dialeto inexistente, pulso/pino fora da faixa) = desligado, sem exceção. */
    private ExtrasImpressao lerExtras(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        try {
            JsonNode e = JSON.readTree(new String(bytes, StandardCharsets.UTF_8)).path(CAMPO_EXTRAS);
            if (!e.isObject()) {
                return null;
            }
            return deJson(e, e.path("impressora").asText(""));
        } catch (IOException | RuntimeException ex) {
            log.warn("extras do config.json ilegíveis em {} ({}); gaveta/corte ficam desligados", arquivo, ex.toString());
            return null;
        }
    }

    /**
     * Objeto {@code extras} (do config.json ou do protocolo) → record. Estrito nos tipos: {@code gaveta}/{@code corte} booleanos,
     * {@code dialeto} do enum, pino/pulso inteiros na faixa do catálogo. Lança {@link IllegalArgumentException} no que não servir.
     */
    static ExtrasImpressao deJson(JsonNode e, String impressora) {
        if (e.has("gaveta") && !e.get("gaveta").isBoolean() || e.has("corte") && !e.get("corte").isBoolean()) {
            throw new IllegalArgumentException("gaveta/corte devem ser booleanos");
        }
        if (e.has("gavetaPino") && !e.get("gavetaPino").isInt() || e.has("gavetaPulsoMs") && !e.get("gavetaPulsoMs").isInt()) {
            throw new IllegalArgumentException("gavetaPino/gavetaPulsoMs devem ser inteiros");
        }
        br.com.wagner.wagsyspet.agente.impressao.raw.ComandosRaw.Dialeto dialeto =
                br.com.wagner.wagsyspet.agente.impressao.raw.ComandosRaw.Dialeto.valueOf(e.path("dialeto").asText("ESCPOS"));
        return new ExtrasImpressao(impressora, dialeto, e.path("gaveta").asBoolean(false), e.path("corte").asBoolean(false),
                e.path("gavetaPino").asInt(br.com.wagner.wagsyspet.agente.impressao.raw.ComandosRaw.PINO_PADRAO),
                e.path("gavetaPulsoMs").asInt(br.com.wagner.wagsyspet.agente.impressao.raw.ComandosRaw.PULSO_PADRAO_MS));
    }

    private String ler(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        try {
            JsonNode n = JSON.readTree(new String(bytes, StandardCharsets.UTF_8));
            String v = n.path(CAMPO_IMPRESSORA).asText(null);
            return v == null || v.isBlank() ? null : v;
        } catch (IOException | RuntimeException e) {
            log.warn("config.json ilegível em {} ({}); seguindo sem impressora selecionada", arquivo, e.toString());
            return null;
        }
    }

    private void gravarAtomico(String conteudo) throws IOException {
        br.com.wagner.wagsyspet.agente.core.atualizacao.EscritaAtomica.gravarTexto(arquivo, conteudo, false);
    }
}

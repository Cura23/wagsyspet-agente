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
 * <pre>{ "impressoraSelecionada": "EPSON TM-T20" }</pre>
 */
public final class ConfiguracaoLocalArquivo implements ConfiguracaoLocal {

    private static final Logger log = LoggerFactory.getLogger(ConfiguracaoLocalArquivo.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    static final String CAMPO_IMPRESSORA = "impressoraSelecionada";

    private final Path arquivo;
    private volatile String impressora;
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
        String novo = nome == null || nome.isBlank() ? null : nome;
        ObjectNode n = JSON.createObjectNode();
        if (novo != null) {
            n.put(CAMPO_IMPRESSORA, novo);
        }
        String json = n.toPrettyString();
        gravarAtomico(json);
        impressora = novo;
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
        bytesLidos = atual;
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

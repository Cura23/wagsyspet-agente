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
import java.nio.file.attribute.FileTime;
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
    private volatile FileTime lidoEm;

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
        gravarAtomico(n.toPrettyString());
        impressora = novo;
        lidoEm = mtime();
    }

    private synchronized void recarregarSeMudou() {
        FileTime atual = mtime();
        if (Objects.equals(atual, lidoEm)) {
            return;
        }
        impressora = ler();
        lidoEm = atual;
    }

    /** {@code null} quando o arquivo não existe (também é um "estado" que dispara releitura quando ele aparece). */
    private FileTime mtime() {
        try {
            return Files.exists(arquivo) ? Files.getLastModifiedTime(arquivo) : null;
        } catch (IOException e) {
            return null;
        }
    }

    private String ler() {
        if (!Files.exists(arquivo)) {
            return null;
        }
        try {
            JsonNode n = JSON.readTree(Files.readString(arquivo, StandardCharsets.UTF_8));
            String v = n.path(CAMPO_IMPRESSORA).asText(null);
            return v == null || v.isBlank() ? null : v;
        } catch (IOException | RuntimeException e) {
            log.warn("config.json ilegível em {} ({}); seguindo sem impressora selecionada", arquivo, e.toString());
            return null;
        }
    }

    private void gravarAtomico(String conteudo) throws IOException {
        Path dir = arquivo.toAbsolutePath().getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        Path tmp = arquivo.resolveSibling(arquivo.getFileName() + ".tmp");
        Files.writeString(tmp, conteudo, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, arquivo, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, arquivo, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}

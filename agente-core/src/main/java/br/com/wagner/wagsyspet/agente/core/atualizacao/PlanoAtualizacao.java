package br.com.wagner.wagsyspet.agente.core.atualizacao;

import br.com.wagner.wagsyspet.agente.protocolo.release.ManifestoRelease;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code plano.json}: o que o agente entrega ao ATUALIZADOR (processo externo, {@code --aplicar-atualizacao <plano>}) antes de sair
 * (plano F6 D2). Tudo o que o atualizador precisa sem consultar rede: versões, instalador já verificado (caminho + sha256), formato da
 * instalação, launcher atual (para relançar / reverter) e a pasta de dados (para o {@code estado.json}).
 */
public record PlanoAtualizacao(String versaoNova, String versaoAnterior, String artefato, String sha256, String arquivo,
                               ManifestoRelease.FormatoInstalado formato, Optional<String> launcherAtual, String dirDados, Instant criadoEm, Gatilho gatilho) {

    /** Quem pediu: ociosidade (AUTO — nunca pode pedir senha) ou clique/CLI (MANUAL — pode usar pkexec no .deb). */
    public enum Gatilho { AUTO, MANUAL }

    private static final ObjectMapper JSON = new ObjectMapper();

    public PlanoAtualizacao {
        Objects.requireNonNull(versaoNova); Objects.requireNonNull(versaoAnterior); Objects.requireNonNull(artefato);
        Objects.requireNonNull(sha256); Objects.requireNonNull(arquivo); Objects.requireNonNull(formato);
        Objects.requireNonNull(launcherAtual); Objects.requireNonNull(dirDados); Objects.requireNonNull(criadoEm); Objects.requireNonNull(gatilho);
    }

    public void gravar(Path destino) throws IOException {
        ObjectNode n = JSON.createObjectNode();
        n.put("versaoNova", versaoNova).put("versaoAnterior", versaoAnterior).put("artefato", artefato).put("sha256", sha256)
                .put("arquivo", arquivo).put("formato", formato.name()).put("dirDados", dirDados).put("criadoEm", criadoEm.toString()).put("gatilho", gatilho.name());
        launcherAtual.ifPresent(l -> n.put("launcherAtual", l));
        EscritaAtomica.gravarTexto(destino, n.toPrettyString(), false);
    }

    public static PlanoAtualizacao ler(Path origem) throws IOException {
        JsonNode n = JSON.readTree(Files.readString(origem, StandardCharsets.UTF_8));
        if (n == null || !n.isObject()) {
            throw new IOException("plano.json não é objeto");
        }
        try {
            return new PlanoAtualizacao(texto(n, "versaoNova"), texto(n, "versaoAnterior"), texto(n, "artefato"), texto(n, "sha256"),
                    texto(n, "arquivo"), ManifestoRelease.FormatoInstalado.valueOf(texto(n, "formato")),
                    Optional.ofNullable(n.hasNonNull("launcherAtual") ? n.get("launcherAtual").asText() : null),
                    texto(n, "dirDados"), Instant.parse(texto(n, "criadoEm")),
                    n.hasNonNull("gatilho") ? Gatilho.valueOf(n.get("gatilho").asText()) : Gatilho.AUTO);
        } catch (RuntimeException e) {
            throw new IOException("plano.json inválido: " + e.getMessage(), e);
        }
    }

    private static String texto(JsonNode n, String campo) {
        JsonNode v = n.get(campo);
        if (v == null || !v.isTextual()) {
            throw new IllegalArgumentException("campo ausente: " + campo);
        }
        return v.textValue();
    }
}

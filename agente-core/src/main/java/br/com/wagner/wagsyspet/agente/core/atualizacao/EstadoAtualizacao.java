package br.com.wagner.wagsyspet.agente.core.atualizacao;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code atualizacao/estado.json}: a ÚNICA memória do self-update, compartilhada por desktop, CLI e atualizador (plano F6 D4).
 * Escrita atômica, relido por mtime a cada {@link #ler()} (mesmo desenho da {@code ConfiguracaoLocalArquivo}: um processo grava,
 * o outro enxerga), tolerante a lixo (arquivo corrompido vai de lado como {@code estado.json.corrompido-<epoch>} e o estado volta ao
 * {@link Estado#VAZIO}) e aditivo (campos desconhecidos de uma versão futura são ignorados na leitura).
 */
public final class EstadoAtualizacao {

    private static final Logger log = LoggerFactory.getLogger(EstadoAtualizacao.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Instalador já baixado e verificado (sha256 bate), pronto para aplicar. */
    public record ArtefatoBaixado(String versao, String caminho, String sha256) {
        public ArtefatoBaixado {
            Objects.requireNonNull(versao); Objects.requireNonNull(caminho); Objects.requireNonNull(sha256);
        }
    }

    /** Troca em curso: quem sobe depois disto decide se confirma (saúde) ou reverte. */
    public record EmAplicacao(String versaoNova, String versaoAnterior, String artefatoAnterior, Instant iniciadaEm) {
        public EmAplicacao {
            Objects.requireNonNull(versaoNova); Objects.requireNonNull(versaoAnterior); Objects.requireNonNull(iniciadaEm);
        }
    }

    /** Versão que falhou ao aplicar/subir: não tentar de novo até {@code ate} (24 h) nem mais que N vezes. */
    public record Recusada(String versao, Instant ate, int tentativas) {
        public Recusada {
            Objects.requireNonNull(versao); Objects.requireNonNull(ate);
        }
    }

    /** Imutável; cada {@code comX} devolve uma cópia. Campos ausentes = {@code null} internamente, {@code Optional} para fora. */
    public static final class Estado {
        public static final Estado VAZIO = new Estado(null, null, null, null, null, 0, null, null);

        private final Instant ultimaVerificacao;
        private final String etag;
        private final String versaoDisponivel;
        private final ArtefatoBaixado artefatoBaixado;
        private final EmAplicacao emAplicacao;
        private final int tentativasBoot;
        private final Instant confirmadaEm;
        private final Recusada recusada;

        private Estado(Instant ultimaVerificacao, String etag, String versaoDisponivel, ArtefatoBaixado artefatoBaixado,
                       EmAplicacao emAplicacao, int tentativasBoot, Instant confirmadaEm, Recusada recusada) {
            this.ultimaVerificacao = ultimaVerificacao; this.etag = etag; this.versaoDisponivel = versaoDisponivel;
            this.artefatoBaixado = artefatoBaixado; this.emAplicacao = emAplicacao; this.tentativasBoot = tentativasBoot;
            this.confirmadaEm = confirmadaEm; this.recusada = recusada;
        }

        public Optional<Instant> ultimaVerificacao() { return Optional.ofNullable(ultimaVerificacao); }
        public Optional<String> etag() { return Optional.ofNullable(etag); }
        public Optional<String> versaoDisponivel() { return Optional.ofNullable(versaoDisponivel); }
        public Optional<ArtefatoBaixado> artefatoBaixado() { return Optional.ofNullable(artefatoBaixado); }
        public Optional<EmAplicacao> emAplicacao() { return Optional.ofNullable(emAplicacao); }
        public int tentativasBoot() { return tentativasBoot; }
        public Optional<Instant> confirmadaEm() { return Optional.ofNullable(confirmadaEm); }
        public Optional<Recusada> recusada() { return Optional.ofNullable(recusada); }

        public Estado comVerificacao(Instant quando, String etag, String versaoDisponivel) {
            return new Estado(Objects.requireNonNull(quando), etag, versaoDisponivel, artefatoBaixado, emAplicacao, tentativasBoot, confirmadaEm, recusada);
        }
        public Estado comArtefatoBaixado(ArtefatoBaixado a) {
            return new Estado(ultimaVerificacao, etag, versaoDisponivel, a, emAplicacao, tentativasBoot, confirmadaEm, recusada);
        }
        public Estado comEmAplicacao(EmAplicacao e, int tentativasBoot) {
            return new Estado(ultimaVerificacao, etag, versaoDisponivel, artefatoBaixado, e, tentativasBoot, confirmadaEm, recusada);
        }
        public Estado comTentativasBoot(int n) {
            return new Estado(ultimaVerificacao, etag, versaoDisponivel, artefatoBaixado, emAplicacao, n, confirmadaEm, recusada);
        }
        public Estado confirmada(Instant quando) {
            return new Estado(ultimaVerificacao, etag, versaoDisponivel, null, null, 0, Objects.requireNonNull(quando), recusada);
        }
        public Estado comRecusada(Recusada r) {
            return new Estado(ultimaVerificacao, etag, versaoDisponivel, null, null, 0, confirmadaEm, r);
        }

        @Override public boolean equals(Object o) {
            return o instanceof Estado e && Objects.equals(ultimaVerificacao, e.ultimaVerificacao) && Objects.equals(etag, e.etag)
                    && Objects.equals(versaoDisponivel, e.versaoDisponivel) && Objects.equals(artefatoBaixado, e.artefatoBaixado)
                    && Objects.equals(emAplicacao, e.emAplicacao) && tentativasBoot == e.tentativasBoot
                    && Objects.equals(confirmadaEm, e.confirmadaEm) && Objects.equals(recusada, e.recusada);
        }
        @Override public int hashCode() {
            return Objects.hash(ultimaVerificacao, etag, versaoDisponivel, artefatoBaixado, emAplicacao, tentativasBoot, confirmadaEm, recusada);
        }
        @Override public String toString() {
            return "Estado{disponivel=" + versaoDisponivel + ", baixado=" + (artefatoBaixado != null) + ", emAplicacao=" + emAplicacao
                    + ", tentativasBoot=" + tentativasBoot + ", confirmadaEm=" + confirmadaEm + ", recusada=" + recusada + "}";
        }
    }

    private final Path arquivo;
    private Estado cache = Estado.VAZIO;
    /** Bytes da última leitura: o arquivo é minúsculo, então a "releitura" compara CONTEÚDO — mtime não distingue duas escritas no
     *  mesmo tique do relógio do kernel (poucos ms), e é exatamente isso que acontece quando CLI/atualizador gravam logo após o desktop. */
    private byte[] bytesCache;

    public EstadoAtualizacao(Path arquivo) {
        this.arquivo = Objects.requireNonNull(arquivo);
    }

    public Path arquivo() {
        return arquivo;
    }

    public synchronized Estado ler() {
        byte[] atual;
        try {
            atual = Files.exists(arquivo) ? Files.readAllBytes(arquivo) : null;
        } catch (IOException e) {
            log.warn("estado.json ilegível ({}); mantendo o último estado lido", e.toString());
            return cache;
        }
        if (bytesCache != null && java.util.Arrays.equals(atual, bytesCache)) {
            return cache;
        }
        if (atual == null) {
            cache = Estado.VAZIO;
            bytesCache = null;
            return cache;
        }
        cache = carregar(atual);
        bytesCache = atual;
        return cache;
    }

    public synchronized void gravar(Estado e) throws IOException {
        String json = serializar(Objects.requireNonNull(e));
        EscritaAtomica.gravarTexto(arquivo, json, false);
        cache = e;
        bytesCache = json.getBytes(StandardCharsets.UTF_8);
    }

    private Estado carregar(byte[] bytes) {
        try {
            JsonNode n = JSON.readTree(new String(bytes, StandardCharsets.UTF_8));
            if (n == null || !n.isObject()) {
                throw new IOException("raiz não é objeto");
            }
            return desserializar(n);
        } catch (IOException | RuntimeException e) {
            log.warn("estado.json ilegível ({}); movendo de lado e seguindo sem estado", e.toString());
            moverCorrompido();
            return Estado.VAZIO;
        }
    }

    private void moverCorrompido() {
        try {
            Path destino = arquivo.resolveSibling(arquivo.getFileName() + ".corrompido-" + System.currentTimeMillis());
            Files.move(arquivo, destino, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.warn("não consegui mover o estado.json corrompido: {}", e.toString());
        }
    }

    static String serializar(Estado e) {
        ObjectNode n = JSON.createObjectNode();
        e.ultimaVerificacao().ifPresent(v -> n.put("ultimaVerificacao", v.toString()));
        e.etag().ifPresent(v -> n.put("etag", v));
        e.versaoDisponivel().ifPresent(v -> n.put("versaoDisponivel", v));
        e.artefatoBaixado().ifPresent(a -> n.putObject("artefatoBaixado").put("versao", a.versao()).put("caminho", a.caminho()).put("sha256", a.sha256()));
        e.emAplicacao().ifPresent(a -> {
            ObjectNode o = n.putObject("emAplicacao").put("versaoNova", a.versaoNova()).put("versaoAnterior", a.versaoAnterior()).put("iniciadaEm", a.iniciadaEm().toString());
            if (a.artefatoAnterior() != null) {
                o.put("artefatoAnterior", a.artefatoAnterior());
            }
        });
        n.put("tentativasBoot", e.tentativasBoot());
        e.confirmadaEm().ifPresent(v -> n.put("confirmadaEm", v.toString()));
        e.recusada().ifPresent(r -> n.putObject("recusada").put("versao", r.versao()).put("ate", r.ate().toString()).put("tentativas", r.tentativas()));
        return n.toPrettyString();
    }

    static Estado desserializar(JsonNode n) {
        Estado e = Estado.VAZIO;
        Instant verif = instante(n, "ultimaVerificacao");
        if (verif != null) {
            e = e.comVerificacao(verif, texto(n, "etag"), texto(n, "versaoDisponivel"));
        }
        JsonNode a = n.get("artefatoBaixado");
        if (a != null && a.isObject() && texto(a, "versao") != null && texto(a, "caminho") != null && texto(a, "sha256") != null) {
            e = e.comArtefatoBaixado(new ArtefatoBaixado(texto(a, "versao"), texto(a, "caminho"), texto(a, "sha256")));
        }
        int tentativas = n.path("tentativasBoot").asInt(0);
        JsonNode ap = n.get("emAplicacao");
        if (ap != null && ap.isObject() && texto(ap, "versaoNova") != null && texto(ap, "versaoAnterior") != null && instante(ap, "iniciadaEm") != null) {
            e = e.comEmAplicacao(new EmAplicacao(texto(ap, "versaoNova"), texto(ap, "versaoAnterior"), texto(ap, "artefatoAnterior"), instante(ap, "iniciadaEm")), tentativas);
        } else {
            e = e.comTentativasBoot(tentativas);
        }
        Instant conf = instante(n, "confirmadaEm");
        if (conf != null) {
            e = new Estado(e.ultimaVerificacao, e.etag, e.versaoDisponivel, e.artefatoBaixado, e.emAplicacao, e.tentativasBoot, conf, null);
        }
        JsonNode r = n.get("recusada");
        if (r != null && r.isObject() && texto(r, "versao") != null && instante(r, "ate") != null) {
            e = new Estado(e.ultimaVerificacao, e.etag, e.versaoDisponivel, e.artefatoBaixado, e.emAplicacao, e.tentativasBoot, e.confirmadaEm,
                    new Recusada(texto(r, "versao"), instante(r, "ate"), r.path("tentativas").asInt(0)));
        }
        return e;
    }

    private static String texto(JsonNode n, String campo) {
        JsonNode v = n.get(campo);
        return v != null && v.isTextual() ? v.textValue() : null;
    }

    private static Instant instante(JsonNode n, String campo) {
        String t = texto(n, campo);
        if (t == null) {
            return null;
        }
        try {
            return Instant.parse(t);
        } catch (RuntimeException e) {
            return null;
        }
    }
}

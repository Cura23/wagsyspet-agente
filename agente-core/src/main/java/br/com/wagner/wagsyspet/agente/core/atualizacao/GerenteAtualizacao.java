package br.com.wagner.wagsyspet.agente.core.atualizacao;

import br.com.wagner.wagsyspet.agente.core.pareamento.DiretoriosDoAgente;
import br.com.wagner.wagsyspet.agente.protocolo.release.ManifestoRelease;
import br.com.wagner.wagsyspet.agente.protocolo.release.VersaoSemantica;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/**
 * Orquestra o self-update SEM tocar no SO (plano F6 D3/D4): verifica o manifesto (com ETag), baixa o instalador UMA vez para
 * {@code <dados>/atualizacao/baixado/}, decide quando pode aplicar (artefato pronto + ociosidade mínima), grava o {@code plano.json} e
 * o {@code emAplicacao}, e no boot faz de sentinela: 1º boot da versão nova aguarda a saúde (o servidor escutando) para confirmar;
 * 2º boot sem confirmação manda reverter. Quem aplica/relança/reverte de verdade é o atualizador (L2). Thread-safe por
 * {@code synchronized}: desktop, zelador e CLI podem chamar ao mesmo tempo.
 */
public final class GerenteAtualizacao {

    private static final Logger log = LoggerFactory.getLogger(GerenteAtualizacao.class);

    public static final Duration OCIOSIDADE_MINIMA = Duration.ofMinutes(5);
    public static final Duration PRAZO_SAUDE = Duration.ofSeconds(60);
    public static final Duration VERIFICACAO_INICIAL = Duration.ofMinutes(2);
    public static final Duration INTERVALO_VERIFICACAO = Duration.ofHours(6);
    public static final Duration RECUSA = Duration.ofHours(24);
    public static final int BOOTS_ATE_REVERTER = 2;
    public static final String ARQUIVO_PLANO = "plano.json";
    public static final String ARQUIVO_ESTADO = "estado.json";

    public enum Situacao { ATUALIZADO, DISPONIVEL_BAIXADO, ADIADO, RECUSADO, INDISPONIVEL }

    public enum DecisaoBoot { SEGUIR, AGUARDAR_CONFIRMACAO, REVERTER }

    /** Quem guarda o instalador da versão ATUAL para o rollback (Windows — plano F6 D4). Nunca lança; vazio = sem rollback automático. */
    @FunctionalInterface
    public interface GuardaDoAnterior {
        Optional<Path> garantir(String versaoAtual);
    }

    private final DiretoriosDoAgente dirs;
    private final EstadoAtualizacao estado;
    private final ClienteRelease cliente;
    private final VerificadorAtualizacao verificador;
    private final Clock relogio;
    private final Duration ociosidadeMinima;
    private volatile Optional<GuardaDoAnterior> guarda = Optional.empty();

    public GerenteAtualizacao(DiretoriosDoAgente dirs, EstadoAtualizacao estado, ClienteRelease cliente, VerificadorAtualizacao verificador, Clock relogio) {
        this(dirs, estado, cliente, verificador, relogio, OCIOSIDADE_MINIMA);
    }

    /** @param ociosidadeMinima quanto tempo sem sessão autenticada antes de aplicar (testes usam ms) */
    public GerenteAtualizacao(DiretoriosDoAgente dirs, EstadoAtualizacao estado, ClienteRelease cliente, VerificadorAtualizacao verificador, Clock relogio, Duration ociosidadeMinima) {
        this.dirs = Objects.requireNonNull(dirs);
        this.estado = Objects.requireNonNull(estado);
        this.cliente = Objects.requireNonNull(cliente);
        this.verificador = Objects.requireNonNull(verificador);
        this.relogio = Objects.requireNonNull(relogio);
        this.ociosidadeMinima = Objects.requireNonNull(ociosidadeMinima);
    }

    /** Liga a guarda do instalador anterior (só faz sentido onde o instalador substitui o antigo in-place: Windows/MSI). */
    public void guardaAnterior(GuardaDoAnterior guarda) {
        this.guarda = Optional.ofNullable(guarda);
    }

    public String versaoAtual() {
        return verificador.versaoAtual();
    }

    public EstadoAtualizacao estado() {
        return estado;
    }

    public Optional<String> versaoDisponivel() {
        return estado.ler().artefatoBaixado().map(EstadoAtualizacao.ArtefatoBaixado::versao);
    }

    public Optional<EstadoAtualizacao.EmAplicacao> emAplicacao() {
        return estado.ler().emAplicacao();
    }

    /** Consulta o manifesto e, havendo versão nova aceitável, deixa o instalador baixado e verificado. Nunca lança. */
    /**
     * Verifica/baixa sob o monitor e, com a atualização PRONTA, aciona a guarda do instalador anterior FORA dele: são ~70 MB de
     * outro download e, dentro do monitor, prenderiam "Atualizar agora", o pareamento e a confirmação de saúde (adversarial L3).
     * Quem aplica sozinho (zelador) chama isto e o {@code podeAplicar} na MESMA thread — então só aplica depois de a guarda tentar.
     */
    public Situacao verificar() {
        Situacao s = verificarSobMonitor();
        if (s == Situacao.DISPONIVEL_BAIXADO && estado.ler().emAplicacao().isEmpty()) {
            guardarAnterior();
        }
        return s;
    }

    public boolean temGuardaAnterior() {
        return guarda.isPresent();
    }

    private synchronized Situacao verificarSobMonitor() {
        EstadoAtualizacao.Estado e = estado.ler();
        if (e.emAplicacao().isPresent()) {
            return e.artefatoBaixado().isPresent() ? Situacao.DISPONIVEL_BAIXADO : Situacao.ATUALIZADO; // troca em curso: não mexer
        }
        Instant agora = relogio.instant();
        Optional<ClienteRelease.ManifestoBaixado> baixado;
        // ETag só quando já temos o instalador pronto (é o único caso em que "nada mudou" basta); sem artefato o manifesto (1 KB) é
        // baixado inteiro — cobre download que falhou, recusa expirada e estado limpo
        String etagConhecido = e.artefatoBaixado().isPresent() ? e.etag().orElse(null) : null;
        try {
            baixado = cliente.buscarManifesto(etagConhecido);
        } catch (ClienteRelease.ReleaseIndisponivelException ex) {
            log.info("Release indisponível agora ({}); tento depois", ex.getMessage());
            return Situacao.INDISPONIVEL;
        }
        if (baixado.isEmpty()) { // 304: nada mudou desde a última vez e o instalador já está baixado
            gravar(e.comVerificacao(agora, etagConhecido, e.versaoDisponivel().orElse(null)));
            return Situacao.DISPONIVEL_BAIXADO;
        }
        VerificadorAtualizacao.Avaliacao a = verificador.avaliar(baixado.get().json(), baixado.get().assinatura());
        String etag = baixado.get().etag();
        switch (a.resultado()) {
            case RECUSADA -> {
                log.warn("Manifesto de release recusado: {}", a.motivo());
                gravar(e.comVerificacao(agora, null, null)); // sem ETag: da próxima vez baixa de novo (pode ter sido corrigido)
                return Situacao.RECUSADO;
            }
            case ATUALIZADA -> {
                gravar(limparBaixado(e).comVerificacao(agora, etag, null));
                return Situacao.ATUALIZADO;
            }
            default -> { /* DISPONIVEL */ }
        }
        ManifestoRelease m = a.manifesto().orElseThrow();
        ManifestoRelease.Artefato art = a.artefato().orElseThrow();
        Optional<EstadoAtualizacao.Recusada> recusada = e.recusada().filter(r -> VersaoSemantica.comparar(r.versao(), m.versao()) == 0 && r.ate().isAfter(agora));
        if (recusada.isPresent()) {
            log.info("Versão {} recusada até {} (falhou ao aplicar); não baixo de novo", m.versao(), recusada.get().ate());
            gravar(e.comVerificacao(agora, etag, m.versao()));
            return Situacao.ADIADO;
        }
        Path destino = dirs.atualizacao().resolve("baixado").resolve(art.arquivo());
        Optional<EstadoAtualizacao.ArtefatoBaixado> ja = e.artefatoBaixado()
                .filter(b -> b.sha256().equals(art.sha256()) && Files.isRegularFile(Path.of(b.caminho())) && shaConfere(Path.of(b.caminho()), art.sha256()));
        if (ja.isEmpty()) {
            try {
                cliente.baixarArtefato(art, destino);
            } catch (ClienteRelease.ReleaseIndisponivelException ex) {
                log.warn("Não consegui baixar {}: {}", art.arquivo(), ex.getMessage());
                gravar(limparBaixado(e).comVerificacao(agora, null, m.versao()));
                return Situacao.INDISPONIVEL;
            }
            e = limparBaixado(e).comArtefatoBaixado(new EstadoAtualizacao.ArtefatoBaixado(m.versao(), destino.toString(), art.sha256()));
            log.info("Atualização {} pronta para aplicar quando o caixa estiver ocioso ({})", m.versao(), destino.getFileName());
        }
        gravar(e.comVerificacao(agora, etag, m.versao()));
        return Situacao.DISPONIVEL_BAIXADO;
    }

    /**
     * Com a atualização pronta, garante o instalador da versão ATUAL em {@code anterior/} ANTES de aplicar (o rollback do Windows depende
     * dele). Barato quando já está lá (só sha local); falha de rede/servidor não impede a atualização — fica sem rollback automático.
     */
    private void guardarAnterior() {
        guarda.ifPresent(g -> {
            try {
                Optional<Path> exe = g.garantir(verificador.versaoAtual());
                if (exe.isEmpty()) {
                    log.info("Sem instalador da versão atual ({}) guardado: a atualização segue sem rollback automático", verificador.versaoAtual());
                }
            } catch (RuntimeException ex) {
                log.warn("Guarda do instalador anterior falhou: {}", ex.toString());
            }
        });
    }

    /** Pode aplicar agora? Instalador baixado E (ocioso há pelo menos {@link #OCIOSIDADE_MINIMA}). */
    public boolean podeAplicar(boolean ocioso, Duration ociosoHa) {
        return estado.ler().artefatoBaixado().isPresent() && estado.ler().emAplicacao().isEmpty()
                && ocioso && ociosoHa.compareTo(ociosidadeMinima) >= 0;
    }

    /** Grava o {@code plano.json} e marca {@code emAplicacao}; devolve o caminho do plano para o lançador. */
    public synchronized Path prepararAplicacao(String versaoAtual, Optional<Path> launcherAtual, ManifestoRelease.FormatoInstalado formato) throws IOException {
        return prepararAplicacao(versaoAtual, launcherAtual, formato, PlanoAtualizacao.Gatilho.AUTO);
    }

    public synchronized Path prepararAplicacao(String versaoAtual, Optional<Path> launcherAtual, ManifestoRelease.FormatoInstalado formato, PlanoAtualizacao.Gatilho gatilho) throws IOException {
        EstadoAtualizacao.Estado e = estado.ler();
        EstadoAtualizacao.ArtefatoBaixado b = e.artefatoBaixado().orElseThrow(() -> new IllegalStateException("nada baixado para aplicar"));
        if (!shaConfere(Path.of(b.caminho()), b.sha256())) {
            gravar(limparBaixado(e));
            throw new IOException("instalador baixado não confere mais com o sha256 (" + b.caminho() + "); descartado");
        }
        PlanoAtualizacao plano = new PlanoAtualizacao(b.versao(), versaoAtual, b.caminho(), b.sha256(), Path.of(b.caminho()).getFileName().toString(),
                formato, launcherAtual.map(Path::toString), dirs.raiz().toString(), relogio.instant(), gatilho);
        Path destino = dirs.atualizacao().resolve(ARQUIVO_PLANO);
        plano.gravar(destino);
        gravar(e.comEmAplicacao(new EstadoAtualizacao.EmAplicacao(b.versao(), versaoAtual, null, relogio.instant()), 0));
        log.info("Plano de atualização {} → {} gravado em {}", versaoAtual, b.versao(), destino);
        return destino;
    }

    /** Desfaz {@link #prepararAplicacao} quando o lançador falhou antes de sair (o agente continua na versão atual). */
    public synchronized void abortarAplicacao(String motivo) {
        EstadoAtualizacao.Estado e = estado.ler();
        e.emAplicacao().ifPresent(ap -> log.warn("Aplicação de {} abortada: {}", ap.versaoNova(), motivo));
        gravar(e.comEmAplicacao(null, 0));
    }

    /** Sentinela de boot: conta a tentativa e decide. */
    public synchronized DecisaoBoot avaliarBoot() {
        EstadoAtualizacao.Estado e = estado.ler();
        if (e.emAplicacao().isEmpty()) {
            return DecisaoBoot.SEGUIR;
        }
        if (VersaoSemantica.comparar(e.emAplicacao().get().versaoNova(), verificador.versaoAtual()) != 0) {
            // quem subiu é o binário ANTIGO (o atualizador ainda não trocou, ou não conseguiu): não é tentativa da versão nova —
            // contar aqui faria o 1º boot legítimo dela virar REVERTER (adversarial L3). O confirmar() decide (recusa por 24 h).
            log.warn("Boot da versão {} com troca para {} pendente: não conta como tentativa", verificador.versaoAtual(), e.emAplicacao().get().versaoNova());
            return DecisaoBoot.AGUARDAR_CONFIRMACAO;
        }
        int tentativas = e.tentativasBoot() + 1;
        gravar(e.comTentativasBoot(tentativas));
        if (tentativas >= BOOTS_ATE_REVERTER) {
            log.error("Atualização para {} não foi confirmada em {} boots — reverter", e.emAplicacao().get().versaoNova(), tentativas);
            return DecisaoBoot.REVERTER;
        }
        return DecisaoBoot.AGUARDAR_CONFIRMACAO;
    }

    /**
     * Chamado quando o servidor está escutando há {@link #PRAZO_SAUDE}: se ESTA é a versão nova, confirma e apaga o instalador; se ainda
     * é a antiga (o atualizador não aplicou), marca a nova como recusada por 24 h.
     */
    public synchronized void confirmar() {
        EstadoAtualizacao.Estado e = estado.ler();
        Optional<EstadoAtualizacao.EmAplicacao> ap = e.emAplicacao();
        if (ap.isEmpty()) {
            return;
        }
        Instant agora = relogio.instant();
        if (VersaoSemantica.comparar(ap.get().versaoNova(), verificador.versaoAtual()) == 0) {
            log.info("Atualização para {} CONFIRMADA (servidor saudável)", ap.get().versaoNova());
            apagarBaixado(e);
            gravar(e.confirmada(agora));
        } else {
            log.warn("Agente subiu na versão {} com atualização para {} pendente: o atualizador não aplicou — recusando por 24 h",
                    verificador.versaoAtual(), ap.get().versaoNova());
            apagarBaixado(e);
            gravar(e.comRecusada(new EstadoAtualizacao.Recusada(ap.get().versaoNova(), agora.plus(RECUSA), e.recusada().map(r -> r.tentativas() + 1).orElse(1))));
        }
    }

    /** Depois de reverter (ou de falhar ao aplicar): a versão fica recusada por 24 h e a troca em curso é esquecida. */
    public synchronized void marcarRevertida(EstadoAtualizacao.EmAplicacao ap, String motivo) {
        registrarRecusa(estado, ap, relogio, motivo);
    }

    /** Mesma regra, para quem só tem o {@code estado.json} (o atualizador externo, sem cliente/verificador). */
    public static void registrarRecusa(EstadoAtualizacao estado, EstadoAtualizacao.EmAplicacao ap, Clock relogio, String motivo) {
        EstadoAtualizacao.Estado e = estado.ler();
        log.warn("Versão {} revertida/recusada: {}", ap.versaoNova(), motivo);
        apagarBaixado(e);
        int tentativas = e.recusada().filter(r -> r.versao().equals(ap.versaoNova())).map(r -> r.tentativas() + 1).orElse(1);
        try {
            estado.gravar(e.comRecusada(new EstadoAtualizacao.Recusada(ap.versaoNova(), relogio.instant().plus(RECUSA), tentativas)));
        } catch (IOException ex) {
            log.error("Não consegui gravar a recusa da versão {}: {}", ap.versaoNova(), ex.toString());
        }
    }

    /** Quem só tem o {@code estado.json} (atualizador) esquece a troca em curso sem recusar a versão. */
    public static void esquecerAplicacao(EstadoAtualizacao estado, String motivo) {
        EstadoAtualizacao.Estado e = estado.ler();
        e.emAplicacao().ifPresent(ap -> log.warn("Aplicação de {} esquecida: {}", ap.versaoNova(), motivo));
        try {
            estado.gravar(e.comEmAplicacao(null, 0));
        } catch (IOException ex) {
            log.error("Não consegui limpar o estado da atualização: {}", ex.toString());
        }
    }

    /** Uma linha para {@code --status} a partir só do {@code estado.json}. */
    public static String resumo(EstadoAtualizacao estado, Clock relogio) {
        EstadoAtualizacao.Estado e = estado.ler();
        if (e.emAplicacao().isPresent()) {
            return "aplicando " + e.emAplicacao().get().versaoNova() + " (boot " + e.tentativasBoot() + ")";
        }
        if (e.artefatoBaixado().isPresent()) {
            return "versão " + e.artefatoBaixado().get().versao() + " baixada, aguardando o caixa ficar ocioso";
        }
        if (e.recusada().filter(r -> r.ate().isAfter(relogio.instant())).isPresent()) {
            return "versão " + e.recusada().get().versao() + " recusada até " + e.recusada().get().ate();
        }
        return e.ultimaVerificacao().map(v -> "em dia (verificado em " + v + ")").orElse("ainda não verificado");
    }

    public String resumo() {
        return resumo(estado, relogio);
    }

    private void gravar(EstadoAtualizacao.Estado e) {
        try {
            estado.gravar(e);
        } catch (IOException ex) {
            log.error("Não consegui gravar o estado da atualização: {}", ex.toString());
        }
    }

    private EstadoAtualizacao.Estado limparBaixado(EstadoAtualizacao.Estado e) {
        apagarBaixado(e);
        return e.comArtefatoBaixado(null);
    }

    private static void apagarBaixado(EstadoAtualizacao.Estado e) {
        e.artefatoBaixado().ifPresent(b -> {
            try {
                Files.deleteIfExists(Path.of(b.caminho()));
            } catch (IOException ex) {
                log.debug("não apaguei {}: {}", b.caminho(), ex.toString());
            }
        });
    }

    static boolean shaConfere(Path arquivo, String sha256) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(arquivo)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    md.update(buf, 0, n);
                }
            }
            return HexFormat.of().formatHex(md.digest()).equalsIgnoreCase(sha256);
        } catch (IOException | NoSuchAlgorithmException e) {
            return false;
        }
    }
}

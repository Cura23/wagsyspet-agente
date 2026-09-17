package br.com.wagner.wagsyspet.agente.app;

import br.com.wagner.wagsyspet.agente.app.autostart.Autostart;
import br.com.wagner.wagsyspet.agente.core.AgenteMain;
import br.com.wagner.wagsyspet.agente.core.ConfiguracaoLocal;
import br.com.wagner.wagsyspet.agente.core.ConfiguracaoLocalArquivo;
import br.com.wagner.wagsyspet.agente.core.MontadorServidor;
import br.com.wagner.wagsyspet.agente.core.PortaImpressao;
import br.com.wagner.wagsyspet.agente.core.ServidorAgente;
import br.com.wagner.wagsyspet.agente.core.SubidaComFallback;
import br.com.wagner.wagsyspet.agente.core.pareamento.ClientePareamento;
import br.com.wagner.wagsyspet.agente.core.pareamento.CofreCredencial;
import br.com.wagner.wagsyspet.agente.core.pareamento.DiretoriosDoAgente;
import br.com.wagner.wagsyspet.agente.core.pareamento.Pareamento;
import br.com.wagner.wagsyspet.agente.core.pareamento.PareamentoException;
import br.com.wagner.wagsyspet.agente.app.ui.AcoesUi;
import br.com.wagner.wagsyspet.agente.app.ui.Superficie;
import br.com.wagner.wagsyspet.agente.impressao.AquecedorPdfBox;
import br.com.wagner.wagsyspet.agente.impressao.ImpressoraJavaxPrint.Resultado;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.CompletableFuture;
import br.com.wagner.wagsyspet.agente.core.atualizacao.GerenteAtualizacao;
import br.com.wagner.wagsyspet.agente.core.atualizacao.LancadorAtualizador;
import br.com.wagner.wagsyspet.agente.core.atualizacao.Reversor;
import br.com.wagner.wagsyspet.agente.core.atualizacao.EstadoAtualizacao;
import br.com.wagner.wagsyspet.agente.protocolo.release.ManifestoRelease;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * O agente rodando como programa de desktop (plano F3 L4): dono do ciclo de vida do {@link ServidorAgente} — sobe se
 * pareado (com fallback de porta), fica vivo NÃO pareado quando há UI para o lojista parear, troca o servidor ao
 * re-parear, para ao desparear, encerra com o código certo:
 * <ul>
 *   <li>0 — saída normal, ou "não reinicie" (não pareado sem UI, instância duplicada);</li>
 *   <li>3 — o servidor morreu depois de subir (supervisor do SO reinicia);</li>
 *   <li>4 — nenhuma porta livre (28421 e 28422 ocupadas) — intervenção humana.</li>
 * </ul>
 * A UI ({@link Superficie}: bandeja ou janela) é opcional (headless = só CLI) e chama as ações ({@link AcoesUi.Agente})
 * em thread própria, nunca na EDT.
 */
final class AgenteDesktop implements AcoesUi.Agente {

    private static final Logger log = LoggerFactory.getLogger(AgenteDesktop.class);
    static final int SAIDA_OK = 0;
    static final int SAIDA_SERVIDOR_MORTO = 3;
    static final int SAIDA_SEM_PORTA = 4;
    /** Versão nova não confirmou saúde em 2 boots → revertida; o supervisor relança a anterior (F6 D4). */
    static final int SAIDA_REVERTIDA = 5;

    /**
     * Peças do self-update (F6-L1), injetáveis para teste: o gerente (verifica/baixa/plano/sentinela), quem lança o atualizador
     * externo, quem reverte, e os prazos (verificação inicial e periódica, intervalo entre tentativas de aplicar, prazo de saúde).
     */
    /**
     * @param aplicaSozinho {@code false} quando o instalador deste SO/formato só funciona com a PESSOA presente (Linux .deb:
     *                      {@code pkexec dpkg -i} pede a senha de administrador). Aí o agente NÃO aplica por ociosidade — sairia,
     *                      o instalador recusaria o gatilho AUTO, o download seria apagado e o clique "Atualizar…" (o único
     *                      caminho que funciona) ficaria bloqueado por 24 h, todo dia (Fecho F6).
     */
    record Atualizacao(GerenteAtualizacao gerente, LancadorAtualizador lancador, Reversor reversor,
                       Duration verificacaoInicial, Duration intervaloVerificacao, Duration intervaloTentativa, Duration prazoSaude,
                       boolean aplicaSozinho) {
        Atualizacao(GerenteAtualizacao gerente, LancadorAtualizador lancador, Reversor reversor,
                    Duration verificacaoInicial, Duration intervaloVerificacao, Duration intervaloTentativa, Duration prazoSaude) {
            this(gerente, lancador, reversor, verificacaoInicial, intervaloVerificacao, intervaloTentativa, prazoSaude, true);
        }

        /** A mesma fiação, para um instalador que exige o clique. */
        Atualizacao assistida() {
            return new Atualizacao(gerente, lancador, reversor, verificacaoInicial, intervaloVerificacao, intervaloTentativa, prazoSaude, false);
        }

        /** @param pausarSupervisor/retomarSupervisor keepalive do Windows — o reversor de lá sai para o atualizador instalar a versão anterior */
        static Atualizacao padrao(DiretoriosDoAgente dirs, String versao, Runnable pausarSupervisor, Runnable retomarSupervisor) {
            ManifestoRelease.FormatoInstalado formato = ComandosAtualizacao.formatoInstalado(Autostart.launcherDesteProcesso(), Path.of(System.getProperty("user.home", ".")));
            LancadorAtualizador deFora = br.com.wagner.wagsyspet.agente.app.atualizacao.LancadorAtualizadorDeFora.padrao(dirs, versao, comando -> {
                try {
                    Path saida = dirs.atualizacao().resolve("atualizador.log");
                    ProcessoFilho.novo(comando).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(saida.toFile())).start();
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
            return new Atualizacao(ComandosAtualizacao.gerentePadrao(dirs, versao), deFora,
                    br.com.wagner.wagsyspet.agente.app.atualizacao.Instaladores.reversorDesteSo(dirs, formato, deFora, pausarSupervisor, retomarSupervisor),
                    GerenteAtualizacao.VERIFICACAO_INICIAL, GerenteAtualizacao.INTERVALO_VERIFICACAO, Duration.ofSeconds(30), GerenteAtualizacao.PRAZO_SAUDE,
                    br.com.wagner.wagsyspet.agente.app.atualizacao.Instaladores.aplicaSozinho(System.getProperty("os.name", ""), formato));
        }
    }
    /** Re-subidas automáticas antes de desistir (exit 3): Windows/Linux-sem-systemd não têm supervisor (adversarial L4-A4). */
    static final int[] ESPERA_RESUBIDA_SEGUNDOS = {3, 10, 30};
    /** Intervalo do zelador que relê o cofre quando a CLI (--parear/--desparear) mexe no pareamento com o agente aberto (C3). */
    static final Duration INTERVALO_ZELADOR = Duration.ofSeconds(5);

    private final DiretoriosDoAgente dirs;
    private final String versao;
    private final int[] portas;
    private volatile Superficie ui;
    private final PrintStream out;
    private final PortaImpressao impressao;
    private final CofreCredencial cofre;
    private final ConfiguracaoLocal config;
    private final ServidorAgente.Prazos prazos = new ServidorAgente.Prazos();
    private final CompletableFuture<Integer> encerramento = new CompletableFuture<>();
    private final Object trocaServidor = new Object();
    private final ScheduledExecutorService zelador = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "agente-zelador");
        t.setDaemon(true);
        return t;
    });
    private volatile ServidorAgente servidor;
    private volatile Pareamento pareamento;
    private volatile boolean aquecido;
    private volatile FileTime cofreVistoEm;
    private final Optional<Atualizacao> atualizacao;
    private volatile boolean aplicandoAtualizacao;
    /**
     * Supervisor que relança sozinho MESMO com saída 0 (só a tarefa keepalive do Windows): pausado quando a pessoa encerra o agente
     * (Sair / erro fatal visto) e quando o agente sai para o atualizador instalar — senão o disparo de 1 min reabre o agente VELHO no
     * meio do msiexec (adversarial L3). Quem reabilita depois é o atualizador (relançamento) ou a próxima subida.
     */
    private volatile Runnable pausarSupervisor = () -> { };
    private volatile Runnable retomarSupervisor = () -> { };
    private volatile long ultimoAvisoAtualizacaoDia = -1;

    AgenteDesktop(DiretoriosDoAgente dirs, String versao, int[] portas, PrintStream out, PortaImpressao impressao) {
        this(dirs, versao, portas, out, impressao, Optional.empty());
    }

    /** @param atualizacao vazio = sem self-update (testes antigos); {@link Atualizacao#padrao} no binário instalado */
    AgenteDesktop(DiretoriosDoAgente dirs, String versao, int[] portas, PrintStream out, PortaImpressao impressao, Optional<Atualizacao> atualizacao) {
        this.dirs = Objects.requireNonNull(dirs);
        this.versao = Objects.requireNonNull(versao);
        this.portas = portas.clone();
        this.out = Objects.requireNonNull(out);
        this.impressao = Objects.requireNonNull(impressao);
        this.cofre = new CofreCredencial(dirs);
        this.config = new ConfiguracaoLocalArquivo(dirs.config());
        this.atualizacao = Objects.requireNonNull(atualizacao);
    }

    /** Liga a superfície visual (bandeja/janela) — antes de {@link #executar()}. Sem chamada = headless. */
    void ui(Superficie superficie) {
        this.ui = superficie;
    }

    // ── ciclo de vida ──────────────────────────────────────────────────────────────────────────────────────────

    /** Bloqueia até o agente encerrar; devolve o código de saída do processo. */
    int executar() throws InterruptedException {
        Runtime.getRuntime().addShutdownHook(new Thread(this::pararServidorSilencioso, "agente-shutdown"));
        boolean aguardarConfirmacao = false;
        if (atualizacao.isPresent()) {
            GerenteAtualizacao g = atualizacao.get().gerente();
            switch (g.avaliarBoot()) {
                case REVERTER -> {
                    EstadoAtualizacao.EmAplicacao ap = g.emAplicacao().orElseThrow();
                    Reversor reversor = atualizacao.get().reversor();
                    boolean revertido = reversor.reverter(ap);
                    if (revertido && reversor.adiada()) {
                        // Windows: a volta foi ENTREGUE ao atualizador de fora; é ele que fecha o estado com o resultado real do MSI
                        // (marcar "revertida" aqui mentiria se o MSI anterior falhar — adversarial L3 r2)
                        log.warn("Reversão {} → {} entregue ao atualizador; saindo para ele instalar", ap.versaoNova(), ap.versaoAnterior());
                        out.println("Atualização para " + ap.versaoNova() + " não confirmou; voltando para " + ap.versaoAnterior() + " (o agente reabre sozinho).");
                        return SAIDA_REVERTIDA;
                    }
                    g.marcarRevertida(ap, revertido ? "revertida para " + ap.versaoAnterior() : "não confirmou saúde; sem reversor neste SO, segue nesta versão");
                    out.println("Atualização para " + ap.versaoNova() + " não confirmou; " + (revertido ? "voltando para " + ap.versaoAnterior() + "." : "seguindo na versão atual."));
                    if (revertido) {
                        return SAIDA_REVERTIDA; // o supervisor relança a versão anterior
                    }
                }
                case AGUARDAR_CONFIRMACAO -> aguardarConfirmacao = true;
                default -> { }
            }
        }
        Optional<Pareamento> p = cofre.ler();
        cofreVistoEm = mtimeCofre();
        if (p.isPresent()) {
            try {
                subir(p.get());
            } catch (SubidaComFallback.NenhumaPortaLivreException e) {
                log.error("Sem porta livre: {}", e.getMessage());
                out.println(e.getMessage());
                if (ui != null) {
                    // diálogo SÍNCRONO (o assíncrono seguido de exit nunca era visto); com uma pessoa avisada, sai 0 para o launchd
                    // do macOS não reabrir em loop a cada 10 s — sem UI (serviço) mantém o 4 = "intervenção humana"
                    ui.erroFatal("Agente de Impressão AgroEase", e.getMessage() + "\n\nFeche o outro programa e abra o agente de novo.");
                    pausarSupervisorAposErroFatal(); // Windows: senão o diálogo volta a cada 1 min
                    return SAIDA_OK;
                }
                return SAIDA_SEM_PORTA;
            } catch (IOException | TimeoutException e) {
                log.error("Servidor não subiu: {}", e.toString());
                out.println("O agente não conseguiu iniciar: " + e.getMessage());
                if (ui != null) {
                    ui.erroFatal("Agente de Impressão AgroEase", "O agente não conseguiu iniciar: " + e.getMessage());
                    pausarSupervisorAposErroFatal(); // a pessoa já viu: o keepalive do Windows não pode reabrir o mesmo erro a cada 1 min (sem bandeja para "Sair")
                }
                return SAIDA_SERVIDOR_MORTO;
            }
        } else if (ui == null) {
            out.println("Este computador ainda não foi pareado com a loja.");
            out.println("Peça o código de pareamento ao responsável (Configurações → Geral → Impressão de Cupom → Gerar código) e rode:");
            out.println("    AgroEase-Agente-Impressao-cli --parear <codigo>");
            return SAIDA_OK; // "não reinicie": sem UI não há como parear daqui
        } else {
            log.info("Agente aberto NÃO pareado; aguardando pareamento pela interface");
            ui.estado("Não pareado", "Use \"Parear…\" com o código gerado no painel da loja.", false);
        }
        zelador.scheduleWithFixedDelay(this::vigiarCofre, INTERVALO_ZELADOR.toMillis(), INTERVALO_ZELADOR.toMillis(), TimeUnit.MILLISECONDS);
        if (atualizacao.isPresent()) {
            Atualizacao at = atualizacao.get();
            if (aguardarConfirmacao) {
                zelador.schedule(this::confirmarSaude, at.prazoSaude().toMillis(), TimeUnit.MILLISECONDS);
            }
            zelador.scheduleWithFixedDelay(this::verificarAtualizacao, at.verificacaoInicial().toMillis(), at.intervaloVerificacao().toMillis(), TimeUnit.MILLISECONDS);
            zelador.scheduleWithFixedDelay(this::tentarAplicarAtualizacao, at.intervaloTentativa().toMillis(), at.intervaloTentativa().toMillis(), TimeUnit.MILLISECONDS);
        }
        int codigo = encerramento.join();
        zelador.shutdownNow();
        pararServidorSilencioso();
        return codigo;
    }

    private FileTime mtimeCofre() {
        try {
            return Files.exists(dirs.pareamento()) ? Files.getLastModifiedTime(dirs.pareamento()) : null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * A linha de comando ({@code --parear}/{@code --desparear}) grava o cofre sem falar com este processo: quando o arquivo muda,
     * o agente aberto aplica sozinho — troca o servidor (novo agenteId/loja) ou para (despareado). Adversarial F3 C3/L4-A2.
     */
    void vigiarCofre() {
        try {
            FileTime agora = mtimeCofre();
            if (Objects.equals(agora, cofreVistoEm)) {
                return;
            }
            cofreVistoEm = agora;
            Optional<Pareamento> novo = cofre.ler();
            synchronized (trocaServidor) {
                Pareamento atual = pareamento;
                if (novo.isEmpty()) {
                    if (servidor != null) {
                        log.info("Pareamento removido por fora (--desparear); parando o servidor");
                        pararServidorSilencioso();
                        pareamento = null;
                        atualizarEstado();
                    }
                    return;
                }
                Pareamento n = novo.get();
                if (atual != null && atual.agenteId().equals(n.agenteId()) && atual.lojaId() == n.lojaId()
                        && atual.chavePublicaTicket().equals(n.chavePublicaTicket()) && atual.origensPermitidas().equals(n.origensPermitidas())) {
                    return; // mesmo pareamento (regravação idêntica)
                }
                log.info("Pareamento alterado por fora (--parear): loja {} agenteId {} — trocando o servidor", n.lojaId(), n.agenteId());
                pararServidorSilencioso();
                subir(n);
            }
        } catch (Exception | Error e) {
            log.error("Zelador do cofre: {}", e.toString());
        }
    }

    private void subir(Pareamento p) throws IOException, InterruptedException, TimeoutException {
        if (!aquecido) {
            // cache de fontes do PDFBox na pasta do agente + varredura em segundo plano: o 1º cupom (Windows) não paga os 15 s
            AquecedorPdfBox.configurarCache(dirs.raiz());
            AquecedorPdfBox.aquecerEmSegundoPlano();
            aquecido = true;
        }
        synchronized (trocaServidor) {
            // a MESMA ConfiguracaoLocal da bandeja/janela vai para o servidor: uma verdade só para a impressora (adversarial A1)
            ServidorAgente s = SubidaComFallback.subir(
                    porta -> MontadorServidor.montar(p, versao, porta, prazos, impressao, config), portas, Duration.ofSeconds(10));
            servidor = s;
            pareamento = p;
            if (java.util.Arrays.stream(portas).noneMatch(x -> x == p.portaSugerida())) {
                log.warn("Backend sugeriu a porta {}, mas o PWA só procura {}: a sugestão é informativa (plano F3 D20)", p.portaSugerida(), java.util.Arrays.toString(portas));
            }
            AgenteMain.vigiar(s);
            s.falhaFatal().thenAccept(causa -> {
                if (servidor == s) { // não foi uma troca voluntária (re-parear/desparear)
                    Thread t = new Thread(() -> aoMorrer(s, causa), "agente-resubida");
                    t.setDaemon(true);
                    t.start();
                }
            });
            log.info("Agente de Impressão AgroEase {} pronto em ws://127.0.0.1:{} (loja {}, agenteId {})", versao, s.getPort(), p.lojaId(), p.agenteId());
            out.println("Agente de Impressão AgroEase " + versao + " pronto em ws://127.0.0.1:" + s.getPort() + " (loja " + p.lojaId() + ").");
            atualizarEstado();
        }
    }

    /**
     * O servidor morreu depois de subir (erro fatal da lib ou watchdog): tenta re-subir sozinho com backoff — no Windows (HKCU Run)
     * e no Linux sem systemd ninguém reiniciaria o processo — e só depois de esgotar as tentativas encerra com 3 (onde há
     * supervisor, ele reinicia). Adversarial F3 L4-A4/C4.
     */
    void aoMorrer(ServidorAgente morto, Exception causa) {
        log.error("Servidor morreu depois de subir ({}); tentando subir de novo", causa == null ? "?" : causa.toString());
        Pareamento p = pareamento;
        synchronized (trocaServidor) {
            if (servidor == morto) {
                pararServidorSilencioso();
            }
        }
        if (p == null) {
            encerramento.complete(SAIDA_SERVIDOR_MORTO);
            return;
        }
        for (int tentativa = 0; tentativa < ESPERA_RESUBIDA_SEGUNDOS.length; tentativa++) {
            try {
                Thread.sleep(ESPERA_RESUBIDA_SEGUNDOS[tentativa] * 1000L);
                if (encerramento.isDone()) {
                    return;
                }
                subir(p);
                log.warn("Servidor re-subiu sozinho na tentativa {}", tentativa + 1);
                if (ui != null) {
                    ui.aviso("Agente de Impressão AgroEase", "O agente reiniciou sozinho depois de uma falha e voltou a funcionar.");
                }
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.error("Re-subida {} falhou: {}", tentativa + 1, e.toString());
            }
        }
        if (ui != null) {
            ui.erroFatal("Agente de Impressão AgroEase", "O agente parou de responder e não conseguiu reiniciar sozinho. Abra o agente de novo.");
            pausarSupervisorAposErroFatal(); // idem: quem reabre é a pessoa, como a mensagem pede
        }
        encerramento.complete(SAIDA_SERVIDOR_MORTO);
    }

    private void atualizarEstado() {
        if (ui == null) {
            return;
        }
        ServidorAgente s = servidor;
        Pareamento p = pareamento;
        if (s == null || p == null) {
            ui.estado("Não pareado", "Use \"Parear…\" com o código gerado no painel da loja.", false);
            return;
        }
        String impressora = config.impressoraSelecionada().orElse("nenhuma escolhida (use \"Impressora…\")");
        ui.estado("Pareado com a loja " + p.lojaId(), "Porta " + s.getPort() + " · impressora: " + impressora, true);
    }

    private void pararServidorSilencioso() {
        synchronized (trocaServidor) {
            ServidorAgente s = servidor;
            servidor = null;
            if (s != null) {
                try {
                    s.stop(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException e) {
                    log.debug("stop(): {}", e.toString());
                }
            }
        }
    }

    // ── self-update (F6-L1) ────────────────────────────────────────────────────────────────────────────────────

    /** Servidor escutando há {@code prazoSaude} depois de um boot com troca pendente → confirma (ou recusa, se ainda é a versão antiga). */
    private void confirmarSaude() {
        atualizacao.ifPresent(at -> {
            ServidorAgente s = servidor;
            if (s != null && s.estaEscutando() || (s == null && pareamento == null)) { // não pareado também é "vivo"
                at.gerente().confirmar();
                atualizarEstado();
            } else {
                log.warn("Servidor não está escutando no prazo de saúde; a confirmação fica para o próximo boot");
            }
        });
    }

    /** Verificação periódica: baixa a versão nova (se houver) e avisa a UI 1× por dia. */
    void verificarAtualizacao() {
        atualizacao.ifPresent(at -> {
            try {
                GerenteAtualizacao.Situacao s = at.gerente().verificar();
                Optional<String> v = at.gerente().versaoDisponivel();
                if (ui != null) {
                    ui.atualizacao(v);
                }
                long dia = System.currentTimeMillis() / 86_400_000L;
                if (s == GerenteAtualizacao.Situacao.DISPONIVEL_BAIXADO && v.isPresent()) {
                    if (ui != null && dia != ultimoAvisoAtualizacaoDia) {
                        ultimoAvisoAtualizacaoDia = dia;
                        ui.aviso("Agente de Impressão AgroEase", at.aplicaSozinho()
                                ? "Versão " + v.get() + " pronta. O agente se atualiza sozinho quando o caixa ficar parado, ou use \"Atualizar\"."
                                : "Versão " + v.get() + " pronta. Clique em \"Atualizar\" para instalar (o sistema vai pedir a senha de administrador).");
                    }
                } else if (s == GerenteAtualizacao.Situacao.ADIADO) {
                    // esgotou as tentativas NESTA máquina: sem este aviso ninguém fica sabendo que o update não entra
                    at.gerente().esgotada().ifPresent(esgotada -> {
                        if (ui != null && dia != ultimoAvisoAtualizacaoDia) {
                            ultimoAvisoAtualizacaoDia = dia;
                            ui.aviso("Agente de Impressão AgroEase", "A versão " + esgotada + " não instalou neste computador depois de "
                                    + GerenteAtualizacao.TENTATIVAS_POR_VERSAO + " tentativas. Baixe o instalador na página do agente e instale por cima.");
                        }
                    });
                }
            } catch (RuntimeException e) {
                log.warn("Verificação de atualização falhou: {}", e.toString());
            }
        });
    }

    /** A cada intervalo: se há versão baixada e o caixa está ocioso há tempo suficiente, aplica. */
    void tentarAplicarAtualizacao() {
        atualizacao.ifPresent(at -> {
            ServidorAgente s = servidor;
            boolean ocioso = s == null || s.ocioso();
            Duration ha = s == null ? Duration.ofDays(1) : s.ociosoHa();
            // instalador assistido (Linux .deb): só o clique aplica — o download fica esperando
            if (at.aplicaSozinho() && !aplicandoAtualizacao && at.gerente().podeAplicar(ocioso, ha)) {
                aplicarAtualizacao("ociosidade");
            }
        });
    }

    @Override
    public Optional<String> atualizacaoDisponivel() {
        return atualizacao.flatMap(at -> at.gerente().versaoDisponivel());
    }

    @Override
    public void atualizarAgora() throws IOException {
        Atualizacao at = atualizacao.orElseThrow(() -> new IllegalStateException("atualização automática indisponível nesta instalação"));
        if (at.gerente().versaoDisponivel().isEmpty()) {
            GerenteAtualizacao.Situacao s = at.gerente().verificar();
            if (s != GerenteAtualizacao.Situacao.DISPONIVEL_BAIXADO) {
                throw new IOException(switch (s) {
                    case ATUALIZADO -> "O agente já está na versão mais recente.";
                    case INDISPONIVEL -> "Não consegui consultar a release agora. Tente de novo em alguns minutos.";
                    case ADIADO -> "Essa versão falhou ao instalar aqui recentemente; nova tentativa em 24 h.";
                    default -> "A release publicada foi recusada pela verificação de segurança.";
                });
            }
        }
        aplicarAtualizacao("pedido manual");
    }

    /**
     * Fecha as conexões com 1001 'ATUALIZANDO', grava o plano, para o servidor, lança o atualizador externo e encerra com 0
     * (o atualizador aplica e relança). Se o lançamento falhar, desfaz e sobe o servidor de volta.
     */
    private void aplicarAtualizacao(String gatilho) {
        Atualizacao at = atualizacao.orElseThrow();
        synchronized (trocaServidor) {
            if (aplicandoAtualizacao || encerramento.isDone()) {
                return;
            }
            aplicandoAtualizacao = true;
            ServidorAgente s = servidor;
            Pareamento p = pareamento;
            Path plano;
            try {
                plano = at.gerente().prepararAplicacao(versao, Autostart.launcherDesteProcesso(),
                        ComandosAtualizacao.formatoInstalado(Autostart.launcherDesteProcesso(), Path.of(System.getProperty("user.home", "."))),
                        "pedido manual".equals(gatilho) ? br.com.wagner.wagsyspet.agente.core.atualizacao.PlanoAtualizacao.Gatilho.MANUAL
                                : br.com.wagner.wagsyspet.agente.core.atualizacao.PlanoAtualizacao.Gatilho.AUTO);
            } catch (IOException | IllegalStateException e) {
                log.warn("Não deu para preparar a atualização ({}): {}", gatilho, e.toString());
                aplicandoAtualizacao = false;
                return;
            }
            if (s != null) {
                s.fecharParaAtualizar();
            }
            if (ui != null) {
                ui.estado("Atualizando…", "O agente volta sozinho em até 1 minuto.", p != null);
            }
            pararServidorSilencioso();
            pausarSupervisor(); // ANTES de sair: o disparo de 1 min do keepalive reabriria o agente velho no meio da instalação
            try {
                at.lancador().lancar(plano);
                log.info("Atualizador lançado ({}); encerrando para ele aplicar {}", gatilho, plano);
                out.println("Atualizando o agente; ele volta sozinho em até 1 minuto.");
                encerramento.complete(SAIDA_OK);
            } catch (IOException | RuntimeException e) { // a fiação real lança UncheckedIOException (ProcessBuilder dentro de lambda)
                log.error("Não consegui lançar o atualizador: {}", e.toString());
                retomarSupervisor();
                at.gerente().abortarAplicacao(e.toString());
                aplicandoAtualizacao = false;
                if (ui != null) {
                    ui.erro("Agente de Impressão AgroEase", "Não consegui iniciar a atualização: " + e.getMessage());
                }
                if (p != null) {
                    try {
                        subir(p);
                    } catch (Exception ex) {
                        log.error("Servidor não voltou depois da falha do atualizador: {}", ex.toString());
                        encerramento.complete(SAIDA_SERVIDOR_MORTO);
                    }
                }
            }
        }
    }

    // ── ações (UI e testes; nunca na EDT) ──────────────────────────────────────────────────────────────────────

    /** Pareia (ou re-pareia) e sobe/troca o servidor. Mensagens da exceção já são para o lojista. */
    @Override
    public void parear(String codigo, String backendUrlOpcional) throws PareamentoException, IOException {
        String url = ComandosPareamento.urlBackend(backendUrlOpcional, System.getenv(), System.getProperties());
        Pareamento novo;
        synchronized (trocaServidor) { // uma ação de pareamento por vez (parear × desparear × zelador) — adversarial C5
            novo = ClientePareamento.padrao(url, versao).parear(codigo);
            cofre.gravar(novo);
            cofreVistoEm = mtimeCofre();
            pararServidorSilencioso();
            try {
                subir(novo);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrompido ao subir o servidor", e);
            } catch (TimeoutException e) {
                throw new IOException("o servidor não subiu a tempo", e);
            }
        }
        String autostart = ComandosAutostart.padrao(dirs, out, out).ativarAposPareamento().map(m -> "\n" + m).orElse("");
        if (ui != null) {
            ui.aviso("Pareado", "Este computador foi pareado com a loja " + novo.lojaId() + ".\nAgora escolha a impressora em \"Impressora…\"." + autostart);
        }
    }

    @Override
    public void desparear() throws IOException {
        synchronized (trocaServidor) {
            pararServidorSilencioso();
            pareamento = null;
            cofre.apagar();
            cofreVistoEm = mtimeCofre();
        }
        atualizarEstado();
        log.info("Despareado pela interface");
    }

    @Override
    public List<String> listarImpressoras() {
        return impressao.listar();
    }

    @Override
    public Optional<String> impressoraSelecionada() {
        return config.impressoraSelecionada();
    }

    @Override
    public void selecionarImpressora(String nome) throws IOException {
        if (!impressao.listar().contains(nome)) {
            throw new IOException("Impressora '" + nome + "' não encontrada neste computador");
        }
        config.impressoraSelecionada(nome);
        log.info("Impressora deste computador selecionada pela interface: {}", nome);
        atualizarEstado();
    }

    // ── F6-L5: gaveta e corte pela interface do agente (loja que não usa o painel do PWA) ────────────────────────────

    @Override
    public Optional<br.com.wagner.wagsyspet.agente.core.ExtrasImpressao> extrasDaImpressora() {
        return config.extrasAtivos();
    }

    /**
     * A autorização é do HARDWARE que o lojista viu e testou: {@code valores.impressora()} é a impressora que o diálogo mostrava. Se a
     * selecionada mudou com o diálogo aberto (painel do PWA, "Impressora…"), nada é testado nem ligado na outra (adversarial L5).
     */
    private String impressoraDoDialogo(br.com.wagner.wagsyspet.agente.core.ExtrasImpressao valores, String paraQue) throws IOException {
        String selecionada = config.impressoraSelecionada().orElseThrow(() -> new IOException("Escolha a impressora deste computador antes " + paraQue));
        if (!selecionada.equals(valores.impressora())) {
            throw new IOException("A impressora deste computador mudou para '" + selecionada + "'. Abra \"Gaveta e corte…\" de novo.");
        }
        return selecionada;
    }

    @Override
    public void configurarExtras(br.com.wagner.wagsyspet.agente.core.ExtrasImpressao pedido) throws IOException {
        String impressora = impressoraDoDialogo(pedido, "de ligar a gaveta ou o corte");
        var extras = new br.com.wagner.wagsyspet.agente.core.ExtrasImpressao(impressora, pedido.dialeto(), pedido.gaveta(), pedido.corte(), pedido.gavetaPino(), pedido.gavetaPulsoMs());
        config.extras(extras.algumLigado() ? extras : null);
        log.info("Gaveta/corte configurados pela interface para '{}': dialeto={} gaveta={} corte={} pino={} pulso={} ms",
                impressora, extras.dialeto(), extras.gaveta(), extras.corte(), extras.gavetaPino(), extras.gavetaPulsoMs());
    }

    /** "Testar": manda os bytes do catálogo com os valores do DIÁLOGO, sem ligar nada — liga-se só depois de ver o efeito físico. */
    @Override
    public String testarGaveta(br.com.wagner.wagsyspet.agente.core.ExtrasImpressao valores) throws IOException {
        return testarRaw(valores, "gaveta", br.com.wagner.wagsyspet.agente.impressao.raw.ComandosRaw.abrirGaveta(valores.dialeto(), valores.gavetaPino(), valores.gavetaPulsoMs()));
    }

    @Override
    public String testarCorte(br.com.wagner.wagsyspet.agente.core.ExtrasImpressao valores) throws IOException {
        return testarRaw(valores, "corte", br.com.wagner.wagsyspet.agente.impressao.raw.ComandosRaw.cortar(valores.dialeto()));
    }

    private String testarRaw(br.com.wagner.wagsyspet.agente.core.ExtrasImpressao valores, String qual, byte[] bytes) throws IOException {
        String impressora = impressoraDoDialogo(valores, "do teste");
        Resultado r = impressao.enviarRaw(bytes, impressora, "AgroEase " + qual + " teste");
        log.info("Teste de {} pela interface em '{}' → {} ({})", qual, impressora, r.estado(), r.detalhe());
        if (!r.aceito()) {
            throw new IOException("A impressora '" + impressora + "' não aceitou o comando. Confira se está ligada. Detalhe no log.");
        }
        return "Comando enviado para " + impressora + ".";
    }

    Resultado imprimirTesteResultado() throws IOException {
        String impressora = config.impressoraSelecionada().orElseThrow(() -> new IOException("Escolha a impressora deste computador antes do teste"));
        byte[] pdf = PdfTeste.cupom80mm(Main.NOME_PRODUTO, versao + " · teste pela bandeja");
        return impressao.imprimir(pdf, impressora, "AgroEase teste");
    }

    @Override
    public String imprimirTeste() throws IOException {
        Resultado r = imprimirTesteResultado();
        if (!r.aceito()) {
            throw new IOException(r.estado() == Resultado.Estado.IMPRESSORA_INDISPONIVEL
                    ? "Impressora '" + r.impressora() + "' não encontrada ou parada. Confira se está ligada e instalada."
                    : "Não foi possível enviar o teste para '" + r.impressora() + "'. Detalhe no log.");
        }
        return "Enviado para " + r.impressora() + " (" + r.estado() + ").";
    }

    @Override
    public Path pastaLogs() {
        return dirs.logs();
    }

    @Override
    public boolean pareado() {
        return servidor != null;
    }

    /** Para os testes do ciclo de vida (re-subida). */
    ServidorAgente servidorAtual() {
        return servidor;
    }

    Integer porta() {
        ServidorAgente s = servidor;
        return s == null ? null : s.getPort();
    }

    void supervisor(Runnable pausar, Runnable retomar) {
        this.pausarSupervisor = Objects.requireNonNull(pausar);
        this.retomarSupervisor = Objects.requireNonNull(retomar);
    }

    private void pausarSupervisor() {
        try {
            pausarSupervisor.run();
        } catch (RuntimeException e) {
            log.warn("Não consegui pausar o supervisor: {}", e.toString());
        }
    }

    private void retomarSupervisor() {
        try {
            retomarSupervisor.run();
        } catch (RuntimeException e) {
            log.warn("Não consegui retomar o supervisor: {}", e.toString());
        }
    }

    /**
     * Erro fatal que a pessoa já viu: pausa o supervisor para o mesmo diálogo não voltar a cada minuto — MENOS com uma troca de versão
     * pendente de confirmação: aí o supervisor TEM de dar o 2º boot, que é o que leva a sentinela ao REVERTER (adversarial L3 r2).
     */
    private void pausarSupervisorAposErroFatal() {
        boolean sobSentinela = atualizacao.map(at -> at.gerente().emAplicacao().isPresent()).orElse(false);
        if (sobSentinela) {
            log.warn("Erro fatal com troca de versão pendente: supervisor mantido para o 2º boot (sentinela)");
            return;
        }
        pausarSupervisor();
    }

    private void sairDefinitivo(int codigo) {
        pausarSupervisor();
        encerramento.complete(codigo);
    }

    @Override
    public void sair() {
        log.info("Saída pedida pela interface");
        sairDefinitivo(SAIDA_OK);
    }
}

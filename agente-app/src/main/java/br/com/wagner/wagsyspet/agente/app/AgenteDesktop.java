package br.com.wagner.wagsyspet.agente.app;

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

    AgenteDesktop(DiretoriosDoAgente dirs, String versao, int[] portas, PrintStream out, PortaImpressao impressao) {
        this.dirs = Objects.requireNonNull(dirs);
        this.versao = Objects.requireNonNull(versao);
        this.portas = portas.clone();
        this.out = Objects.requireNonNull(out);
        this.impressao = Objects.requireNonNull(impressao);
        this.cofre = new CofreCredencial(dirs);
        this.config = new ConfiguracaoLocalArquivo(dirs.config());
    }

    /** Liga a superfície visual (bandeja/janela) — antes de {@link #executar()}. Sem chamada = headless. */
    void ui(Superficie superficie) {
        this.ui = superficie;
    }

    // ── ciclo de vida ──────────────────────────────────────────────────────────────────────────────────────────

    /** Bloqueia até o agente encerrar; devolve o código de saída do processo. */
    int executar() throws InterruptedException {
        Runtime.getRuntime().addShutdownHook(new Thread(this::pararServidorSilencioso, "agente-shutdown"));
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
                    return SAIDA_OK;
                }
                return SAIDA_SEM_PORTA;
            } catch (IOException | TimeoutException e) {
                log.error("Servidor não subiu: {}", e.toString());
                out.println("O agente não conseguiu iniciar: " + e.getMessage());
                if (ui != null) {
                    ui.erroFatal("Agente de Impressão AgroEase", "O agente não conseguiu iniciar: " + e.getMessage());
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
        String autostart = ComandosAutostart.padrao(out, out).ativarAposPareamento().map(m -> "\n" + m).orElse("");
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

    @Override
    public void sair() {
        log.info("Saída pedida pela interface");
        encerramento.complete(SAIDA_OK);
    }
}

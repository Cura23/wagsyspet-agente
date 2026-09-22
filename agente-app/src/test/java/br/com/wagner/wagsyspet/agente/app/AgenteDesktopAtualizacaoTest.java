package br.com.wagner.wagsyspet.agente.app;

import br.com.wagner.wagsyspet.agente.core.PortaImpressao;
import br.com.wagner.wagsyspet.agente.core.atualizacao.ClienteRelease;
import br.com.wagner.wagsyspet.agente.core.atualizacao.EstadoAtualizacao;
import br.com.wagner.wagsyspet.agente.core.atualizacao.GerenteAtualizacao;
import br.com.wagner.wagsyspet.agente.core.atualizacao.PlanoAtualizacao;
import br.com.wagner.wagsyspet.agente.core.atualizacao.VerificadorAtualizacao;
import br.com.wagner.wagsyspet.agente.core.pareamento.CofreCredencial;
import br.com.wagner.wagsyspet.agente.core.pareamento.DiretoriosDoAgente;
import br.com.wagner.wagsyspet.agente.core.pareamento.Pareamento;
import br.com.wagner.wagsyspet.agente.impressao.ImpressoraJavaxPrint.Resultado;
import br.com.wagner.wagsyspet.agente.protocolo.release.ChavesRelease;
import br.com.wagner.wagsyspet.agente.protocolo.release.ManifestoRelease;
import br.com.wagner.wagsyspet.agente.protocolo.release.VerificadorAssinaturaRelease;
import br.com.wagner.wagsyspet.agente.protocolo.ticket.AssinadorTicket;
import br.com.wagner.wagsyspet.agente.protocolo.ticket.ChavesTicket;
import br.com.wagner.wagsyspet.agente.protocolo.ticket.TicketClaims;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** F6-L1: o desktop verifica, espera ociosidade, fecha com ATUALIZANDO, entrega o plano ao lançador e sai 0; no boot confirma ou reverte. */
@DisplayName("AgenteDesktop — self-update: só aplica ocioso, 'Atualizar agora' fecha ATUALIZANDO, sentinela de boot confirma/reverte")
class AgenteDesktopAtualizacaoTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final PortaImpressao IMPRESSAO_FAKE = new PortaImpressao() {
        @Override public List<String> listar() { return List.of("PDF"); }
        @Override public Optional<String> padrao() { return Optional.of("PDF"); }
        @Override public Resultado imprimir(byte[] pdf, String impressora, String nomeJob) { return new Resultado(Resultado.Estado.ACEITO_SPOOLER, impressora, "fake"); }
    };

    private HttpServer servidor;
    private String base;
    private KeyPair parRelease;
    private ChavesRelease chaves;
    private byte[] json, sig;
    private final byte[] instalador = "instalador-falso-".repeat(50).getBytes(StandardCharsets.UTF_8);
    private final AtomicReference<Path> planoLancado = new AtomicReference<>();
    private volatile boolean falharLancador;
    private final AtomicReference<EstadoAtualizacao.EmAplicacao> revertido = new AtomicReference<>();

    @BeforeEach
    void release() throws Exception {
        parRelease = ChavesTicket.gerar();
        Properties p = new Properties();
        p.setProperty("release.chave.publica", ChavesTicket.exportarPublica(parRelease.getPublic()));
        p.setProperty("release.kid", VerificadorAssinaturaRelease.kid(parRelease.getPublic()));
        chaves = ChavesRelease.de(p);
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/latest.json", ex -> { ex.sendResponseHeaders(200, json.length); try (OutputStream o = ex.getResponseBody()) { o.write(json); } });
        servidor.createContext("/latest.json.sig", ex -> { ex.sendResponseHeaders(200, sig.length); try (OutputStream o = ex.getResponseBody()) { o.write(sig); } });
        servidor.createContext("/i.bin", ex -> { ex.sendResponseHeaders(200, instalador.length); try (OutputStream o = ex.getResponseBody()) { o.write(instalador); } });
        servidor.start();
        base = "http://127.0.0.1:" + servidor.getAddress().getPort();
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(instalador));
        String chave = ManifestoRelease.chaveArtefato(System.getProperty("os.name"), System.getProperty("os.arch"), ManifestoRelease.FormatoInstalado.INSTALADOR).orElseThrow();
        json = ("{\"formato\":1,\"versao\":\"9.9.9\",\"protocolo\":1,\"kid\":\"" + chaves.kidAtual() + "\",\"artefatos\":{\"" + chave + "\":{\"arquivo\":\"AgroEase-Agente-Impressao-9.9.9-x.bin\",\"url\":\"" + base + "/i.bin\",\"sha256\":\"" + sha + "\",\"tamanho\":" + instalador.length + "}}}").getBytes(StandardCharsets.UTF_8);
        Signature s = Signature.getInstance("Ed25519"); s.initSign(parRelease.getPrivate()); s.update(json); sig = s.sign();
    }

    @AfterEach
    void derrubar() { servidor.stop(0); }

    private AgenteDesktop.Atualizacao atualizacao(DiretoriosDoAgente dirs, String versao, Duration ociosidadeMinima) {
        ClienteRelease cliente = new ClienteRelease(URI.create(base + "/latest.json"), Duration.ofSeconds(5), versao);
        VerificadorAtualizacao verificador = VerificadorAtualizacao.destaMaquina(chaves, versao, ManifestoRelease.FormatoInstalado.INSTALADOR);
        GerenteAtualizacao gerente = new GerenteAtualizacao(dirs, new EstadoAtualizacao(dirs.atualizacao().resolve("estado.json")), cliente, verificador, Clock.systemUTC(), ociosidadeMinima);
        return new AgenteDesktop.Atualizacao(gerente, plano -> { if (falharLancador) { throw new java.io.UncheckedIOException(new java.io.IOException("sem atualizador")); } planoLancado.set(plano); } /* a fiação REAL do Windows (ProcessBuilder no lambda "direto") lança UncheckedIOException — adversarial L3 r2 */, ap -> { revertido.set(ap); return true; },
                Duration.ofMillis(200), Duration.ofHours(1), Duration.ofMillis(100), Duration.ofMillis(300));
    }

    private static KeyPair parear(DiretoriosDoAgente dirs) throws Exception {
        KeyPair chavesTicket = ChavesTicket.gerar();
        new CofreCredencial(dirs).gravar(new Pareamento("caixa-1", 7L, null, ChavesTicket.exportarPublica(chavesTicket.getPublic()),
                List.of("https://app.agroease.com.br"), 28421, "1.0.0", "https://wagsyspet-backend-production.up.railway.app", Instant.now()));
        return chavesTicket;
    }

    private static int portaLivre() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) { return s.getLocalPort(); }
    }

    private static CompletableFuture<Integer> executar(AgenteDesktop d) throws Exception {
        CompletableFuture<Integer> codigo = CompletableFuture.supplyAsync(() -> { try { return d.executar(); } catch (InterruptedException e) { throw new IllegalStateException(e); } });
        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!d.pareado() && !codigo.isDone() && System.nanoTime() < limite) { Thread.sleep(50); }
        return codigo;
    }

    @Test
    @DisplayName("versão nova baixada + sessão AUTENTICADA aberta → não aplica; sessão fechada e ociosidade mínima vencida → plano.json entregue ao lançador, saída 0, estado emAplicacao")
    void aplicaSoOcioso(@TempDir Path tmp) throws Exception {
        DiretoriosDoAgente dirs = new DiretoriosDoAgente(tmp);
        KeyPair ticket = parear(dirs);
        int porta = portaLivre();
        AgenteDesktop d = new AgenteDesktop(dirs, "1.0.0-teste", new int[]{porta}, new PrintStream(new ByteArrayOutputStream()), IMPRESSAO_FAKE,
                Optional.of(atualizacao(dirs, "1.0.0-teste", Duration.ofMillis(600))));
        CompletableFuture<Integer> codigo = executar(d);
        assertThat(d.pareado()).isTrue();

        Cliente c = new Cliente(porta, "https://app.agroease.com.br");
        c.connect();
        assertThat(c.abriu.await(5, TimeUnit.SECONDS)).isTrue();
        c.send("{\"tipo\":\"hello\",\"versaoProtocolo\":1}");
        assertThat(c.proxima().get("tipo").asText()).isEqualTo("hello_ok");
        c.send("{\"tipo\":\"auth\",\"ticket\":\"" + new AssinadorTicket(ticket.getPrivate()).assinar(TicketClaims.novo(7L, "caixa-1", Instant.now(), Duration.ofMinutes(10))) + "\"}");
        assertThat(c.proxima().get("tipo").asText()).isEqualTo("auth_ok");

        // a verificação roda (200 ms) e baixa; com a sessão aberta o agente NÃO pode sair
        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (d.atualizacaoDisponivel().isEmpty() && System.nanoTime() < limite) { Thread.sleep(50); }
        assertThat(d.atualizacaoDisponivel()).contains("9.9.9");
        Thread.sleep(1200);
        assertThat(planoLancado.get()).as("sessão autenticada aberta: não aplica").isNull();
        assertThat(codigo.isDone()).isFalse();

        c.close();
        assertThat(codigo.get(10, TimeUnit.SECONDS)).as("ocioso ≥ 600 ms → aplica e sai 0").isEqualTo(AgenteDesktop.SAIDA_OK);
        assertThat(planoLancado.get()).isNotNull();
        PlanoAtualizacao plano = PlanoAtualizacao.ler(planoLancado.get());
        assertThat(plano.versaoNova()).isEqualTo("9.9.9");
        assertThat(plano.versaoAnterior()).isEqualTo("1.0.0-teste");
        assertThat(Path.of(plano.artefato())).exists();
        EstadoAtualizacao.Estado e = new EstadoAtualizacao(dirs.atualizacao().resolve("estado.json")).ler();
        assertThat(e.emAplicacao()).map(EstadoAtualizacao.EmAplicacao::versaoNova).contains("9.9.9");
    }

    @Test
    @DisplayName("'Atualizar agora' com sessão aberta → a conexão recebe close 1001 'ATUALIZANDO', o plano é lançado e o processo sai 0")
    void atualizarAgora(@TempDir Path tmp) throws Exception {
        DiretoriosDoAgente dirs = new DiretoriosDoAgente(tmp);
        KeyPair ticket = parear(dirs);
        int porta = portaLivre();
        AgenteDesktop d = new AgenteDesktop(dirs, "1.0.0-teste", new int[]{porta}, new PrintStream(new ByteArrayOutputStream()), IMPRESSAO_FAKE,
                Optional.of(atualizacao(dirs, "1.0.0-teste", Duration.ofHours(1)))); // ociosidade enorme: só o clique aplica
        CompletableFuture<Integer> codigo = executar(d);
        Cliente c = new Cliente(porta, "https://app.agroease.com.br");
        c.connect();
        assertThat(c.abriu.await(5, TimeUnit.SECONDS)).isTrue();
        c.send("{\"tipo\":\"hello\",\"versaoProtocolo\":1}");
        c.proxima();
        c.send("{\"tipo\":\"auth\",\"ticket\":\"" + new AssinadorTicket(ticket.getPrivate()).assinar(TicketClaims.novo(7L, "caixa-1", Instant.now(), Duration.ofMinutes(10))) + "\"}");
        assertThat(c.proxima().get("tipo").asText()).isEqualTo("auth_ok");
        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (d.atualizacaoDisponivel().isEmpty() && System.nanoTime() < limite) { Thread.sleep(50); }
        assertThat(d.atualizacaoDisponivel()).contains("9.9.9");

        java.util.concurrent.atomic.AtomicInteger pausas = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger retomadas = new java.util.concurrent.atomic.AtomicInteger();
        d.supervisor(pausas::incrementAndGet, retomadas::incrementAndGet);
        d.atualizarAgora();
        assertThat(c.fechou.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(c.codigoFechamento).isEqualTo(1001);
        assertThat(c.motivoFechamento).isEqualTo("ATUALIZANDO");
        assertThat(codigo.get(10, TimeUnit.SECONDS)).isEqualTo(AgenteDesktop.SAIDA_OK);
        assertThat(planoLancado.get()).isNotNull();
        assertThat(pausas.get()).as("saída para ATUALIZAR pausa o keepalive ANTES de sair: senão o tick de 1 min reabre o agente velho no meio do msiexec (adversarial L3); quem reabilita e relança é o atualizador").isEqualTo(1);
        assertThat(retomadas.get()).isZero();
    }

    @Test
    @DisplayName("lançador do atualizador FALHA → o agente continua no ar e o keepalive pausado para atualizar é RETOMADO (senão ficaria sem supervisor até o próximo login)")
    void lancadorFalhaRetomaKeepalive(@TempDir Path tmp) throws Exception {
        DiretoriosDoAgente dirs = new DiretoriosDoAgente(tmp);
        parear(dirs);
        falharLancador = true;
        AgenteDesktop d = new AgenteDesktop(dirs, "1.0.0-teste", new int[]{portaLivre()}, new PrintStream(new ByteArrayOutputStream()), IMPRESSAO_FAKE,
                Optional.of(atualizacao(dirs, "1.0.0-teste", Duration.ofHours(1))));
        java.util.concurrent.atomic.AtomicInteger pausas = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger retomadas = new java.util.concurrent.atomic.AtomicInteger();
        d.supervisor(pausas::incrementAndGet, retomadas::incrementAndGet);
        CompletableFuture<Integer> codigo = executar(d);
        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (d.atualizacaoDisponivel().isEmpty() && System.nanoTime() < limite) { Thread.sleep(50); }
        d.atualizarAgora();
        limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (retomadas.get() == 0 && System.nanoTime() < limite) { Thread.sleep(50); }
        assertThat(pausas.get()).isEqualTo(1);
        assertThat(retomadas.get()).isEqualTo(1);
        assertThat(codigo.isDone()).as("segue servindo na versão atual").isFalse();
        // Fecho F6: a falha do lançador CONTA como tentativa e entra no recuo de 24 h — antes o agente tentava de novo a cada ~5 min
        EstadoAtualizacao.Estado depois = new EstadoAtualizacao(dirs.atualizacao().resolve("estado.json")).ler();
        assertThat(depois.recusada()).map(EstadoAtualizacao.Recusada::versao).contains("9.9.9");
        assertThat(d.atualizacaoDisponivel()).as("nada 'pronto' para aplicar de novo daqui a 5 min").isEmpty();
        d.sair();
        codigo.get(10, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("'Sair' pela interface → saída 0 E o gancho de saída definitiva roda (Windows: schtasks /change /disable — senão o keepalive reabre o agente em 1 min)")
    void sairPausaKeepalive(@TempDir Path tmp) throws Exception {
        DiretoriosDoAgente dirs = new DiretoriosDoAgente(tmp);
        parear(dirs);
        AgenteDesktop d = new AgenteDesktop(dirs, "1.0.0-teste", new int[]{portaLivre()}, new PrintStream(new ByteArrayOutputStream()), IMPRESSAO_FAKE);
        java.util.concurrent.atomic.AtomicInteger pausas = new java.util.concurrent.atomic.AtomicInteger();
        d.supervisor(pausas::incrementAndGet, () -> { });
        CompletableFuture<Integer> codigo = executar(d);
        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (d.porta() == null && System.nanoTime() < limite) { Thread.sleep(50); }
        d.sair();
        assertThat(codigo.get(10, TimeUnit.SECONDS)).isEqualTo(AgenteDesktop.SAIDA_OK);
        assertThat(pausas.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("boot com emAplicacao pendente: 1ª vez sobe e CONFIRMA após o prazo de saúde (estado limpo); 3ª vez sem confirmação → reverte e sai 5 (o 2º boot ainda tenta — Fecho F6)")
    void sentinelaDeBoot(@TempDir Path tmp) throws Exception {
        DiretoriosDoAgente dirs = new DiretoriosDoAgente(tmp);
        parear(dirs);
        EstadoAtualizacao estado = new EstadoAtualizacao(dirs.atualizacao().resolve("estado.json"));
        estado.gravar(EstadoAtualizacao.Estado.VAZIO.comEmAplicacao(new EstadoAtualizacao.EmAplicacao("9.9.9", "1.0.0", null, Instant.now()), 0));

        // esta JVM "é" a 9.9.9: sobe, escuta pelo prazo de saúde (300 ms no teste) e confirma
        AgenteDesktop novo = new AgenteDesktop(dirs, "9.9.9", new int[]{portaLivre()}, new PrintStream(new ByteArrayOutputStream()), IMPRESSAO_FAKE,
                Optional.of(atualizacao(dirs, "9.9.9", Duration.ofHours(1))));
        CompletableFuture<Integer> codigo = executar(novo);
        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (estado.ler().emAplicacao().isPresent() && System.nanoTime() < limite) { Thread.sleep(50); }
        assertThat(estado.ler().emAplicacao()).as("confirmada após a saúde").isEmpty();
        assertThat(estado.ler().confirmadaEm()).isPresent();
        novo.sair();
        assertThat(codigo.get(10, TimeUnit.SECONDS)).isEqualTo(AgenteDesktop.SAIDA_OK);

        // agora simula: aplicou, o novo NÃO confirmou em 2 boots (tentativasBoot já = 2) → este boot é o 3º → reverter e sair 5
        estado.gravar(EstadoAtualizacao.Estado.VAZIO.comEmAplicacao(new EstadoAtualizacao.EmAplicacao("9.9.9", "1.0.0", null, Instant.now()), 2));
        AgenteDesktop segundo = new AgenteDesktop(dirs, "9.9.9", new int[]{portaLivre()}, new PrintStream(new ByteArrayOutputStream()), IMPRESSAO_FAKE,
                Optional.of(atualizacao(dirs, "9.9.9", Duration.ofHours(1))));
        assertThat(segundo.executar()).isEqualTo(AgenteDesktop.SAIDA_REVERTIDA);
        assertThat(revertido.get()).isNotNull();
        assertThat(revertido.get().versaoNova()).isEqualTo("9.9.9");
        EstadoAtualizacao.Estado e = estado.ler();
        assertThat(e.emAplicacao()).isEmpty();
        assertThat(e.recusada()).map(EstadoAtualizacao.Recusada::versao).contains("9.9.9");
    }

    static final class Cliente extends WebSocketClient {
        final CountDownLatch abriu = new CountDownLatch(1);
        final CountDownLatch fechou = new CountDownLatch(1);
        final BlockingQueue<String> recebidas = new LinkedBlockingQueue<>();
        volatile int codigoFechamento; volatile String motivoFechamento = "";
        Cliente(int porta, String origin) { super(URI.create("ws://127.0.0.1:" + porta + "/"), Map.of("Origin", origin)); }
        @Override public void onOpen(ServerHandshake h) { abriu.countDown(); }
        @Override public void onMessage(String m) { recebidas.add(m); }
        @Override public void onClose(int c, String r, boolean remote) { codigoFechamento = c; motivoFechamento = r == null ? "" : r; fechou.countDown(); }
        @Override public void onError(Exception e) { }
        JsonNode proxima() throws Exception { String m = recebidas.poll(5, TimeUnit.SECONDS); assertThat(m).isNotNull(); return JSON.readTree(m); }
    }

    /** Interface falsa: só registra o erro fatal (o diálogo real é síncrono). */
    private static final class UiFalsa implements br.com.wagner.wagsyspet.agente.app.ui.Superficie {
        final java.util.List<String> fatais = new java.util.concurrent.CopyOnWriteArrayList<>();
        @Override public void estado(String titulo, String detalhe, boolean pareado) { }
        final java.util.List<String> avisos = new java.util.concurrent.CopyOnWriteArrayList<>();
        @Override public void aviso(String titulo, String mensagem) { avisos.add(mensagem); }
        @Override public void erro(String titulo, String mensagem) { }
        @Override public void erroFatal(String titulo, String mensagem) { fatais.add(mensagem); }
    }

    @Test
    @DisplayName("erro fatal visto pela pessoa pausa o keepalive — MENOS sob a sentinela: com a troca pendente o supervisor TEM de dar o 2º boot, que é o que leva ao REVERTER (pausar ali desligaria o rollback automático do Windows — adversarial L3 r2)")
    void erroFatalSobSentinelaNaoPausa(@TempDir Path tmp) throws Exception {
        try (java.net.ServerSocket ocupante = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            int porta = ocupante.getLocalPort();
            // (1) sem troca pendente: erro fatal visto → pausa
            DiretoriosDoAgente normal = new DiretoriosDoAgente(tmp.resolve("normal"));
            parear(normal);
            AgenteDesktop a = new AgenteDesktop(normal, "9.9.9", new int[]{porta}, new PrintStream(new ByteArrayOutputStream()), IMPRESSAO_FAKE,
                    Optional.of(atualizacao(normal, "9.9.9", Duration.ofHours(1))));
            UiFalsa uiA = new UiFalsa(); a.ui(uiA);
            java.util.concurrent.atomic.AtomicInteger pausasA = new java.util.concurrent.atomic.AtomicInteger();
            a.supervisor(pausasA::incrementAndGet, () -> { });
            a.executar();
            assertThat(uiA.fatais).hasSize(1);
            assertThat(pausasA.get()).isEqualTo(1);

            // (2) 1º boot da versão nova (emAplicacao pendente) que não consegue subir: NÃO pausa
            DiretoriosDoAgente sentinela = new DiretoriosDoAgente(tmp.resolve("sentinela"));
            parear(sentinela);
            new EstadoAtualizacao(sentinela.atualizacao().resolve("estado.json")).gravar(EstadoAtualizacao.Estado.VAZIO
                    .comEmAplicacao(new EstadoAtualizacao.EmAplicacao("9.9.9", "1.0.0", null, Instant.now()), 0));
            AgenteDesktop b = new AgenteDesktop(sentinela, "9.9.9", new int[]{porta}, new PrintStream(new ByteArrayOutputStream()), IMPRESSAO_FAKE,
                    Optional.of(atualizacao(sentinela, "9.9.9", Duration.ofHours(1))));
            UiFalsa uiB = new UiFalsa(); b.ui(uiB);
            java.util.concurrent.atomic.AtomicInteger pausasB = new java.util.concurrent.atomic.AtomicInteger();
            b.supervisor(pausasB::incrementAndGet, () -> { });
            b.executar();
            assertThat(pausasB.get()).as("sob sentinela o keepalive fica ligado para dar o 2º boot").isZero();
        }
    }

    @Test
    @DisplayName("reversão ADIADA (Windows: quem instala a anterior é o atualizador de fora): a sentinela NÃO marca 'revertida' nem limpa o emAplicacao — quem fecha o estado é o atualizador, com o resultado REAL do MSI (adversarial L3 r2); sai 5")
    void reversaoAdiadaNaoMarcaRevertida(@TempDir Path tmp) throws Exception {
        DiretoriosDoAgente dirs = new DiretoriosDoAgente(tmp);
        parear(dirs);
        EstadoAtualizacao estado = new EstadoAtualizacao(dirs.atualizacao().resolve("estado.json"));
        estado.gravar(EstadoAtualizacao.Estado.VAZIO.comEmAplicacao(new EstadoAtualizacao.EmAplicacao("9.9.9", "1.0.0", null, Instant.now()), 2));
        AgenteDesktop.Atualizacao base = atualizacao(dirs, "9.9.9", Duration.ofHours(1));
        br.com.wagner.wagsyspet.agente.core.atualizacao.Reversor adiado = new br.com.wagner.wagsyspet.agente.core.atualizacao.Reversor() {
            @Override public boolean reverter(EstadoAtualizacao.EmAplicacao ap) { revertido.set(ap); return true; }
            @Override public boolean adiada() { return true; }
        };
        AgenteDesktop d = new AgenteDesktop(dirs, "9.9.9", new int[]{portaLivre()}, new PrintStream(new ByteArrayOutputStream()), IMPRESSAO_FAKE,
                Optional.of(new AgenteDesktop.Atualizacao(base.gerente(), base.lancador(), adiado, base.verificacaoInicial(), base.intervaloVerificacao(), base.intervaloTentativa(), base.prazoSaude())));
        assertThat(d.executar()).isEqualTo(AgenteDesktop.SAIDA_REVERTIDA);
        assertThat(revertido.get()).isNotNull();
        EstadoAtualizacao.Estado e = estado.ler();
        assertThat(e.emAplicacao()).as("o atualizador precisa dele para reconhecer o plano invertido e fechar o estado").isPresent();
        assertThat(e.recusada()).as("ainda não reverteu de verdade").isEmpty();
    }

    // ---------- Fecho F6: instalador que NÃO aplica sozinho (Linux .deb pede a senha de administrador) ----------

    @Test
    @DisplayName("instalador ASSISTIDO (Linux .deb): ocioso NÃO aplica sozinho — o agente não derruba a si mesmo para o dpkg recusar o AUTO, o download fica esperando o CLIQUE e o aviso diz a verdade ('clique em Atualizar… pede a senha'); o clique aplica")
    void instaladorAssistidoNaoAplicaSozinho(@TempDir Path tmp) throws Exception {
        DiretoriosDoAgente dirs = new DiretoriosDoAgente(tmp);
        parear(dirs);
        AgenteDesktop.Atualizacao base = atualizacao(dirs, "1.0.0-teste", Duration.ofMillis(200));
        AgenteDesktop d = new AgenteDesktop(dirs, "1.0.0-teste", new int[]{portaLivre()}, new PrintStream(new ByteArrayOutputStream()), IMPRESSAO_FAKE,
                Optional.of(base.assistida()));
        UiFalsa ui = new UiFalsa();
        d.ui(ui);
        CompletableFuture<Integer> codigo = executar(d);
        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (d.atualizacaoDisponivel().isEmpty() && System.nanoTime() < limite) { Thread.sleep(50); }
        assertThat(d.atualizacaoDisponivel()).contains("9.9.9");

        Thread.sleep(1200); // ocioso há MUITO mais que os 200 ms mínimos, com a verificação de 100 em 100 ms
        assertThat(planoLancado.get()).as("assistido: nunca aplica por ociosidade").isNull();
        assertThat(codigo.isDone()).isFalse();
        assertThat(d.atualizacaoDisponivel()).as("o download continua esperando o clique").contains("9.9.9");
        assertThat(ui.avisos).anySatisfy(a -> assertThat(a).contains("9.9.9").contains("Atualizar").containsIgnoringCase("senha"));
        assertThat(ui.avisos).noneSatisfy(a -> assertThat(a).containsIgnoringCase("se atualiza sozinho"));

        d.atualizarAgora(); // o clique é o ÚNICO caminho
        assertThat(codigo.get(10, TimeUnit.SECONDS)).isEqualTo(AgenteDesktop.SAIDA_OK);
        assertThat(PlanoAtualizacao.ler(planoLancado.get()).gatilho()).isEqualTo(PlanoAtualizacao.Gatilho.MANUAL);
    }

    @Test
    @DisplayName("verificação SEM novidade deixa rastro no log (INFO 'nenhuma versão nova'): em produção o zelador rodava 4×/dia mudo e o suporte não sabia se ele estava vivo (achado A4 do teste manual)")
    void verificacaoSemNovidadeDeixaRastroNoLog(@TempDir Path tmp) throws Exception {
        DiretoriosDoAgente dirs = new DiretoriosDoAgente(tmp);
        java.util.logging.Logger jul = java.util.logging.Logger.getLogger(GerenteAtualizacao.class.getName());
        java.util.List<java.util.logging.LogRecord> registros = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.logging.Handler handler = new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord r) { registros.add(r); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        java.util.logging.Level nivelAntes = jul.getLevel();
        jul.setLevel(java.util.logging.Level.INFO); // não depender do nível herdado do raiz (outra classe pode tê-lo deixado em WARNING)
        jul.addHandler(handler);
        try {
            // esta JVM "é" a 9.9.9 = a publicada: nada a baixar
            assertThat(atualizacao(dirs, "9.9.9", Duration.ofHours(1)).gerente().verificar()).isEqualTo(GerenteAtualizacao.Situacao.ATUALIZADO);
            assertThat(registros).anySatisfy(r -> {
                assertThat(r.getLevel()).isEqualTo(java.util.logging.Level.INFO);
                assertThat(r.getMessage()).contains("nenhuma versão nova").contains("9.9.9"); // slf4j-jdk14 já entrega a mensagem formatada
            });
        } finally {
            jul.removeHandler(handler);
            jul.setLevel(nivelAntes);
        }
    }

    // ---------- D4: aplicar NO BOOT, antes de abrir a porta (lacuna 1 da auditoria de completude da F6) ----------

    /** Deixa o instalador 9.9.9 BAIXADO no estado (como o zelador de uma sessão anterior teria deixado) sem subir agente nenhum. */
    private AgenteDesktop.Atualizacao comInstaladorBaixado(DiretoriosDoAgente dirs, String versao) {
        AgenteDesktop.Atualizacao base = atualizacao(dirs, versao, Duration.ofHours(1)); // ociosidade enorme: se aplicar, NÃO foi o idle
        assertThat(base.gerente().verificar()).isEqualTo(GerenteAtualizacao.Situacao.DISPONIVEL_BAIXADO);
        return base;
    }

    @Test
    @DisplayName("instalador já baixado numa sessão anterior + boot → aplica ANTES de abrir a porta: sai 0 com o plano (AUTO) entregue ao lançador, keepalive pausado, e a porta NUNCA chegou a escutar (o PDV nem vê o agente velho)")
    void aplicaNoBootAntesDeAbrirAPorta(@TempDir Path tmp) throws Exception {
        DiretoriosDoAgente dirs = new DiretoriosDoAgente(tmp);
        parear(dirs);
        ByteArrayOutputStream saida = new ByteArrayOutputStream();
        AgenteDesktop d = new AgenteDesktop(dirs, "1.0.0-teste", new int[]{portaLivre()}, new PrintStream(saida, true, StandardCharsets.UTF_8), IMPRESSAO_FAKE,
                Optional.of(comInstaladorBaixado(dirs, "1.0.0-teste")));
        java.util.concurrent.atomic.AtomicInteger pausas = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger retomadas = new java.util.concurrent.atomic.AtomicInteger();
        d.supervisor(pausas::incrementAndGet, retomadas::incrementAndGet);
        CompletableFuture<Integer> codigo = executar(d);
        assertThat(codigo.get(10, TimeUnit.SECONDS)).as("saiu para o atualizador aplicar").isEqualTo(AgenteDesktop.SAIDA_OK);
        // "pronto em ws://" só é impresso por subir(): é a prova de que a porta NUNCA abriu (porta()==null vale para qualquer saída — adversarial)
        assertThat(saida.toString(StandardCharsets.UTF_8)).doesNotContain("pronto em ws://").contains("Atualizando o agente");
        assertThat(planoLancado.get()).isNotNull();
        PlanoAtualizacao plano = PlanoAtualizacao.ler(planoLancado.get());
        assertThat(plano.versaoNova()).isEqualTo("9.9.9");
        assertThat(plano.gatilho()).as("boot é automático: o .deb assistido nem chega aqui (aplicaSozinho=false)").isEqualTo(PlanoAtualizacao.Gatilho.AUTO);
        assertThat(pausas.get()).isEqualTo(1);
        assertThat(retomadas.get()).isZero();
        assertThat(new EstadoAtualizacao(dirs.atualizacao().resolve("estado.json")).ler().emAplicacao()).map(EstadoAtualizacao.EmAplicacao::versaoNova).contains("9.9.9");
    }

    @Test
    @DisplayName("boot NÃO aplica quando é o 1º boot da versão nova (sentinela aguardando confirmação) nem quando o instalador é ASSISTIDO (.deb): sobe normal e o download fica esperando")
    void bootNaoAplicaSobSentinelaNemAssistido(@TempDir Path tmp) throws Exception {
        // (1) troca em curso: esta JVM "é" a 9.9.9 recém-instalada, e sobrou um artefato baixado no estado — aplicar de novo seria o LOOP de boot
        DiretoriosDoAgente sentinela = new DiretoriosDoAgente(tmp.resolve("sentinela"));
        parear(sentinela);
        Path baixado = sentinela.atualizacao().resolve("baixado").resolve("i.bin");
        java.nio.file.Files.createDirectories(baixado.getParent());
        java.nio.file.Files.write(baixado, instalador);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(instalador));
        EstadoAtualizacao estado = new EstadoAtualizacao(sentinela.atualizacao().resolve("estado.json"));
        estado.gravar(EstadoAtualizacao.Estado.VAZIO.comArtefatoBaixado(new EstadoAtualizacao.ArtefatoBaixado("9.9.9", baixado.toString(), sha))
                .comEmAplicacao(new EstadoAtualizacao.EmAplicacao("9.9.9", "1.0.0", null, Instant.now()), 0));
        AgenteDesktop novo = new AgenteDesktop(sentinela, "9.9.9", new int[]{portaLivre()}, new PrintStream(new ByteArrayOutputStream()), IMPRESSAO_FAKE,
                Optional.of(atualizacao(sentinela, "9.9.9", Duration.ofHours(1))));
        CompletableFuture<Integer> codigo = executar(novo);
        assertThat(novo.pareado()).as("subiu e abriu a porta").isTrue();
        assertThat(planoLancado.get()).isNull();
        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (estado.ler().emAplicacao().isPresent() && System.nanoTime() < limite) { Thread.sleep(50); }
        assertThat(estado.ler().confirmadaEm()).as("a sentinela confirmou a 9.9.9 normalmente").isPresent();
        novo.sair();
        assertThat(codigo.get(10, TimeUnit.SECONDS)).isEqualTo(AgenteDesktop.SAIDA_OK);

        // (2) instalador assistido (Linux .deb) com o download pronto: o boot não sai sozinho — pkexec pediria senha a ninguém
        DiretoriosDoAgente deb = new DiretoriosDoAgente(tmp.resolve("deb"));
        parear(deb);
        AgenteDesktop assistido = new AgenteDesktop(deb, "1.0.0-teste", new int[]{portaLivre()}, new PrintStream(new ByteArrayOutputStream()), IMPRESSAO_FAKE,
                Optional.of(comInstaladorBaixado(deb, "1.0.0-teste").assistida()));
        CompletableFuture<Integer> codigo2 = executar(assistido);
        assertThat(assistido.pareado()).isTrue();
        assertThat(planoLancado.get()).isNull();
        assertThat(assistido.atualizacaoDisponivel()).as("o download continua esperando o clique").contains("9.9.9");
        assistido.sair();
        assertThat(codigo2.get(10, TimeUnit.SECONDS)).isEqualTo(AgenteDesktop.SAIDA_OK);
    }

    @Test
    @DisplayName("Windows: com guarda configurada e o instalador da versão ATUAL ainda não guardado, o boot NÃO aplica (ficaria sem rollback; a verificação de 2 min retenta a guarda e o caminho ocioso aplica); guardado → aplica")
    void bootNaoAplicaSemInstaladorAnteriorGuardado(@TempDir Path tmp) throws Exception {
        DiretoriosDoAgente semGuarda = new DiretoriosDoAgente(tmp.resolve("sem"));
        parear(semGuarda);
        AgenteDesktop.Atualizacao at = comInstaladorBaixado(semGuarda, "1.0.0-teste");
        at.gerente().guardaAnterior(v -> Optional.empty()); // guarda que nunca conseguiu baixar (rede/404) — guardado() default = vazio
        AgenteDesktop d = new AgenteDesktop(semGuarda, "1.0.0-teste", new int[]{portaLivre()}, new PrintStream(new ByteArrayOutputStream()), IMPRESSAO_FAKE, Optional.of(at));
        CompletableFuture<Integer> codigo = executar(d);
        assertThat(d.pareado()).as("subiu normal").isTrue();
        assertThat(planoLancado.get()).isNull();
        assertThat(d.atualizacaoDisponivel()).as("o download fica esperando").contains("9.9.9");
        d.sair();
        assertThat(codigo.get(10, TimeUnit.SECONDS)).isEqualTo(AgenteDesktop.SAIDA_OK);

        DiretoriosDoAgente comGuarda = new DiretoriosDoAgente(tmp.resolve("com"));
        parear(comGuarda);
        AgenteDesktop.Atualizacao at2 = comInstaladorBaixado(comGuarda, "1.0.0-teste");
        Path exeGuardado = tmp.resolve("anterior.exe");
        at2.gerente().guardaAnterior(new GerenteAtualizacao.GuardaDoAnterior() {
            @Override public Optional<Path> garantir(String v) { return Optional.of(exeGuardado); }
            @Override public Optional<Path> guardado(String v) { return Optional.of(exeGuardado); }
        });
        AgenteDesktop d2 = new AgenteDesktop(comGuarda, "1.0.0-teste", new int[]{portaLivre()}, new PrintStream(new ByteArrayOutputStream()), IMPRESSAO_FAKE, Optional.of(at2));
        assertThat(executar(d2).get(10, TimeUnit.SECONDS)).isEqualTo(AgenteDesktop.SAIDA_OK);
        assertThat(planoLancado.get()).isNotNull();
    }

    @Test
    @DisplayName("instalador baixado da MESMA versão que está rodando (instalou à mão; ou a reversão falhou e o atualizador esqueceu a troca) → o boot NÃO reaplica e o artefato é descartado na próxima verificação")
    void bootNaoReaplicaAMesmaVersao(@TempDir Path tmp) throws Exception {
        DiretoriosDoAgente dirs = new DiretoriosDoAgente(tmp);
        parear(dirs);
        Path baixado = dirs.atualizacao().resolve("baixado").resolve("i.bin");
        java.nio.file.Files.createDirectories(baixado.getParent());
        java.nio.file.Files.write(baixado, instalador);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(instalador));
        EstadoAtualizacao estado = new EstadoAtualizacao(dirs.atualizacao().resolve("estado.json"));
        estado.gravar(EstadoAtualizacao.Estado.VAZIO.comArtefatoBaixado(new EstadoAtualizacao.ArtefatoBaixado("9.9.9", baixado.toString(), sha)));
        AgenteDesktop.Atualizacao at = atualizacao(dirs, "9.9.9", Duration.ofHours(1)); // esta JVM JÁ É a 9.9.9
        AgenteDesktop d = new AgenteDesktop(dirs, "9.9.9", new int[]{portaLivre()}, new PrintStream(new ByteArrayOutputStream()), IMPRESSAO_FAKE, Optional.of(at));
        CompletableFuture<Integer> codigo = executar(d);
        assertThat(d.pareado()).as("subiu normal, sem sair para o atualizador").isTrue();
        assertThat(planoLancado.get()).isNull();
        assertThat(d.atualizacaoDisponivel()).as("a UI não oferece 'Atualizar' para a mesma versão").isEmpty();
        assertThat(at.gerente().verificar()).isEqualTo(GerenteAtualizacao.Situacao.ATUALIZADO);
        assertThat(estado.ler().artefatoBaixado()).as("sobra descartada").isEmpty();
        assertThat(baixado).doesNotExist();
        d.sair();
        assertThat(codigo.get(10, TimeUnit.SECONDS)).isEqualTo(AgenteDesktop.SAIDA_OK);
    }

    @Test
    @DisplayName("lançador do atualizador FALHA no boot → o agente segue: abre a porta normalmente, retoma o keepalive e a versão entra no recuo de 24 h (conta como tentativa)")
    void lancadorFalhaNoBootSegueServindo(@TempDir Path tmp) throws Exception {
        DiretoriosDoAgente dirs = new DiretoriosDoAgente(tmp);
        parear(dirs);
        AgenteDesktop.Atualizacao at = comInstaladorBaixado(dirs, "1.0.0-teste");
        falharLancador = true;
        AgenteDesktop d = new AgenteDesktop(dirs, "1.0.0-teste", new int[]{portaLivre()}, new PrintStream(new ByteArrayOutputStream()), IMPRESSAO_FAKE, Optional.of(at));
        java.util.concurrent.atomic.AtomicInteger pausas = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger retomadas = new java.util.concurrent.atomic.AtomicInteger();
        d.supervisor(pausas::incrementAndGet, retomadas::incrementAndGet);
        CompletableFuture<Integer> codigo = executar(d);
        assertThat(d.pareado()).as("segue servindo na versão atual").isTrue();
        assertThat(codigo.isDone()).isFalse();
        assertThat(pausas.get()).isEqualTo(1);
        assertThat(retomadas.get()).isEqualTo(1);
        EstadoAtualizacao.Estado e = new EstadoAtualizacao(dirs.atualizacao().resolve("estado.json")).ler();
        assertThat(e.recusada()).map(EstadoAtualizacao.Recusada::versao).contains("9.9.9");
        assertThat(e.emAplicacao()).isEmpty();
        assertThat(d.atualizacaoDisponivel()).as("nada 'pronto' para reaplicar no próximo boot").isEmpty();
        d.sair();
        codigo.get(10, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("versão que ESGOTOU as tentativas nesta máquina: o lojista é avisado (1×/dia) de que ela precisa ser instalada à mão — antes ninguém sabia que o update falhava")
    void avisaQuandoEsgota(@TempDir Path tmp) throws Exception {
        DiretoriosDoAgente dirs = new DiretoriosDoAgente(tmp);
        parear(dirs);
        new EstadoAtualizacao(dirs.atualizacao().resolve("estado.json")).gravar(EstadoAtualizacao.Estado.VAZIO
                .comRecusada(new EstadoAtualizacao.Recusada("9.9.9", Instant.now().minus(Duration.ofDays(2)), GerenteAtualizacao.TENTATIVAS_POR_VERSAO)));
        AgenteDesktop d = new AgenteDesktop(dirs, "1.0.0-teste", new int[]{portaLivre()}, new PrintStream(new ByteArrayOutputStream()), IMPRESSAO_FAKE,
                Optional.of(atualizacao(dirs, "1.0.0-teste", Duration.ofMillis(200))));
        UiFalsa ui = new UiFalsa();
        d.ui(ui);
        CompletableFuture<Integer> codigo = executar(d);
        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (ui.avisos.isEmpty() && System.nanoTime() < limite) { Thread.sleep(50); }
        assertThat(ui.avisos).anySatisfy(a -> assertThat(a).contains("9.9.9").containsIgnoringCase("não instalou"));
        assertThat(d.atualizacaoDisponivel()).isEmpty();
        assertThat(planoLancado.get()).isNull();
        d.sair();
        codigo.get(10, TimeUnit.SECONDS);
    }
}

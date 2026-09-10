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
        return new AgenteDesktop.Atualizacao(gerente, plano -> planoLancado.set(plano), ap -> { revertido.set(ap); return true; },
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

        d.atualizarAgora();
        assertThat(c.fechou.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(c.codigoFechamento).isEqualTo(1001);
        assertThat(c.motivoFechamento).isEqualTo("ATUALIZANDO");
        assertThat(codigo.get(10, TimeUnit.SECONDS)).isEqualTo(AgenteDesktop.SAIDA_OK);
        assertThat(planoLancado.get()).isNotNull();
    }

    @Test
    @DisplayName("boot com emAplicacao pendente: 1ª vez sobe e CONFIRMA após o prazo de saúde (estado limpo); 2ª vez sem confirmação → reverte e sai 5")
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

        // agora simula: aplicou, o novo NÃO confirmou (tentativasBoot já = 1) → este boot é o 2º → reverter e sair 5
        estado.gravar(EstadoAtualizacao.Estado.VAZIO.comEmAplicacao(new EstadoAtualizacao.EmAplicacao("9.9.9", "1.0.0", null, Instant.now()), 1));
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
}

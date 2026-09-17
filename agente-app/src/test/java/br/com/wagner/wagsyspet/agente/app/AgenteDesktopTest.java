package br.com.wagner.wagsyspet.agente.app;

import br.com.wagner.wagsyspet.agente.core.PortaImpressao;
import br.com.wagner.wagsyspet.agente.core.pareamento.CofreCredencial;
import br.com.wagner.wagsyspet.agente.core.pareamento.DiretoriosDoAgente;
import br.com.wagner.wagsyspet.agente.core.pareamento.Pareamento;
import br.com.wagner.wagsyspet.agente.impressao.ImpressoraJavaxPrint.Resultado;
import br.com.wagner.wagsyspet.agente.protocolo.ticket.AssinadorTicket;
import br.com.wagner.wagsyspet.agente.protocolo.ticket.ChavesTicket;
import br.com.wagner.wagsyspet.agente.protocolo.ticket.TicketClaims;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.net.URI;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Orquestrador da casca em modo HEADLESS (sem bandeja/janela): o que o CI consegue provar. UI = teste manual na máquina do dono. */
@DisplayName("AgenteDesktop — ciclo de vida sem interface gráfica")
class AgenteDesktopTest {

    private static final PortaImpressao IMPRESSAO_FAKE = new PortaImpressao() {
        @Override public List<String> listar() { return List.of("PDF"); }
        @Override public Optional<String> padrao() { return Optional.of("PDF"); }
        @Override public Resultado imprimir(byte[] pdf, String impressora, String nomeJob) {
            return new Resultado(Resultado.Estado.ACEITO_SPOOLER, impressora, "fake");
        }
    };

    private static int portaLivre() throws Exception {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return s.getLocalPort();
        }
    }

    @Test
    @DisplayName("não pareado e sem UI → orienta a parear e devolve 0 ('não reinicie')")
    void naoPareadoSemUi(@TempDir Path tmp) throws Exception {
        ByteArrayOutputStream saida = new ByteArrayOutputStream();
        AgenteDesktop d = new AgenteDesktop(new DiretoriosDoAgente(tmp), "1.0.0-teste", new int[]{portaLivre()},
                new PrintStream(saida, true, StandardCharsets.UTF_8), IMPRESSAO_FAKE);
        assertThat(d.executar()).isEqualTo(AgenteDesktop.SAIDA_OK);
        assertThat(saida.toString(StandardCharsets.UTF_8)).contains("não foi pareado").contains("--parear");
        assertThat(d.pareado()).isFalse();
    }

    /** Cliente WebSocket mínimo (o ClienteTeste do core não é visível aqui). */
    static final class Cliente extends WebSocketClient {
        final CountDownLatch abriu = new CountDownLatch(1);
        final BlockingQueue<String> recebidas = new LinkedBlockingQueue<>();
        Cliente(int porta, String origin) { super(URI.create("ws://127.0.0.1:" + porta), Map.of("Origin", origin)); }
        @Override public void onOpen(ServerHandshake h) { abriu.countDown(); }
        @Override public void onMessage(String m) { recebidas.add(m); }
        final CountDownLatch fechou = new CountDownLatch(1);
        volatile int codigoFechamento; volatile String motivoFechamento = "";
        @Override public void onClose(int c, String r, boolean remote) { codigoFechamento = c; motivoFechamento = r == null ? "" : r; fechou.countDown(); }
        @Override public void onError(Exception e) { }
        JsonNode proxima() throws Exception {
            String m = recebidas.poll(5, TimeUnit.SECONDS);
            assertThat(m).isNotNull();
            return new ObjectMapper().readTree(m);
        }
    }

    @Test
    @DisplayName("pareado → servidor sobe na porta pedida; impressora escolhida na UI é VISTA pelo servidor (listar → selecionada) e vice-versa (adversarial A1); sair() → 0 e porta fecha")
    void pareadoSobeESai(@TempDir Path tmp) throws Exception {
        DiretoriosDoAgente dirs = new DiretoriosDoAgente(tmp);
        KeyPair chaves = ChavesTicket.gerar();
        new CofreCredencial(dirs).gravar(new Pareamento("caixa-1", 7L, null, ChavesTicket.exportarPublica(chaves.getPublic()),
                List.of("https://app.agroease.com.br"), 28421, "1.0.0", "https://wagsyspet-backend-production.up.railway.app", Instant.now()));
        int porta = portaLivre();
        ByteArrayOutputStream saida = new ByteArrayOutputStream();
        AgenteDesktop d = new AgenteDesktop(dirs, "1.0.0-teste", new int[]{porta}, new PrintStream(saida, true, StandardCharsets.UTF_8), IMPRESSAO_FAKE);

        CompletableFuture<Integer> codigo = CompletableFuture.supplyAsync(() -> {
            try {
                return d.executar();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
        // espera subir
        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!d.pareado() && System.nanoTime() < limite) {
            Thread.sleep(50);
        }
        assertThat(d.pareado()).isTrue();
        assertThat(d.porta()).isEqualTo(porta);
        try (Socket s = new Socket()) {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", porta), 1000);
        }
        d.selecionarImpressora("PDF");
        assertThat(Files.readString(dirs.config())).contains("\"impressoraSelecionada\" : \"PDF\"");
        assertThat(d.imprimirTeste()).contains("PDF");

        // o SERVIDOR (PWA) enxerga a escolha feita na UI — antes eram duas instâncias de ConfiguracaoLocalArquivo em cache
        Cliente c = new Cliente(porta, "https://app.agroease.com.br");
        c.connect();
        assertThat(c.abriu.await(5, TimeUnit.SECONDS)).isTrue();
        c.send("{\"tipo\":\"hello\",\"versaoProtocolo\":1}");
        assertThat(c.proxima().get("tipo").asText()).isEqualTo("hello_ok");
        String ticket = new AssinadorTicket(chaves.getPrivate()).assinar(TicketClaims.novo(7L, "caixa-1", Instant.now(), Duration.ofMinutes(10)));
        c.send("{\"tipo\":\"auth\",\"ticket\":\"" + ticket + "\"}");
        assertThat(c.proxima().get("tipo").asText()).isEqualTo("auth_ok");
        c.send("{\"tipo\":\"listar_impressoras\",\"id\":\"l1\"}");
        JsonNode lista = c.proxima();
        assertThat(lista.get("selecionada").asText()).as("servidor vê a escolha da UI").isEqualTo("PDF");
        // e o inverso: escolha pelo PWA aparece na UI/CLI
        c.send("{\"tipo\":\"selecionar_impressora\",\"id\":\"s1\",\"nome\":\"PDF\"}");
        assertThat(c.proxima().get("tipo").asText()).isEqualTo("selecionar_impressora_ok");
        assertThat(d.impressoraSelecionada()).contains("PDF");
        c.close();

        d.sair();
        assertThat(codigo.get(10, TimeUnit.SECONDS)).isEqualTo(AgenteDesktop.SAIDA_OK);
        assertThat(saida.toString(StandardCharsets.UTF_8)).contains("pronto em ws://127.0.0.1:" + porta);
        // porta liberada
        Thread.sleep(200);
        try (ServerSocket s = new ServerSocket(porta, 1, InetAddress.getLoopbackAddress())) {
            assertThat(s.isBound()).isTrue();
        }
    }

    private static Pareamento pareamento(String agenteId, KeyPair chaves) {
        return new Pareamento(agenteId, 7L, null, ChavesTicket.exportarPublica(chaves.getPublic()),
                List.of("https://app.agroease.com.br"), 28421, "1.0.0", "https://wagsyspet-backend-production.up.railway.app", Instant.now());
    }

    private static AgenteDesktop subirEmSegundoPlano(AgenteDesktop d) throws Exception {
        CompletableFuture.runAsync(() -> {
            try {
                d.executar();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!d.pareado() && System.nanoTime() < limite) {
            Thread.sleep(50);
        }
        assertThat(d.pareado()).isTrue();
        return d;
    }

    private static String agenteIdNoHello(int porta) throws Exception {
        Cliente c = new Cliente(porta, "https://app.agroease.com.br");
        c.connect();
        assertThat(c.abriu.await(5, TimeUnit.SECONDS)).isTrue();
        c.send("{\"tipo\":\"hello\",\"versaoProtocolo\":1}");
        String id = c.proxima().get("agenteId").asText();
        c.close();
        return id;
    }

    @Test
    @DisplayName("agente ABERTO: --parear/--desparear pela CLI (outro processo grava o cofre) → o zelador troca o servidor / para (adversarial C3)")
    void cliMexeNoCofreComAgenteAberto(@TempDir Path tmp) throws Exception {
        DiretoriosDoAgente dirs = new DiretoriosDoAgente(tmp);
        KeyPair chaves = ChavesTicket.gerar();
        CofreCredencial cli = new CofreCredencial(dirs); // "outro processo"
        cli.gravar(pareamento("caixa-antigo", chaves));
        int porta = portaLivre();
        AgenteDesktop d = subirEmSegundoPlano(new AgenteDesktop(dirs, "1.0.0-teste", new int[]{porta},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8), IMPRESSAO_FAKE));
        assertThat(agenteIdNoHello(porta)).isEqualTo("caixa-antigo");

        // re-parear pela CLI: mtime muda → zelador troca o servidor pelo novo agenteId
        Thread.sleep(20);
        cli.gravar(pareamento("caixa-novo", chaves));
        Files.setLastModifiedTime(dirs.pareamento(), java.nio.file.attribute.FileTime.from(Instant.now().plusSeconds(2)));
        d.vigiarCofre();
        assertThat(d.pareado()).isTrue();
        assertThat(agenteIdNoHello(porta)).isEqualTo("caixa-novo");

        // desparear pela CLI → servidor para, porta livre
        cli.apagar();
        d.vigiarCofre();
        assertThat(d.pareado()).isFalse();
        try (ServerSocket s = new ServerSocket(porta, 1, InetAddress.getLoopbackAddress())) {
            assertThat(s.isBound()).isTrue();
        }
        d.sair();
    }

    @Test
    @DisplayName("servidor morre depois de subir → re-sobe sozinho (backoff) em vez de sair com 3 na 1ª falha (adversarial L4-A4)")
    void reSobeSozinho(@TempDir Path tmp) throws Exception {
        DiretoriosDoAgente dirs = new DiretoriosDoAgente(tmp);
        new CofreCredencial(dirs).gravar(pareamento("caixa-1", ChavesTicket.gerar()));
        int porta = portaLivre();
        AgenteDesktop d = subirEmSegundoPlano(new AgenteDesktop(dirs, "1.0.0-teste", new int[]{porta},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8), IMPRESSAO_FAKE));
        var morto = d.servidorAtual();
        morto.stop(1000); // simula a morte: porta fecha
        d.aoMorrer(morto, new java.io.IOException("simulada")); // síncrono no teste: espera 3 s e re-sobe
        assertThat(d.pareado()).isTrue();
        assertThat(d.servidorAtual()).isNotSameAs(morto);
        assertThat(agenteIdNoHello(porta)).isEqualTo("caixa-1");
        d.sair();
    }

    @Test
    @DisplayName("pareado mas a porta pedida está ocupada por outro processo → 4 (nenhuma porta livre), sem loop")
    void semPorta(@TempDir Path tmp) throws Exception {
        DiretoriosDoAgente dirs = new DiretoriosDoAgente(tmp);
        new CofreCredencial(dirs).gravar(new Pareamento("caixa-1", 7L, null, ChavesTicket.exportarPublica(ChavesTicket.gerar().getPublic()),
                List.of("https://app.agroease.com.br"), 28421, "1.0.0", "https://wagsyspet-backend-production.up.railway.app", Instant.now()));
        int porta = portaLivre();
        try (ServerSocket ocupante = new ServerSocket(porta, 1, InetAddress.getLoopbackAddress())) {
            ByteArrayOutputStream saida = new ByteArrayOutputStream();
            AgenteDesktop d = new AgenteDesktop(dirs, "1.0.0-teste", new int[]{porta}, new PrintStream(saida, true, StandardCharsets.UTF_8), IMPRESSAO_FAKE);
            assertThat(d.executar()).isEqualTo(AgenteDesktop.SAIDA_SEM_PORTA);
            assertThat(saida.toString(StandardCharsets.UTF_8)).contains(String.valueOf(porta));
            assertThat(ocupante.isBound()).isTrue();
        }
    }



    @Test
    @DisplayName("F6-L5 pela INTERFACE do agente (loja que não usa o painel do PWA): 'Testar gaveta/corte' manda os bytes do catálogo com os valores do DIÁLOGO, antes de ligar nada (liga-se só depois de ver a gaveta abrir); salvar grava o opt-in para a impressora SELECIONADA; sem impressora escolhida → erro claro; falha do spooler → erro claro")
    void gavetaECortePelaInterface(@TempDir Path tmp) throws Exception {
        DiretoriosDoAgente dirs = new DiretoriosDoAgente(tmp);
        java.util.List<String> raws = new java.util.ArrayList<>();
        java.util.concurrent.atomic.AtomicBoolean falhar = new java.util.concurrent.atomic.AtomicBoolean();
        PortaImpressao motor = new PortaImpressao() {
            @Override public java.util.List<String> listar() { return java.util.List.of("EPSON", "PDF"); }
            @Override public java.util.Optional<String> padrao() { return java.util.Optional.of("EPSON"); }
            @Override public br.com.wagner.wagsyspet.agente.impressao.ImpressoraJavaxPrint.Resultado imprimir(byte[] pdf, String impressora, String nomeJob) { throw new AssertionError("não é PDF"); }
            @Override public br.com.wagner.wagsyspet.agente.impressao.ImpressoraJavaxPrint.Resultado enviarRaw(byte[] bytes, String impressora, String nomeJob) {
                raws.add(impressora + "|" + nomeJob + "|" + java.util.HexFormat.ofDelimiter(" ").withUpperCase().formatHex(bytes));
                return new br.com.wagner.wagsyspet.agente.impressao.ImpressoraJavaxPrint.Resultado(falhar.get()
                        ? br.com.wagner.wagsyspet.agente.impressao.ImpressoraJavaxPrint.Resultado.Estado.ERRO
                        : br.com.wagner.wagsyspet.agente.impressao.ImpressoraJavaxPrint.Resultado.Estado.ACEITO_SPOOLER, impressora, "fake");
            }
        };
        AgenteDesktop d = new AgenteDesktop(dirs, "1.0.0-teste", new int[]{0}, new PrintStream(new ByteArrayOutputStream()), motor);
        var bema = new br.com.wagner.wagsyspet.agente.core.ExtrasImpressao("EPSON", br.com.wagner.wagsyspet.agente.impressao.raw.ComandosRaw.Dialeto.ESC_BEMA, true, true, 2, 100);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> d.testarGaveta(bema)).hasMessageContaining("Escolha a impressora");
        d.selecionarImpressora("EPSON");
        assertThat(d.extrasDaImpressora()).as("default: desligado").isEmpty();

        d.testarGaveta(bema);
        d.testarCorte(bema);
        assertThat(raws).containsExactly("EPSON|AgroEase gaveta teste|1B 76 64", "EPSON|AgroEase corte teste|0A 0A 0A 0A 1B 6D");
        assertThat(d.extrasDaImpressora()).as("testar NÃO liga nada").isEmpty();

        d.configurarExtras(bema);
        var salvo = d.extrasDaImpressora().orElseThrow();
        assertThat(salvo.impressora()).isEqualTo("EPSON");
        assertThat(salvo.dialeto().name()).isEqualTo("ESC_BEMA");
        assertThat(Files.readString(dirs.config())).contains("\"extras\"").contains("\"gavetaPulsoMs\" : 100");

        falhar.set(true);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> d.testarGaveta(bema)).hasMessageContaining("não aceitou");

        d.selecionarImpressora("PDF");
        assertThat(d.extrasDaImpressora()).as("trocar de impressora zera").isEmpty();
        // a impressora mudou com o diálogo (da EPSON) ainda aberto: nem testar nem ligar na impressora que o lojista NÃO viu/testou
        falhar.set(false);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> d.configurarExtras(bema)).hasMessageContaining("mudou");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> d.testarCorte(bema)).hasMessageContaining("mudou");
        assertThat(d.extrasDaImpressora()).isEmpty();
    }
    @Test
    @DisplayName("2ª instância disparada pelo Agendador (--keepalive, a cada 1 min no Windows) com o agente já aberto → sai 0 MUDA: sem mensagem, sem diálogo, sem tocar o log; sem a flag (clique humano) continua avisando")
    void segundaInstanciaDoKeepaliveSaiMuda(@TempDir Path tmp) throws Exception {
        DiretoriosDoAgente dirs = new DiretoriosDoAgente(tmp);
        dirs.garantir();
        try (TravaDeInstancia aberta = TravaDeInstancia.tentar(dirs.lock()).orElseThrow()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (PrintStream pout = new PrintStream(out, true, StandardCharsets.UTF_8)) {
                assertThat(Main.executar(new String[]{"--keepalive", "--dir-dados", tmp.toString()}, pout, pout)).isZero();
            }
            assertThat(out.toString(StandardCharsets.UTF_8)).isEmpty();
            try (var arquivos = Files.list(dirs.logs())) { // a pasta existe (garantir()), mas nenhum arquivo de log é aberto: seriam 1.440 aberturas por dia
                assertThat(arquivos.toList()).isEmpty();
            }

            ByteArrayOutputStream humano = new ByteArrayOutputStream();
            String versaoAntes = System.getProperty("agente.versao");
            System.setProperty("agente.versao", "1.0.0-teste"); // fora do binário empacotado não há MANIFEST; o caminho "humano" anuncia a versão no log
            try (PrintStream pout = new PrintStream(humano, true, StandardCharsets.UTF_8)) {
                assertThat(Main.executar(new String[]{"--sem-bandeja", "--dir-dados", tmp.toString()}, pout, pout)).isZero();
            } finally {
                if (versaoAntes == null) { System.clearProperty("agente.versao"); } else { System.setProperty("agente.versao", versaoAntes); }
                java.util.logging.LogManager.getLogManager().reset(); // fecha o agente-0.log aberto pelo caminho "humano" (no Windows o @TempDir não apaga arquivo aberto)
            }
            assertThat(humano.toString(StandardCharsets.UTF_8)).contains("já está em execução");
        }
    }
    @Test
    @DisplayName("Main.executar: erro de argumento → 2 com o uso; --ajuda → 0; --status com --dir-dados → 0 sem tocar a pasta padrão")
    void mainComandos(@TempDir Path tmp) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream pout = new PrintStream(out, true, StandardCharsets.UTF_8);
        PrintStream perr = new PrintStream(err, true, StandardCharsets.UTF_8);
        System.setProperty("agente.versao", "1.0.0-teste");
        try {
            assertThat(Main.executar(new String[]{"--porta", "abc"}, pout, perr)).isEqualTo(2);
            assertThat(err.toString(StandardCharsets.UTF_8)).contains("Erro:").contains("Uso:");
            assertThat(Main.executar(new String[]{"--ajuda"}, pout, perr)).isEqualTo(0);
            assertThat(Main.executar(new String[]{"--status", "--dir-dados", tmp.toString()}, pout, perr)).isEqualTo(0);
            assertThat(out.toString(StandardCharsets.UTF_8)).contains("NÃO pareado").contains(tmp.toString());
            assertThat(Main.executar(new String[]{"--instalar", "--dir-dados", tmp.toString()}, pout, perr)).isEqualTo(2);
        } finally {
            System.clearProperty("agente.versao");
        }
    }
}

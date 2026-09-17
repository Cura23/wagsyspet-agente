package br.com.wagner.wagsyspet.agente.core.atualizacao;

import br.com.wagner.wagsyspet.agente.protocolo.release.ChavesRelease;
import br.com.wagner.wagsyspet.agente.protocolo.release.VerificadorAssinaturaRelease;
import br.com.wagner.wagsyspet.agente.protocolo.ticket.ChavesTicket;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rollback no Windows (plano F6 D4): o MSI novo substitui o antigo in-place, então reverter exige o instalador da versão ATUAL
 * guardado ANTES da troca. Ele vem da release dessa versão ({@code releases/download/v<atual>/latest.json} assinado), 1× por versão.
 */
@DisplayName("GuardaAnterior — baixa e verifica o instalador da versão atual para anterior/ (1×); versão sem release → vazio sem lançar")
class GuardaAnteriorTest {

    private HttpServer servidor;
    private String base;
    private KeyPair par;
    private ChavesRelease chaves;
    private final byte[] instalador = "instalador-1.0.0-".repeat(100).getBytes(StandardCharsets.UTF_8);
    private final AtomicInteger downloads = new AtomicInteger();
    private final AtomicInteger manifestos = new AtomicInteger();

    @BeforeEach
    void subir() throws Exception {
        par = ChavesTicket.gerar();
        Properties p = new Properties();
        p.setProperty("release.chave.publica", ChavesTicket.exportarPublica(par.getPublic()));
        p.setProperty("release.kid", VerificadorAssinaturaRelease.kid(par.getPublic()));
        chaves = ChavesRelease.de(p);
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + servidor.getAddress().getPort();
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(instalador));
        byte[] json = ("{\"formato\":1,\"versao\":\"1.0.0\",\"protocolo\":1,\"kid\":\"" + chaves.kidAtual() + "\",\"artefatos\":{\"windows\":{\"arquivo\":\"AgroEase-Agente-Impressao-1.0.0-windows-x64.exe\",\"url\":\"" + base + "/v1.0.0/i.exe\",\"sha256\":\"" + sha + "\",\"tamanho\":" + instalador.length + "}}}").getBytes(StandardCharsets.UTF_8);
        Signature s = Signature.getInstance("Ed25519"); s.initSign(par.getPrivate()); s.update(json); byte[] sig = s.sign();
        servidor.createContext("/v1.0.0/latest.json", ex -> { manifestos.incrementAndGet(); ex.sendResponseHeaders(200, json.length); try (OutputStream o = ex.getResponseBody()) { o.write(json); } });
        servidor.createContext("/v1.0.0/latest.json.sig", ex -> { ex.sendResponseHeaders(200, sig.length); try (OutputStream o = ex.getResponseBody()) { o.write(sig); } });
        servidor.createContext("/v1.0.0/i.exe", ex -> { downloads.incrementAndGet(); ex.sendResponseHeaders(200, instalador.length); try (OutputStream o = ex.getResponseBody()) { o.write(instalador); } });
        // v1.0.1 publicado mas o latest.json dele diz OUTRA versão (release montada errada): não serve como "anterior" de 1.0.1
        servidor.createContext("/v1.0.1/latest.json", ex -> { ex.sendResponseHeaders(200, json.length); try (OutputStream o = ex.getResponseBody()) { o.write(json); } });
        servidor.createContext("/v1.0.1/latest.json.sig", ex -> { ex.sendResponseHeaders(200, sig.length); try (OutputStream o = ex.getResponseBody()) { o.write(sig); } });
        servidor.createContext("/v0.9.0/latest.json", ex -> { ex.sendResponseHeaders(404, -1); ex.close(); });
        // v1.0.2: manifesto correto, mas assinado por OUTRO par (impostor)
        byte[] json102 = new String(json, StandardCharsets.UTF_8).replace("1.0.0", "1.0.2").getBytes(StandardCharsets.UTF_8);
        Signature imp = Signature.getInstance("Ed25519"); imp.initSign(ChavesTicket.gerar().getPrivate()); imp.update(json102); byte[] sigImpostor = imp.sign();
        servidor.createContext("/v1.0.2/latest.json", ex -> { ex.sendResponseHeaders(200, json102.length); try (OutputStream o = ex.getResponseBody()) { o.write(json102); } });
        servidor.createContext("/v1.0.2/latest.json.sig", ex -> { ex.sendResponseHeaders(200, sigImpostor.length); try (OutputStream o = ex.getResponseBody()) { o.write(sigImpostor); } });
        // v1.0.4: manifesto legítimo, mas o binário servido foi trocado (mesmo tamanho, outro conteúdo)
        byte[] json104 = new String(json, StandardCharsets.UTF_8).replace("1.0.0", "1.0.4").getBytes(StandardCharsets.UTF_8);
        Signature s104 = Signature.getInstance("Ed25519"); s104.initSign(par.getPrivate()); s104.update(json104); byte[] sig104 = s104.sign();
        byte[] trocado = instalador.clone(); trocado[0] ^= 0x01;
        servidor.createContext("/v1.0.4/latest.json", ex -> { ex.sendResponseHeaders(200, json104.length); try (OutputStream o = ex.getResponseBody()) { o.write(json104); } });
        servidor.createContext("/v1.0.4/latest.json.sig", ex -> { ex.sendResponseHeaders(200, sig104.length); try (OutputStream o = ex.getResponseBody()) { o.write(sig104); } });
        servidor.createContext("/v1.0.4/i.exe", ex -> { downloads.incrementAndGet(); ex.sendResponseHeaders(200, trocado.length); try (OutputStream o = ex.getResponseBody()) { o.write(trocado); } });
        servidor.start();
    }

    @AfterEach
    void derrubar() { servidor.stop(0); }

    private GuardaAnterior guarda(Path tmp, String osName) {
        return new GuardaAnterior(URI.create(base + "/latest.json"), chaves, osName, "amd64", tmp.resolve("anterior"), Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("baixa o .exe da versão atual para anterior/<arquivo>, verificado (assinatura, kid, versão IGUAL, sha); 2ª chamada não vai à rede; lixo antigo em anterior/ é removido")
    void baixaUmaVez(@TempDir Path tmp) throws Exception {
        Path anterior = tmp.resolve("anterior");
        Files.createDirectories(anterior);
        Files.writeString(anterior.resolve("AgroEase-Agente-Impressao-0.9.0-windows-x64.exe"), "velho");
        GuardaAnterior g = guarda(tmp, "Windows 11");
        Optional<Path> exe = g.garantir("1.0.0");
        assertThat(exe).isPresent();
        assertThat(exe.get()).exists().hasFileName("AgroEase-Agente-Impressao-1.0.0-windows-x64.exe").hasParent(anterior);
        assertThat(Files.readAllBytes(exe.get())).isEqualTo(instalador);
        assertThat(anterior.resolve("AgroEase-Agente-Impressao-0.9.0-windows-x64.exe")).as("só o instalador da versão atual fica").doesNotExist();
        assertThat(g.garantir("1.0.0")).contains(exe.get());
        assertThat(guarda(tmp, "Windows 11").garantir("1.0.0")).as("outra instância (outro boot) reconhece o guardado").contains(exe.get());
        assertThat(downloads.get()).isEqualTo(1);
        assertThat(manifestos.get()).as("sem rede quando já está guardado").isEqualTo(1);
    }

    @Test
    @DisplayName("URL da release da versão = a do 'latest' trocando releases/latest/download por releases/download/v<versao>; URL sem esse padrão ganha /v<versao>/ antes do arquivo")
    void urlDaVersao() {
        assertThat(GuardaAnterior.manifestoDaVersao(URI.create("https://github.com/Cura23/wagsyspet-agente/releases/latest/download/latest.json"), "1.0.0").toString())
                .isEqualTo("https://github.com/Cura23/wagsyspet-agente/releases/download/v1.0.0/latest.json");
        assertThat(GuardaAnterior.manifestoDaVersao(URI.create("http://127.0.0.1:8770/latest.json"), "1.0.0").toString())
                .isEqualTo("http://127.0.0.1:8770/v1.0.0/latest.json");
        assertThat(GuardaAnterior.manifestoDaVersao(URI.create("http://127.0.0.1:8770/latest/latest.json"), "1.0.0").toString())
                .isEqualTo("http://127.0.0.1:8770/latest/v1.0.0/latest.json");
    }

    @Test
    @DisplayName("sem release da versão (404 — inclui -SNAPSHOT de dev), manifesto de OUTRA versão ou sem artefato desta plataforma → vazio, sem lançar, sem criar anterior/")
    void semRelease(@TempDir Path tmp) {
        assertThat(guarda(tmp, "Windows 11").garantir("0.9.0")).isEmpty();
        assertThat(guarda(tmp, "Windows 11").garantir("1.0.0-SNAPSHOT")).isEmpty();
        assertThat(guarda(tmp, "Windows 11").garantir("1.0.1")).as("latest.json de v1.0.1 declara 1.0.0").isEmpty();
        assertThat(guarda(tmp, "Linux").garantir("1.0.0")).as("manifesto só tem windows").isEmpty();
        assertThat(tmp.resolve("anterior")).doesNotExist();
        assertThat(downloads.get()).isZero();
    }

    @Test
    @DisplayName("cadeia de segurança com dentes: manifesto assinado por IMPOSTOR → vazio e ZERO downloads; binário trocado (sha não bate) → vazio e nenhum .exe/.tmp fica em anterior/")
    void impostorEBinarioTrocado(@TempDir Path tmp) throws Exception {
        assertThat(guarda(tmp, "Windows 11").garantir("1.0.2")).isEmpty();
        assertThat(downloads.get()).isZero();
        assertThat(guarda(tmp, "Windows 11").garantir("1.0.4")).isEmpty();
        assertThat(downloads.get()).isEqualTo(1);
        Path anterior = tmp.resolve("anterior");
        if (Files.isDirectory(anterior)) {
            try (var lista = Files.list(anterior)) {
                assertThat(lista.map(p -> p.getFileName().toString()).toList()).noneMatch(n -> n.endsWith(".exe") || n.endsWith(".tmp"));
            }
        }
    }

    @Test
    @DisplayName("instalador de OUTRA versão (sobra de um update antigo) é removido ANTES de tentar a rede: se a guarda da versão atual falhar, anterior/ fica vazio — nunca com o .exe errado (adversarial L3); guardado() só reconhece versão + arquivo + sha coerentes")
    void obsoletoSaiMesmoSeARedeFalhar(@TempDir Path tmp) throws Exception {
        GuardaAnterior g = guarda(tmp, "Windows 11");
        Path exe100 = g.garantir("1.0.0").orElseThrow();
        assertThat(GuardaAnterior.guardado(tmp.resolve("anterior"), "1.0.0")).contains(exe100);
        assertThat(GuardaAnterior.guardado(tmp.resolve("anterior"), "0.9.0")).as("marcador é de outra versão").isEmpty();

        assertThat(g.garantir("0.9.0")).as("agora a versão atual é outra e a release dela dá 404").isEmpty();
        assertThat(exe100).as("o 1.0.0 não pode sobrar para um rollback errado").doesNotExist();
        assertThat(GuardaAnterior.guardado(tmp.resolve("anterior"), "1.0.0")).isEmpty();

        Path de_novo = g.garantir("1.0.0").orElseThrow();
        Files.writeString(de_novo, "adulterado depois de guardado");
        assertThat(GuardaAnterior.guardado(tmp.resolve("anterior"), "1.0.0")).as("sha do marcador não bate mais").isEmpty();
    }
}

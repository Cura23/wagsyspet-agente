package br.com.wagner.wagsyspet.agente.app;

import br.com.wagner.wagsyspet.agente.protocolo.release.ChavesRelease;
import br.com.wagner.wagsyspet.agente.protocolo.release.ManifestoRelease;
import br.com.wagner.wagsyspet.agente.protocolo.release.VerificadorAssinaturaRelease;
import br.com.wagner.wagsyspet.agente.protocolo.ticket.ChavesTicket;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.Signature;
import java.util.Optional;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code --verificar-atualizacao}: dry-run para o suporte (e gancho do smoke do CI da F6) — nunca baixa nem aplica. */
@DisplayName("ComandosAtualizacao — --verificar-atualizacao contra um manifesto assinado por par descartável")
class ComandosAtualizacaoTest {

    private HttpServer servidor;
    private String base;
    private KeyPair par;
    private ChavesRelease chaves;
    private byte[] json;
    private byte[] sig;
    private final ByteArrayOutputStream saida = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(saida, true, StandardCharsets.UTF_8);

    @BeforeEach
    void subir() throws Exception {
        par = ChavesTicket.gerar();
        Properties p = new Properties();
        p.setProperty("release.chave.publica", ChavesTicket.exportarPublica(par.getPublic()));
        p.setProperty("release.kid", VerificadorAssinaturaRelease.kid(par.getPublic()));
        chaves = ChavesRelease.de(p);
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/latest.json", ex -> { ex.sendResponseHeaders(200, json.length); try (OutputStream o = ex.getResponseBody()) { o.write(json); } });
        servidor.createContext("/latest.json.sig", ex -> { ex.sendResponseHeaders(200, sig.length); try (OutputStream o = ex.getResponseBody()) { o.write(sig); } });
        servidor.start();
        base = "http://127.0.0.1:" + servidor.getAddress().getPort();
    }

    @AfterEach
    void derrubar() { servidor.stop(0); }

    private void publicar(String versao, KeyPair assinante) throws Exception {
        String kid = VerificadorAssinaturaRelease.kid(par.getPublic());
        json = ("{\"formato\":1,\"versao\":\"" + versao + "\",\"protocolo\":1,\"publicadoEm\":\"2026-09-10T00:00:00Z\",\"kid\":\"" + kid + "\",\"artefatos\":{"
                + "\"linux\":{\"arquivo\":\"AgroEase-Agente-Impressao-" + versao + "-linux-x64.deb\",\"url\":\"" + base + "/a.deb\",\"sha256\":\"" + "ab".repeat(32) + "\",\"tamanho\":10},"
                + "\"windows\":{\"arquivo\":\"AgroEase-Agente-Impressao-" + versao + "-windows-x64.exe\",\"url\":\"" + base + "/a.exe\",\"sha256\":\"" + "cd".repeat(32) + "\",\"tamanho\":10},"
                + "\"macos-arm64\":{\"arquivo\":\"AgroEase-Agente-Impressao-" + versao + "-macos-arm64.dmg\",\"url\":\"" + base + "/a.dmg\",\"sha256\":\"" + "ef".repeat(32) + "\",\"tamanho\":10},"
                + "\"macos-x64\":{\"arquivo\":\"AgroEase-Agente-Impressao-" + versao + "-macos-x64.dmg\",\"url\":\"" + base + "/b.dmg\",\"sha256\":\"" + "01".repeat(32) + "\",\"tamanho\":10}}}").getBytes(StandardCharsets.UTF_8);
        Signature s = Signature.getInstance("Ed25519"); s.initSign(assinante.getPrivate()); s.update(json); sig = s.sign();
    }

    private ComandosAtualizacao comando(String versaoAtual) {
        return new ComandosAtualizacao(out, out, versaoAtual, URI.create(base + "/latest.json"), chaves, ManifestoRelease.FormatoInstalado.INSTALADOR);
    }

    @Test
    @DisplayName("versão maior publicada → 'DISPONÍVEL', mostra versão/arquivo/tamanho e sai 1 (scripts distinguem); nada é baixado")
    void disponivel() throws Exception {
        publicar("9.9.9", par);
        int rc = comando("1.0.0").verificar();
        String s = saida.toString(StandardCharsets.UTF_8);
        assertThat(rc).isEqualTo(ComandosAtualizacao.SAIDA_DISPONIVEL);
        assertThat(s).contains("DISPONÍVEL").contains("9.9.9").contains("AgroEase-Agente-Impressao-9.9.9").contains("10 bytes").doesNotContainIgnoringCase("wagsyspet");
    }

    @Test
    @DisplayName("mesma versão → 'atualizado', sai 0")
    void atualizado() throws Exception {
        publicar("1.0.0", par);
        assertThat(comando("1.0.0-SNAPSHOT").verificar()).isZero();
        assertThat(saida.toString(StandardCharsets.UTF_8)).containsIgnoringCase("atualizado");
    }

    @Test
    @DisplayName("assinado por impostor → 'RECUSADO' com o motivo whitelist e sai 2; servidor fora → 'indisponível' e sai 2")
    void recusadoEIndisponivel() throws Exception {
        publicar("9.9.9", ChavesTicket.gerar());
        assertThat(comando("1.0.0").verificar()).isEqualTo(Main.SAIDA_FALHA);
        assertThat(saida.toString(StandardCharsets.UTF_8)).contains("RECUSADO").contains("ASSINATURA");
        saida.reset();
        ComandosAtualizacao fora = new ComandosAtualizacao(out, out, "1.0.0", URI.create("http://127.0.0.1:1/latest.json"), chaves, ManifestoRelease.FormatoInstalado.INSTALADOR);
        assertThat(fora.verificar()).isEqualTo(Main.SAIDA_FALHA);
        assertThat(saida.toString(StandardCharsets.UTF_8)).containsIgnoringCase("indisponível");
    }

    @Test
    @DisplayName("formato instalado pelo caminho do launcher: /opt e Program Files = instalador; ~/.local = app-image (tar.gz); sem launcher (java cru) = instalador")
    void formatoInstalado() {
        Path home = Path.of("/home/loja");
        assertThat(ComandosAtualizacao.formatoInstalado(Optional.of(Path.of("/opt/agroease-agente-impressao/bin/AgroEase-Agente-Impressao")), home)).isEqualTo(ManifestoRelease.FormatoInstalado.INSTALADOR);
        assertThat(ComandosAtualizacao.formatoInstalado(Optional.of(home.resolve(".local/share/agroease/agente-impressao/versoes/1.0.0/bin/AgroEase-Agente-Impressao")), home)).isEqualTo(ManifestoRelease.FormatoInstalado.APP_IMAGE);
        assertThat(ComandosAtualizacao.formatoInstalado(Optional.of(Path.of("C:\\Users\\loja\\AppData\\Local\\AgroEase-Agente-Impressao\\AgroEase-Agente-Impressao.exe")), Path.of("C:\\Users\\loja"))).isEqualTo(ManifestoRelease.FormatoInstalado.INSTALADOR);
        assertThat(ComandosAtualizacao.formatoInstalado(Optional.empty(), home)).isEqualTo(ManifestoRelease.FormatoInstalado.INSTALADOR);
    }

    @Test
    @DisplayName("fonte do manifesto: -Dagente.release.manifesto.url manda; senão a URL embutida do GitHub Releases (/releases/latest/download/latest.json)")
    void fonteDoManifesto() {
        Properties props = new Properties();
        assertThat(ComandosAtualizacao.urlManifesto(props).toString()).isEqualTo("https://github.com/Cura23/wagsyspet-agente/releases/latest/download/latest.json");
        props.setProperty(ComandosAtualizacao.PROP_MANIFESTO, "http://127.0.0.1:9/x/latest.json");
        assertThat(ComandosAtualizacao.urlManifesto(props).toString()).isEqualTo("http://127.0.0.1:9/x/latest.json");
    }
}

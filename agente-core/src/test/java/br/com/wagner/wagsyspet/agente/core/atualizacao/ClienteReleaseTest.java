package br.com.wagner.wagsyspet.agente.core.atualizacao;

import br.com.wagner.wagsyspet.agente.protocolo.release.ManifestoRelease;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Cliente HTTP do self-update (irmão do ClientePareamento): manifesto + .sig com ETag, redirect, allowlist, download streaming com teto/sha. */
@DisplayName("ClienteRelease — manifesto/assinatura, 304, redirect, allowlist de host, artefato streaming com teto e sha256")
class ClienteReleaseTest {

    private HttpServer servidor;
    private String base;
    private byte[] manifesto = "{\"formato\":1}".getBytes(StandardCharsets.UTF_8);
    private byte[] sig = new byte[64];
    private byte[] artefato = "conteudo-do-instalador".repeat(1000).getBytes(StandardCharsets.UTF_8);
    private String contentLengthArtefato = null; // null = real
    private byte[] corpoArtefato = null;         // null = artefato
    private final AtomicInteger hitsManifesto = new AtomicInteger();

    @BeforeEach
    void subir() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/latest.json", ex -> {
            hitsManifesto.incrementAndGet();
            String inm = ex.getRequestHeaders().getFirst("If-None-Match");
            if ("\"etag-atual\"".equals(inm)) { ex.sendResponseHeaders(304, -1); ex.close(); return; }
            ex.getResponseHeaders().add("ETag", "\"etag-atual\"");
            responder(ex, 200, manifesto);
        });
        servidor.createContext("/latest.json.sig", ex -> responder(ex, 200, sig));
        servidor.createContext("/redir/latest.json", ex -> { ex.getResponseHeaders().add("Location", base + "/latest.json"); ex.sendResponseHeaders(302, -1); ex.close(); });
        servidor.createContext("/redir/latest.json.sig", ex -> { ex.getResponseHeaders().add("Location", base + "/latest.json.sig"); ex.sendResponseHeaders(302, -1); ex.close(); });
        servidor.createContext("/fora/latest.json", ex -> { ex.getResponseHeaders().add("Location", "https://exemplo.invalido/latest.json"); ex.sendResponseHeaders(302, -1); ex.close(); });
        servidor.createContext("/artefato.deb", ex -> {
            byte[] corpo = corpoArtefato != null ? corpoArtefato : artefato;
            long declarado = contentLengthArtefato != null ? Long.parseLong(contentLengthArtefato) : corpo.length;
            ex.sendResponseHeaders(200, declarado);
            try (OutputStream out = ex.getResponseBody()) { out.write(corpo, 0, (int) Math.min(corpo.length, declarado)); } catch (IOException ignorada) { }
        });
        servidor.start();
        base = "http://127.0.0.1:" + servidor.getAddress().getPort();
    }

    @AfterEach
    void derrubar() { servidor.stop(0); }

    private static void responder(HttpExchange ex, int status, byte[] corpo) throws IOException {
        ex.sendResponseHeaders(status, corpo.length);
        try (OutputStream out = ex.getResponseBody()) { out.write(corpo); }
    }

    private ClienteRelease cliente() {
        return new ClienteRelease(URI.create(base + "/latest.json"), Duration.ofSeconds(5), "1.0.0-teste");
    }

    @Test
    @DisplayName("manifesto + .sig baixados juntos, ETag guardado; 304 com o ETag → vazio (nada mudou), 1 request a menos")
    void manifestoEEtag() throws Exception {
        Optional<ClienteRelease.ManifestoBaixado> m = cliente().buscarManifesto(null);
        assertThat(m).isPresent();
        assertThat(m.get().json()).isEqualTo(manifesto);
        assertThat(m.get().assinatura()).hasSize(64);
        assertThat(m.get().etag()).isEqualTo("\"etag-atual\"");
        assertThat(cliente().buscarManifesto("\"etag-atual\"")).isEmpty();
    }

    @Test
    @DisplayName("302 para o MESMO host é seguido (GitHub redireciona releases/download para objects.githubusercontent.com); para host fora da allowlist → recusa")
    void redirect() throws Exception {
        ClienteRelease redir = new ClienteRelease(URI.create(base + "/redir/latest.json"), Duration.ofSeconds(5), "1.0.0-teste");
        assertThat(redir.buscarManifesto(null)).isPresent();
        ClienteRelease fora = new ClienteRelease(URI.create(base + "/fora/latest.json"), Duration.ofSeconds(5), "1.0.0-teste");
        assertThatThrownBy(() -> fora.buscarManifesto(null)).isInstanceOf(ClienteRelease.ReleaseIndisponivelException.class)
                .hasMessageContaining("exemplo.invalido");
    }

    @Test
    @DisplayName("hosts permitidos: github.com, *.githubusercontent.com e o host do próprio manifesto (loopback em dev/CI); qualquer outro recusado")
    void allowlist() {
        assertThat(ClienteRelease.hostPermitido(URI.create("https://github.com/x"), URI.create("https://github.com/latest.json"))).isTrue();
        assertThat(ClienteRelease.hostPermitido(URI.create("https://objects.githubusercontent.com/x"), URI.create("https://github.com/latest.json"))).isTrue();
        assertThat(ClienteRelease.hostPermitido(URI.create("https://release-assets.githubusercontent.com/x"), URI.create("https://github.com/latest.json"))).isTrue();
        assertThat(ClienteRelease.hostPermitido(URI.create("http://127.0.0.1:8080/x"), URI.create("http://127.0.0.1:8080/latest.json"))).isTrue();
        assertThat(ClienteRelease.hostPermitido(URI.create("https://evil.com/x"), URI.create("https://github.com/latest.json"))).isFalse();
        assertThat(ClienteRelease.hostPermitido(URI.create("https://github.com.evil.com/x"), URI.create("https://github.com/latest.json"))).isFalse();
        assertThat(ClienteRelease.hostPermitido(URI.create("http://github.com/x"), URI.create("https://github.com/latest.json"))).as("http em host público").isFalse();
    }

    @Test
    @DisplayName("artefato: streaming para arquivo, sha256 e tamanho conferidos; sha errado / tamanho maior que o declarado / Content-Length divergente → falha e apaga o parcial")
    void artefato(@TempDir Path dir) throws Exception {
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(artefato));
        ManifestoRelease.Artefato ok = new ManifestoRelease.Artefato("a.deb", base + "/artefato.deb", sha, artefato.length);
        Path destino = dir.resolve("a.deb");
        cliente().baixarArtefato(ok, destino);
        assertThat(Files.readAllBytes(destino)).isEqualTo(artefato);

        ManifestoRelease.Artefato shaErrado = new ManifestoRelease.Artefato("a.deb", base + "/artefato.deb", "0".repeat(64), artefato.length);
        assertThatThrownBy(() -> cliente().baixarArtefato(shaErrado, dir.resolve("b.deb"))).isInstanceOf(ClienteRelease.ReleaseIndisponivelException.class).hasMessageContaining("sha256");
        assertThat(Files.exists(dir.resolve("b.deb"))).isFalse();

        // servidor manda MAIS bytes que o manifesto declara → para no teto, não enche o disco
        corpoArtefato = ("x".repeat(artefato.length + 5000)).getBytes(StandardCharsets.UTF_8);
        ManifestoRelease.Artefato teto = new ManifestoRelease.Artefato("a.deb", base + "/artefato.deb", sha, artefato.length);
        assertThatThrownBy(() -> cliente().baixarArtefato(teto, dir.resolve("c.deb"))).isInstanceOf(ClienteRelease.ReleaseIndisponivelException.class).hasMessageContaining("tamanho");
        assertThat(Files.exists(dir.resolve("c.deb"))).isFalse();

        // Content-Length mentindo (menor que o declarado no manifesto) → recusa antes de baixar
        corpoArtefato = null; contentLengthArtefato = String.valueOf(artefato.length - 1);
        assertThatThrownBy(() -> cliente().baixarArtefato(ok, dir.resolve("d.deb"))).isInstanceOf(ClienteRelease.ReleaseIndisponivelException.class).hasMessageContaining("Content-Length");
    }

    @Test
    @DisplayName("servidor fora → ReleaseIndisponivelException (nunca outra exceção), com prazo respeitado")
    void servidorFora() {
        ClienteRelease c = new ClienteRelease(URI.create("http://127.0.0.1:1/latest.json"), Duration.ofSeconds(2), "1.0.0-teste");
        assertThatThrownBy(() -> c.buscarManifesto(null)).isInstanceOf(ClienteRelease.ReleaseIndisponivelException.class);
    }
}

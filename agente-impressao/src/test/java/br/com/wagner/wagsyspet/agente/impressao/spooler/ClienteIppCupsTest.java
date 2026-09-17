package br.com.wagner.wagsyspet.agente.impressao.spooler;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * IPP (RFC 8010/8011) direto no cupsd local: o job-state é NUMÉRICO — vale em qualquer versão do CUPS e em qualquer idioma. O texto do
 * lpstat não: CUPS &lt; 2.4.8 termina job IMPRESSO com 'processing-to-stop-point' (Issue #832) e LC_MESSAGES traduz os rótulos.
 */
@DisplayName("ClienteIppCups — Get-Job-Attributes / Get-Printer-Attributes em binário IPP contra um cupsd falso")
class ClienteIppCupsTest {

    private HttpServer servidor;
    private final AtomicReference<byte[]> resposta = new AtomicReference<>();
    private final AtomicReference<byte[]> pedido = new AtomicReference<>();
    private final AtomicReference<String> caminho = new AtomicReference<>();
    private volatile int http = 200;

    @BeforeEach
    void subir() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/", ex -> {
            pedido.set(ex.getRequestBody().readAllBytes());
            caminho.set(ex.getRequestURI().getPath() + "|" + ex.getRequestHeaders().getFirst("Content-Type"));
            byte[] r = resposta.get();
            ex.sendResponseHeaders(http, r.length);
            try (OutputStream o = ex.getResponseBody()) { o.write(r); }
        });
        servidor.start();
    }

    @AfterEach
    void derrubar() { servidor.stop(0); }

    private ClienteIppCups cliente() {
        return new ClienteIppCups(URI.create("http://127.0.0.1:" + servidor.getAddress().getPort()), Duration.ofSeconds(3));
    }

    /** Monta uma resposta IPP: status + grupo de atributos (tag do grupo 0x02 = job, 0x04 = printer). */
    static byte[] ipp(int status, int grupo, Object... atributos) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(new byte[]{2, 0, (byte) (status >> 8), (byte) status, 0, 0, 0, 1, 1});
        texto(b, 0x47, "attributes-charset", "utf-8");
        texto(b, 0x48, "attributes-natural-language", "en");
        b.write(grupo);
        for (int i = 0; i < atributos.length; i += 2) {
            String nome = (String) atributos[i];
            Object v = atributos[i + 1];
            if (v instanceof Integer n) {
                b.write(0x23); escreverNome(b, nome); b.write(new byte[]{0, 4, (byte) (n >> 24), (byte) (n >> 16), (byte) (n >> 8), (byte) (int) n});
            } else if (v instanceof String[] varios) {
                for (int k = 0; k < varios.length; k++) { texto(b, 0x44, k == 0 ? nome : "", varios[k]); }
            } else {
                texto(b, 0x41, nome, (String) v);
            }
        }
        b.write(3);
        return b.toByteArray();
    }

    private static void escreverNome(ByteArrayOutputStream b, String nome) throws IOException {
        byte[] n = nome.getBytes(StandardCharsets.UTF_8);
        b.write(new byte[]{(byte) (n.length >> 8), (byte) n.length}); b.write(n);
    }

    private static void texto(ByteArrayOutputStream b, int tag, String nome, String valor) throws IOException {
        b.write(tag); escreverNome(b, nome);
        byte[] v = valor.getBytes(StandardCharsets.UTF_8);
        b.write(new byte[]{(byte) (v.length >> 8), (byte) v.length}); b.write(v);
    }

    @Test
    @DisplayName("job: pedido Get-Job-Attributes (0x0009) bem formado em POST /jobs/ application/ipp com job-uri ipp://localhost/jobs/<n>; resposta → estado numérico + TODOS os motivos (1setOf) + mensagem")
    void job() throws Exception {
        resposta.set(ipp(0x0000, 0x02, "job-state", 6, "job-state-reasons", new String[]{"job-completed-with-errors", "job-stopped"}, "job-printer-state-message", "Filter failed"));
        ClienteIppCups.Job j = cliente().job(52).orElseThrow();
        assertThat(j.estado()).isEqualTo(6);
        assertThat(j.motivos()).containsExactly("job-completed-with-errors", "job-stopped");
        assertThat(j.mensagem()).isEqualTo("Filter failed");
        assertThat(caminho.get()).isEqualTo("/jobs/|application/ipp");
        byte[] p = pedido.get();
        assertThat(p[2] << 8 | p[3]).as("operation-id").isEqualTo(0x0009);
        String cru = new String(p, StandardCharsets.ISO_8859_1);
        assertThat(cru).contains("attributes-charset").contains("utf-8").contains("job-uri").contains("ipp://localhost/jobs/52")
                .contains("requested-attributes").contains("job-state").contains("job-state-reasons");
        assertThat(p[p.length - 1]).as("end-of-attributes").isEqualTo((byte) 3);
    }

    @Test
    @DisplayName("job inexistente (client-error-not-found 0x0406) → vazio, sem lançar; outro status IPP, HTTP ≠ 200, lixo no corpo ou servidor fora → IOException (quem chama cai no lpstat)")
    void naoEncontradoEFalhas() throws Exception {
        resposta.set(ipp(0x0406, 0x02));
        assertThat(cliente().job(99999)).isEmpty();
        resposta.set(ipp(0x0401, 0x02));
        assertThatThrownBy(() -> cliente().job(1)).isInstanceOf(IOException.class).hasMessageContaining("0x401");
        http = 426;
        assertThatThrownBy(() -> cliente().job(1)).isInstanceOf(IOException.class).hasMessageContaining("426");
        http = 200;
        resposta.set(new byte[]{1, 2, 3});
        assertThatThrownBy(() -> cliente().job(1)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> new ClienteIppCups(URI.create("http://127.0.0.1:1"), Duration.ofSeconds(2)).job(1)).isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("fila: Get-Printer-Attributes (0x000B) em POST /printers/<nome codificado> com printer-uri; estado, motivos e mensagem")
    void fila() throws Exception {
        resposta.set(ipp(0x0000, 0x04, "printer-state", 5, "printer-state-reasons", new String[]{"paused"}, "printer-state-message", "Paused"));
        ClienteIppCups.Fila f = cliente().fila("EPSON TM-T20").orElseThrow();
        assertThat(f.estado()).isEqualTo(5);
        assertThat(f.motivos()).containsExactly("paused");
        assertThat(caminho.get()).startsWith("/printers/EPSON");
        assertThat(pedido.get()[2] << 8 | pedido.get()[3]).isEqualTo(0x000B);
        assertThat(new String(pedido.get(), StandardCharsets.ISO_8859_1)).contains("printer-uri").contains("ipp://localhost/printers/EPSON%20TM-T20");
    }
}

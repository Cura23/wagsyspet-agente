package br.com.wagner.wagsyspet.agente.impressao.spooler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Cliente IPP mínimo (RFC 8010 binário, RFC 8011 operações) para o cupsd LOCAL — {@code http://localhost:631}, sem autenticação
 * (Get-Job-Attributes/Get-Printer-Attributes são liberadas no cupsd.conf padrão; job-state e motivos não são atributos privados).
 * <p>Por que IPP e não o texto do {@code lpstat} (adversarial L4): o {@code job-state} é NUMÉRICO — igual em toda versão e idioma. O
 * {@code lpstat} só mostra {@code job-state-reasons}, e o CUPS &lt; 2.4.8 (Ubuntu 22.04/24.04, Debian 12, macOS) termina um job
 * IMPRESSO com {@code processing-to-stop-point} (Issue #832), o mesmo valor de um abortado; e {@code LC_MESSAGES} traduz os rótulos.
 * <p>Qualquer problema de transporte/formato vira {@link IOException}: quem chama cai na reserva ({@code lpstat}).
 */
public final class ClienteIppCups {

    public static final URI CUPSD_LOCAL = URI.create("http://localhost:631");
    private static final int GET_JOB_ATTRIBUTES = 0x0009;
    private static final int GET_PRINTER_ATTRIBUTES = 0x000B;
    private static final int STATUS_NAO_ENCONTRADO = 0x0406;
    private static final int TETO_RESPOSTA = 64 * 1024;

    /** {@code job-state}: 3 pending, 4 pending-held, 5 processing, 6 processing-stopped, 7 canceled, 8 aborted, 9 completed. */
    public record Job(int estado, List<String> motivos, String mensagem) { }

    /** {@code printer-state}: 3 idle, 4 processing, 5 stopped. */
    public record Fila(int estado, List<String> motivos, String mensagem) { }

    private final URI base;
    private final Duration prazo;
    private final HttpClient http;

    public ClienteIppCups(URI base, Duration prazo) {
        this.base = base;
        this.prazo = prazo;
        this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(prazo).build();
    }

    /** Vazio = o cupsd não conhece o job (histórico desligado ou já rodado). */
    public Optional<Job> job(int numero) throws IOException {
        byte[] pedido = pedido(GET_JOB_ATTRIBUTES, "job-uri", "ipp://localhost/jobs/" + numero,
                "job-state", "job-state-reasons", "job-printer-state-message");
        Optional<Map<String, List<Object>>> a = chamar("/jobs/", pedido);
        if (a.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Job(inteiro(a.get(), "job-state"), textos(a.get(), "job-state-reasons"), primeiro(a.get(), "job-printer-state-message")));
    }

    public Optional<Fila> fila(String nome) throws IOException {
        String codificado = URLEncoder.encode(nome, StandardCharsets.UTF_8).replace("+", "%20");
        byte[] pedido = pedido(GET_PRINTER_ATTRIBUTES, "printer-uri", "ipp://localhost/printers/" + codificado,
                "printer-state", "printer-state-reasons", "printer-state-message");
        Optional<Map<String, List<Object>>> a = chamar("/printers/" + codificado, pedido);
        if (a.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Fila(inteiro(a.get(), "printer-state"), textos(a.get(), "printer-state-reasons"), primeiro(a.get(), "printer-state-message")));
    }

    // ── transporte ────────────────────────────────────────────────────────────────────────────────────────────

    private Optional<Map<String, List<Object>>> chamar(String caminho, byte[] pedido) throws IOException {
        HttpRequest req = HttpRequest.newBuilder(base.resolve(caminho)).timeout(prazo)
                .header("Content-Type", "application/ipp").POST(HttpRequest.BodyPublishers.ofByteArray(pedido)).build();
        HttpResponse<byte[]> r;
        try {
            r = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("consulta IPP interrompida", e);
        } catch (RuntimeException e) {
            throw new IOException("consulta IPP falhou: " + e, e);
        }
        if (r.statusCode() != 200) {
            throw new IOException("cupsd respondeu HTTP " + r.statusCode());
        }
        byte[] corpo = r.body();
        if (corpo.length < 9 || corpo.length > TETO_RESPOSTA) {
            throw new IOException("resposta IPP com " + corpo.length + " bytes");
        }
        int status = (corpo[2] & 0xff) << 8 | (corpo[3] & 0xff);
        if (status == STATUS_NAO_ENCONTRADO) {
            return Optional.empty();
        }
        if (status >= 0x0100) { // 0x0000–0x00ff = successful-ok*
            throw new IOException("cupsd respondeu IPP 0x" + Integer.toHexString(status));
        }
        return Optional.of(atributos(corpo));
    }

    // ── codificação (RFC 8010 §3) ────────────────────────────────────────────────────────────────────────────

    private static byte[] pedido(int operacao, String nomeUri, String uri, String... pedidos) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(2); b.write(0);                          // IPP/2.0
        b.write(operacao >> 8); b.write(operacao);
        b.write(0); b.write(0); b.write(0); b.write(1);  // request-id
        b.write(0x01);                                   // operation-attributes-tag
        atributo(b, 0x47, "attributes-charset", "utf-8");
        atributo(b, 0x48, "attributes-natural-language", "en");
        atributo(b, 0x45, nomeUri, uri);
        atributo(b, 0x42, "requesting-user-name", System.getProperty("user.name", "agroease"));
        for (int i = 0; i < pedidos.length; i++) {
            atributo(b, 0x44, i == 0 ? "requested-attributes" : "", pedidos[i]); // 1setOf: valores extras vão com nome vazio
        }
        b.write(0x03);                                   // end-of-attributes-tag
        return b.toByteArray();
    }

    private static void atributo(ByteArrayOutputStream b, int tag, String nome, String valor) {
        byte[] n = nome.getBytes(StandardCharsets.UTF_8);
        byte[] v = valor.getBytes(StandardCharsets.UTF_8);
        b.write(tag);
        b.write(n.length >> 8); b.write(n.length); b.writeBytes(n);
        b.write(v.length >> 8); b.write(v.length); b.writeBytes(v);
    }

    /** nome → valores (Integer para integer/enum, String para o resto). Lança em qualquer truncamento. */
    private static Map<String, List<Object>> atributos(byte[] d) throws IOException {
        Map<String, List<Object>> mapa = new LinkedHashMap<>();
        String ultimo = null;
        int i = 8;
        try {
            while (i < d.length) {
                int tag = d[i++] & 0xff;
                if (tag == 0x03) {
                    return mapa;
                }
                if (tag < 0x10) {
                    continue; // delimitador de grupo
                }
                int tamanhoNome = (d[i] & 0xff) << 8 | (d[i + 1] & 0xff); i += 2;
                String nome = new String(d, i, tamanhoNome, StandardCharsets.UTF_8); i += tamanhoNome;
                int tamanhoValor = (d[i] & 0xff) << 8 | (d[i + 1] & 0xff); i += 2;
                Object valor = (tag == 0x21 || tag == 0x23) && tamanhoValor == 4
                        ? (Object) ((d[i] & 0xff) << 24 | (d[i + 1] & 0xff) << 16 | (d[i + 2] & 0xff) << 8 | (d[i + 3] & 0xff))
                        : new String(d, i, tamanhoValor, StandardCharsets.UTF_8);
                i += tamanhoValor;
                if (!nome.isEmpty()) {
                    ultimo = nome;
                    mapa.put(nome, new ArrayList<>());
                }
                if (ultimo == null) {
                    throw new IOException("valor adicional sem atributo");
                }
                mapa.get(ultimo).add(valor);
            }
        } catch (IndexOutOfBoundsException e) {
            throw new IOException("resposta IPP truncada", e);
        }
        throw new IOException("resposta IPP sem end-of-attributes");
    }

    private static int inteiro(Map<String, List<Object>> a, String nome) throws IOException {
        List<Object> v = a.get(nome);
        if (v == null || v.isEmpty() || !(v.get(0) instanceof Integer n)) {
            throw new IOException("resposta IPP sem " + nome);
        }
        return n;
    }

    private static List<String> textos(Map<String, List<Object>> a, String nome) {
        return a.getOrDefault(nome, List.of()).stream().map(String::valueOf).toList();
    }

    private static String primeiro(Map<String, List<Object>> a, String nome) {
        List<Object> v = a.get(nome);
        return v == null || v.isEmpty() ? "" : String.valueOf(v.get(0));
    }
}

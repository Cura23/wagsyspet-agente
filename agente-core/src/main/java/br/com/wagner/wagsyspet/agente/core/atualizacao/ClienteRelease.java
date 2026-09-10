package br.com.wagner.wagsyspet.agente.core.atualizacao;

import br.com.wagner.wagsyspet.agente.protocolo.release.ManifestoRelease;
import br.com.wagner.wagsyspet.agente.protocolo.release.VerificadorAssinaturaRelease;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Cliente HTTP do self-update (irmão do {@code ClientePareamento}, não cópia — plano F6 D3): baixa {@code latest.json} + {@code .sig}
 * com ETag, e o instalador em STREAMING para arquivo (heap de 128 MB: nunca {@code byte[]} de 40 MB), conferindo
 * {@code Content-Length}, teto de bytes (= {@code tamanho} do manifesto) e sha256 incremental. Redirecionamentos são seguidos à mão
 * (máx. {@value #MAX_REDIRECTS}) e cada destino passa pela allowlist: {@code github.com}, {@code *.githubusercontent.com} ou o host do
 * próprio manifesto (loopback em dev/CI) — nunca http em host público. Toda falha vira {@link ReleaseIndisponivelException}.
 */
public final class ClienteRelease {

    private static final Logger log = LoggerFactory.getLogger(ClienteRelease.class);
    static final int MAX_REDIRECTS = 5;
    private static final Set<String> HOSTS_PUBLICOS = Set.of("github.com");
    private static final String SUFIXO_PUBLICO = ".githubusercontent.com";

    /** Qualquer falha de rede/protocolo/verificação — o chamador só decide "tentar depois". */
    public static final class ReleaseIndisponivelException extends Exception {
        public ReleaseIndisponivelException(String msg) { super(msg); }
        public ReleaseIndisponivelException(String msg, Throwable causa) { super(msg, causa); }
    }

    /** Bytes crus do manifesto e da assinatura (verificar ANTES de parsear) + ETag para a próxima consulta. */
    public record ManifestoBaixado(byte[] json, byte[] assinatura, String etag) { }

    private final URI manifesto;
    private final URI assinatura;
    private final Duration prazo;
    private final String userAgent;
    private final HttpClient http;

    public ClienteRelease(URI manifesto, Duration prazo, String versaoAgente) {
        this.manifesto = Objects.requireNonNull(manifesto);
        this.assinatura = URI.create(manifesto.toString() + ".sig");
        this.prazo = Objects.requireNonNull(prazo);
        this.userAgent = "AgroEase-Agente/" + versaoAgente;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER) // seguimos à mão para passar cada salto pela allowlist
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public URI manifesto() {
        return manifesto;
    }

    /** {@code Optional.empty()} = 304, nada mudou desde {@code etagConhecido}. */
    public Optional<ManifestoBaixado> buscarManifesto(String etagConhecido) throws ReleaseIndisponivelException {
        HttpResponse<byte[]> r = obter(manifesto, etagConhecido, ManifestoRelease.TETO_BYTES);
        if (r.statusCode() == 304) {
            return Optional.empty();
        }
        exigir200(r, "manifesto");
        HttpResponse<byte[]> s = obter(assinatura, null, VerificadorAssinaturaRelease.TAMANHO_ASSINATURA);
        exigir200(s, "assinatura");
        if (s.body().length != VerificadorAssinaturaRelease.TAMANHO_ASSINATURA) {
            throw new ReleaseIndisponivelException("assinatura com " + s.body().length + " bytes (esperado 64)");
        }
        String etag = r.headers().firstValue("ETag").orElse(null);
        return Optional.of(new ManifestoBaixado(r.body(), s.body(), etag));
    }

    /**
     * Baixa {@code artefato.url()} para {@code destino} (via {@code destino.tmp}), exigindo {@code Content-Length == tamanho} quando
     * presente, parando no teto e conferindo tamanho final e sha256. Em qualquer falha apaga o parcial e lança.
     */
    public void baixarArtefato(ManifestoRelease.Artefato artefato, Path destino) throws ReleaseIndisponivelException {
        Objects.requireNonNull(artefato);
        Path tmp = destino.resolveSibling(destino.getFileName() + ".tmp");
        try {
            Files.createDirectories(destino.toAbsolutePath().getParent());
            HttpResponse<InputStream> r = seguirRedirects(URI.create(artefato.url()), null, HttpResponse.BodyHandlers.ofInputStream());
            if (r.statusCode() != 200) {
                fechar(r.body());
                throw new ReleaseIndisponivelException("download HTTP " + r.statusCode());
            }
            OptionalLong declarado = r.headers().firstValueAsLong("Content-Length");
            if (declarado.isPresent() && declarado.getAsLong() != artefato.tamanho()) {
                fechar(r.body());
                throw new ReleaseIndisponivelException("Content-Length " + declarado.getAsLong() + " ≠ tamanho do manifesto " + artefato.tamanho());
            }
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            long total = 0;
            try (InputStream in = r.body(); OutputStream out = Files.newOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > artefato.tamanho()) {
                        throw new ReleaseIndisponivelException("download passou do tamanho do manifesto (" + artefato.tamanho() + " bytes)");
                    }
                    out.write(buf, 0, n);
                    sha.update(buf, 0, n);
                }
            }
            if (total != artefato.tamanho()) {
                throw new ReleaseIndisponivelException("download com " + total + " bytes, tamanho do manifesto " + artefato.tamanho());
            }
            String hex = HexFormat.of().formatHex(sha.digest());
            if (!hex.equals(artefato.sha256().toLowerCase(Locale.ROOT))) {
                throw new ReleaseIndisponivelException("sha256 do download não bate com o manifesto");
            }
            Files.move(tmp, destino, StandardCopyOption.REPLACE_EXISTING);
            log.info("Instalador {} baixado e verificado ({} bytes, sha256 {}…)", artefato.arquivo(), total, hex.substring(0, 12));
        } catch (ReleaseIndisponivelException e) {
            apagar(tmp); apagar(destino);
            throw e;
        } catch (IOException | InterruptedException | NoSuchAlgorithmException | RuntimeException e) {
            apagar(tmp); apagar(destino);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new ReleaseIndisponivelException("falha no download: " + e, e);
        }
    }

    private HttpResponse<byte[]> obter(URI uri, String etag, int teto) throws ReleaseIndisponivelException {
        try {
            HttpResponse<byte[]> r = seguirRedirects(uri, etag, HttpResponse.BodyHandlers.ofByteArray());
            if (r.body() != null && r.body().length > teto) {
                throw new ReleaseIndisponivelException(uri.getPath() + " com " + r.body().length + " bytes > teto " + teto);
            }
            return r;
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new ReleaseIndisponivelException("falha ao obter " + uri.getPath() + ": " + e, e);
        }
    }

    private <T> HttpResponse<T> seguirRedirects(URI uri, String etag, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException, ReleaseIndisponivelException {
        URI atual = uri;
        for (int salto = 0; salto <= MAX_REDIRECTS; salto++) {
            if (!hostPermitido(atual, manifesto)) {
                throw new ReleaseIndisponivelException("destino fora da allowlist: " + atual.getHost());
            }
            HttpRequest.Builder b = HttpRequest.newBuilder(atual).timeout(prazo).GET()
                    .header("User-Agent", userAgent).header("Accept", "*/*");
            if (etag != null && salto == 0) {
                b.header("If-None-Match", etag);
            }
            HttpResponse<T> r = http.send(b.build(), handler);
            int st = r.statusCode();
            if (st == 301 || st == 302 || st == 303 || st == 307 || st == 308) {
                Optional<String> loc = r.headers().firstValue("Location");
                if (loc.isEmpty()) {
                    throw new ReleaseIndisponivelException("redirect sem Location");
                }
                if (r.body() instanceof InputStream in) {
                    fechar(in);
                }
                atual = atual.resolve(loc.get());
                continue;
            }
            return r;
        }
        throw new ReleaseIndisponivelException("mais de " + MAX_REDIRECTS + " redirecionamentos");
    }

    private static void exigir200(HttpResponse<?> r, String oQue) throws ReleaseIndisponivelException {
        if (r.statusCode() != 200) {
            throw new ReleaseIndisponivelException(oQue + " HTTP " + r.statusCode());
        }
    }

    /**
     * {@code github.com}, {@code *.githubusercontent.com} (só https) ou o mesmo host/porta do manifesto (loopback em dev/CI, que pode
     * ser http). Nunca http em host público; nunca um host que só "termina em" github.com.
     */
    static boolean hostPermitido(URI alvo, URI manifesto) {
        String host = alvo.getHost();
        if (host == null) {
            return false;
        }
        host = host.toLowerCase(Locale.ROOT);
        String esquema = alvo.getScheme() == null ? "" : alvo.getScheme().toLowerCase(Locale.ROOT);
        boolean mesmoDoManifesto = host.equals(manifesto.getHost() == null ? "" : manifesto.getHost().toLowerCase(Locale.ROOT))
                && alvo.getPort() == manifesto.getPort();
        boolean loopback = host.equals("127.0.0.1") || host.equals("localhost");
        if (mesmoDoManifesto && (esquema.equals("https") || (esquema.equals("http") && loopback))) {
            return true;
        }
        if (!esquema.equals("https")) {
            return false;
        }
        return HOSTS_PUBLICOS.contains(host) || (host.endsWith(SUFIXO_PUBLICO) && host.length() > SUFIXO_PUBLICO.length());
    }

    private static void fechar(InputStream in) {
        try {
            if (in != null) {
                in.close();
            }
        } catch (IOException ignorada) {
            // já estamos abortando
        }
    }

    private static void apagar(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignorada) {
            // melhor esforço
        }
    }
}

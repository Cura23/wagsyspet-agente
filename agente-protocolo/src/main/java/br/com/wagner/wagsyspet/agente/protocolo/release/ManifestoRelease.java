package br.com.wagner.wagsyspet.agente.protocolo.release;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

import static br.com.wagner.wagsyspet.agente.protocolo.release.ManifestoInvalidoException.Motivo;

/**
 * O {@code latest.json} publicado a cada release (formato definido por {@code scripts/release/montar-latest.sh}):
 * <pre>{"formato":1,"versao":"1.0.0","protocolo":1,"publicadoEm":"…Z","kid":"16hex","artefatos":{"windows":{"arquivo","url","sha256","tamanho"},…}}</pre>
 * Parser ESTRITO nos campos obrigatórios (qualquer desvio = {@link ManifestoInvalidoException} com motivo whitelist) e ADITIVO nos
 * desconhecidos (um agente antigo lê um manifesto novo). Só é chamado DEPOIS da assinatura Ed25519 conferir
 * ({@link VerificadorAssinaturaRelease}) — o parser nunca vê bytes não assinados. Teto de {@value #TETO_BYTES} bytes.
 *
 * @param artefatos chave por plataforma ({@code windows}, {@code linux}, {@code linux-tar}, {@code macos-arm64}, {@code macos-x64})
 */
public record ManifestoRelease(int formato, String versao, int protocolo, String publicadoEm, String kid, Map<String, Artefato> artefatos) {

    public static final int TETO_BYTES = 64 * 1024;
    public static final int FORMATO_SUPORTADO = 1;

    private static final Pattern VERSAO = Pattern.compile("^\\d+\\.\\d+\\.\\d+(-[0-9A-Za-z.-]+)?$");
    private static final Pattern KID = Pattern.compile("^[0-9a-f]{16}$");
    private static final Pattern CHAVE_ARTEFATO = Pattern.compile("^[a-z0-9][a-z0-9-]{0,31}$");
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    /** https em qualquer host; http só em loopback (dev/CI). Sem espaços; caminho obrigatório. */
    private static final Pattern URL = Pattern.compile("^(https://[A-Za-z0-9.-]+(:\\d{1,5})?|http://(localhost|127\\.0\\.0\\.1)(:\\d{1,5})?)/\\S+$");
    private static final ObjectMapper JSON_ESTRITO = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    /** Um instalador publicado. {@code arquivo} = nome puro (sem separador), {@code tamanho} em bytes (> 0). */
    public record Artefato(String arquivo, String url, String sha256, long tamanho) {
        public Artefato {
            Objects.requireNonNull(arquivo);
            Objects.requireNonNull(url);
            Objects.requireNonNull(sha256);
        }
    }

    /** Como o agente foi instalado nesta máquina — decide qual artefato do Linux serve (deb × tar.gz). */
    public enum FormatoInstalado { INSTALADOR, APP_IMAGE }

    public ManifestoRelease {
        artefatos = Collections.unmodifiableMap(new LinkedHashMap<>(artefatos));
    }

    public static ManifestoRelease parse(byte[] json) throws ManifestoInvalidoException {
        Objects.requireNonNull(json);
        if (json.length > TETO_BYTES) {
            throw new ManifestoInvalidoException(Motivo.TAMANHO, json.length + " bytes > " + TETO_BYTES);
        }
        JsonNode n;
        try (JsonParser p = JSON_ESTRITO.createParser(json)) {
            n = JSON_ESTRITO.readTree(p);
        } catch (IOException e) {
            throw new ManifestoInvalidoException(Motivo.JSON, "JSON inválido");
        }
        if (n == null || !n.isObject()) {
            throw new ManifestoInvalidoException(Motivo.JSON, "raiz não é objeto");
        }
        JsonNode formato = n.get("formato");
        if (formato == null || !formato.isInt()) {
            throw new ManifestoInvalidoException(Motivo.JSON, "formato ausente");
        }
        if (formato.intValue() != FORMATO_SUPORTADO) {
            throw new ManifestoInvalidoException(Motivo.FORMATO_NAO_SUPORTADO, "formato " + formato.intValue());
        }
        String versao = texto(n, "versao");
        if (versao == null || !VERSAO.matcher(versao).matches()) {
            throw new ManifestoInvalidoException(Motivo.VERSAO, "versao inválida");
        }
        JsonNode protocolo = n.get("protocolo");
        if (protocolo == null || !protocolo.isInt() || protocolo.intValue() < 1) {
            throw new ManifestoInvalidoException(Motivo.PROTOCOLO, "protocolo inválido");
        }
        String kid = texto(n, "kid");
        if (kid == null || !KID.matcher(kid).matches()) {
            throw new ManifestoInvalidoException(Motivo.KID, "kid inválido");
        }
        JsonNode publicadoEmNode = n.get("publicadoEm");
        String publicadoEm = publicadoEmNode != null && publicadoEmNode.isTextual() ? publicadoEmNode.textValue() : null;
        JsonNode arts = n.get("artefatos");
        if (arts == null || !arts.isObject() || arts.isEmpty()) {
            throw new ManifestoInvalidoException(Motivo.ARTEFATO, "artefatos ausentes");
        }
        Map<String, Artefato> mapa = new LinkedHashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = arts.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            if (!CHAVE_ARTEFATO.matcher(e.getKey()).matches()) {
                throw new ManifestoInvalidoException(Motivo.ARTEFATO, "chave de artefato inválida");
            }
            mapa.put(e.getKey(), artefato(e.getValue()));
        }
        return new ManifestoRelease(formato.intValue(), versao, protocolo.intValue(), publicadoEm, kid, mapa);
    }

    private static Artefato artefato(JsonNode a) throws ManifestoInvalidoException {
        if (a == null || !a.isObject()) {
            throw new ManifestoInvalidoException(Motivo.ARTEFATO, "artefato não é objeto");
        }
        String arquivo = texto(a, "arquivo");
        if (arquivo == null || arquivo.isBlank() || arquivo.length() > 200 || arquivo.contains("/") || arquivo.contains("\\")
                || arquivo.contains("..") || arquivo.chars().anyMatch(Character::isISOControl)) {
            throw new ManifestoInvalidoException(Motivo.ARTEFATO, "arquivo inválido");
        }
        String url = texto(a, "url");
        if (url == null || url.length() > 500 || !URL.matcher(url).matches()) {
            throw new ManifestoInvalidoException(Motivo.ARTEFATO, "url inválida");
        }
        String sha = texto(a, "sha256");
        if (sha == null || !SHA256.matcher(sha.toLowerCase(Locale.ROOT)).matches()) {
            throw new ManifestoInvalidoException(Motivo.ARTEFATO, "sha256 inválido");
        }
        JsonNode tamanho = a.get("tamanho");
        if (tamanho == null || !tamanho.isIntegralNumber() || !tamanho.canConvertToLong() || tamanho.longValue() <= 0) {
            throw new ManifestoInvalidoException(Motivo.ARTEFATO, "tamanho inválido");
        }
        return new Artefato(arquivo, url, sha.toLowerCase(Locale.ROOT), tamanho.longValue());
    }

    private static String texto(JsonNode n, String campo) {
        JsonNode v = n.get(campo);
        return v != null && v.isTextual() ? v.textValue() : null;
    }

    /** Artefato desta plataforma, se publicado. */
    public Optional<Artefato> artefatoPara(String osName, String osArch, FormatoInstalado formato) {
        return chaveArtefato(osName, osArch, formato).map(artefatos::get);
    }

    /**
     * Chave do artefato por plataforma: Windows só x64 ({@code windows}); Linux x64 = {@code linux} (.deb) ou {@code linux-tar}
     * (app-image); macOS por arquitetura. Plataforma sem instalador publicado → vazio (o agente avisa, não tenta adivinhar).
     */
    public static Optional<String> chaveArtefato(String osName, String osArch, FormatoInstalado formato) {
        String so = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        String arch = osArch == null ? "" : osArch.toLowerCase(Locale.ROOT);
        boolean x64 = arch.equals("amd64") || arch.equals("x86_64") || arch.equals("x64");
        boolean arm64 = arch.equals("aarch64") || arch.equals("arm64");
        if (so.contains("win")) {
            return x64 ? Optional.of("windows") : Optional.empty();
        }
        if (so.contains("mac") || so.contains("darwin")) {
            return arm64 ? Optional.of("macos-arm64") : x64 ? Optional.of("macos-x64") : Optional.empty();
        }
        if (so.contains("linux")) {
            if (!x64) {
                return Optional.empty();
            }
            return Optional.of(formato == FormatoInstalado.APP_IMAGE ? "linux-tar" : "linux");
        }
        return Optional.empty();
    }
}

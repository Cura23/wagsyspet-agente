package br.com.wagner.wagsyspet.agente.protocolo.release;

import java.io.IOException;
import java.io.InputStream;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

/**
 * Raiz de confiança do self-update: as chaves PÚBLICAS de release embutidas em {@code release.properties} — a ATUAL (que assina o
 * {@code latest.json} hoje) e uma RESERVA gerada offline (plano F6 D12: rotacionar sem reinstalar a frota — a próxima release já
 * pode ser assinada pela reserva, e este binário aceita). Falha rápido se um {@code kid} não corresponder à sua chave: binário com
 * raiz inconsistente não pode ser publicado (o CI roda o teste que carrega isto).
 */
public final class ChavesRelease {

    public static final String RECURSO = "/release.properties";

    /** Uma chave pública identificada pelo {@code kid} (16 hex = 8 bytes do SHA-256 do SPKI). */
    public record Chave(String kid, PublicKey publica, boolean reserva) { }

    private final List<Chave> chaves;

    private ChavesRelease(List<Chave> chaves) {
        this.chaves = Collections.unmodifiableList(chaves);
    }

    /** As chaves embutidas no binário ({@code release.properties} deste módulo). */
    public static ChavesRelease embutidas() {
        Properties p = new Properties();
        try (InputStream in = ChavesRelease.class.getResourceAsStream(RECURSO)) {
            if (in == null) {
                throw new IllegalStateException("release.properties ausente — build inválido");
            }
            p.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("release.properties ilegível", e);
        }
        return de(p);
    }

    /** Só uma chave, vinda de fora (override {@code -Dagente.release.chave.publica} para o smoke do CI — plano F6 D11). */
    public static ChavesRelease deUmaPublica(String spkiBase64) {
        PublicKey k = VerificadorAssinaturaRelease.importarPublica(spkiBase64.trim());
        return new ChavesRelease(List.of(new Chave(VerificadorAssinaturaRelease.kid(k), k, false)));
    }

    public static ChavesRelease de(Properties p) {
        List<Chave> lista = new ArrayList<>(2);
        lista.add(carregar(p, "release.chave.publica", "release.kid", false, "atual"));
        String reserva = p.getProperty("release.chave.publica.reserva", "").trim();
        String kidReserva = p.getProperty("release.kid.reserva", "").trim();
        if (!reserva.isEmpty() || !kidReserva.isEmpty()) {
            lista.add(carregar(p, "release.chave.publica.reserva", "release.kid.reserva", true, "reserva"));
            if (lista.get(0).kid().equals(lista.get(1).kid())) {
                throw new IllegalStateException("chave reserva igual à atual — não é reserva");
            }
        }
        return new ChavesRelease(lista);
    }

    private static Chave carregar(Properties p, String propChave, String propKid, boolean reserva, String nome) {
        String b64 = p.getProperty(propChave, "").trim();
        String kid = p.getProperty(propKid, "").trim();
        if (b64.isEmpty() || kid.isEmpty()) {
            throw new IllegalStateException("release.properties sem chave pública/kid (" + nome + ") — build inválido");
        }
        PublicKey publica = VerificadorAssinaturaRelease.importarPublica(b64);
        String calculado = VerificadorAssinaturaRelease.kid(publica);
        if (!calculado.equals(kid)) {
            throw new IllegalStateException("kid " + nome + " (" + kid + ") não corresponde à chave embutida (" + calculado + ")");
        }
        return new Chave(kid, publica, reserva);
    }

    public List<Chave> todas() {
        return chaves;
    }

    public Optional<Chave> porKid(String kid) {
        return chaves.stream().filter(c -> c.kid().equals(kid)).findFirst();
    }

    public String kidAtual() {
        return chaves.get(0).kid();
    }

    public PublicKey publicaAtual() {
        return chaves.get(0).publica();
    }

    /** A chave que assinou {@code conteudo}, se alguma das embutidas confere. */
    public Optional<Chave> quemAssinou(byte[] conteudo, byte[] assinatura) {
        return chaves.stream().filter(c -> VerificadorAssinaturaRelease.verificar(conteudo, assinatura, c.publica())).findFirst();
    }
}

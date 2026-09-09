package br.com.wagner.wagsyspet.agente.protocolo.release;

import br.com.wagner.wagsyspet.agente.protocolo.ticket.ChavesTicket;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Locale;
import java.util.Objects;

/**
 * Verifica a assinatura DETACHED do {@code latest.json} publicado a cada release (plano F3 D23): Ed25519 puro sobre os
 * bytes crus do arquivo — exatamente o que {@code openssl pkeyutl -sign -rawin} produz no workflow de release (64 bytes).
 * A chave é o par de RELEASE, separado do par do ticket (vazar um não compromete o outro). A pública viaja embutida no
 * agente ({@code release.properties}) e identificada por {@code kid} = 8 primeiros bytes do SHA-256 do SPKI (rotação na F6).
 *
 * <p>Na F3 só se prova a interoperabilidade (fixture assinada pelo OpenSSL); quem usa de verdade é o self-update da F6,
 * que também exige versão maior e sha256 do instalador batendo.</p>
 */
public final class VerificadorAssinaturaRelease {

    public static final int TAMANHO_ASSINATURA = 64;

    private VerificadorAssinaturaRelease() {
    }

    /** {@code true} só se a assinatura Ed25519 de {@code conteudo} bate com {@code chavePublica}. Nunca lança. */
    public static boolean verificar(byte[] conteudo, byte[] assinatura, PublicKey chavePublica) {
        Objects.requireNonNull(conteudo);
        Objects.requireNonNull(chavePublica);
        if (assinatura == null || assinatura.length != TAMANHO_ASSINATURA) {
            return false;
        }
        try {
            Signature s = Signature.getInstance(ChavesTicket.ALGORITMO);
            s.initVerify(chavePublica);
            s.update(conteudo);
            return s.verify(assinatura);
        } catch (GeneralSecurityException | RuntimeException e) {
            return false;
        }
    }

    /** Chave pública de release em SPKI base64 (mesmo formato da chave do ticket). */
    public static PublicKey importarPublica(String spkiBase64) {
        PublicKey k = ChavesTicket.importarPublica(spkiBase64);
        ChavesTicket.exigirPublicaUtilizavel(k);
        return k;
    }

    /** {@code kid} = 16 hex (8 bytes) do SHA-256 do SPKI DER — o mesmo que {@code openssl pkey -pubin -outform DER | sha256sum | cut -c1-16}. */
    public static String kid(PublicKey chavePublica) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(chavePublica.getEncoded());
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(String.format(Locale.ROOT, "%02x", d[i]));
            }
            return sb.toString();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 indisponível", e);
        }
    }
}

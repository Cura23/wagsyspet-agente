package br.com.wagner.wagsyspet.agente.protocolo.ticket;

import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Par de chaves Ed25519 do ticket (JDK 21 nativo; provider {@code SunEC}, módulo {@code jdk.crypto.ec} — o runtime
 * {@code jlink} do agente PRECISA incluir esse módulo, senão {@code Signature.getInstance("Ed25519")} falha).
 *
 * <p>Formato em texto (viaja no pareamento e na env do backend): <b>base64 padrão</b> do DER —
 * pública = SubjectPublicKeyInfo (X.509), privada = PKCS#8. É o que {@code Key.getEncoded()} devolve nos dois lados,
 * então não há conversão manual de bytes crus (fonte clássica de incompatibilidade).</p>
 */
public final class ChavesTicket {

    public static final String ALGORITMO = "Ed25519";

    private ChavesTicket() {
    }

    public static KeyPair gerar() {
        try {
            return KeyPairGenerator.getInstance(ALGORITMO).generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(ALGORITMO + " indisponível neste runtime (falta o módulo jdk.crypto.ec?)", e);
        }
    }

    public static String exportarPublica(PublicKey chave) {
        return Base64.getEncoder().encodeToString(chave.getEncoded());
    }

    public static String exportarPrivada(PrivateKey chave) {
        return Base64.getEncoder().encodeToString(chave.getEncoded());
    }

    public static PublicKey importarPublica(String base64) {
        PublicKey chave;
        try {
            chave = KeyFactory.getInstance(ALGORITMO).generatePublic(new X509EncodedKeySpec(decodificar(base64)));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Chave pública não é Ed25519 (SPKI base64) válida", e);
        }
        exigirPublicaUtilizavel(chave);
        return chave;
    }

    /**
     * Fail-fast: o {@code KeyFactory} aceita qualquer SPKI Ed25519 sintaticamente válido, inclusive pontos que não
     * existem na curva (y ≥ p, y fora da curva — metade dos 32 bytes aleatórios). Só o {@code initVerify} descobre, e
     * sem isto ele descobriria no 1º ticket, com uma exceção genérica no meio do WebSocket. Também barra chave de
     * outro algoritmo (X25519/Ed448/RSA) passada direto ao construtor do verificador.
     *
     * @throws IllegalArgumentException com a causa real (não "falta módulo")
     */
    public static void exigirPublicaUtilizavel(PublicKey chave) {
        try {
            Signature.getInstance(ALGORITMO).initVerify(chave);
        } catch (InvalidKeyException e) {
            throw new IllegalArgumentException("Chave pública não é uma chave Ed25519 utilizável: " + e.getMessage(), e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(ALGORITMO + " indisponível neste runtime (falta o módulo jdk.crypto.ec?)", e);
        }
    }

    public static PrivateKey importarPrivada(String base64) {
        try {
            return KeyFactory.getInstance(ALGORITMO).generatePrivate(new PKCS8EncodedKeySpec(decodificar(base64)));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Chave privada não é Ed25519 (PKCS#8 base64) válida", e);
        }
    }

    private static byte[] decodificar(String base64) {
        if (base64 == null || base64.isBlank()) {
            throw new IllegalArgumentException("chave vazia");
        }
        return Base64.getDecoder().decode(base64.trim());
    }
}

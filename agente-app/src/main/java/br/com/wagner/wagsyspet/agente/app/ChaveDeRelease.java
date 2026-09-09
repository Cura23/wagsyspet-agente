package br.com.wagner.wagsyspet.agente.app;

import br.com.wagner.wagsyspet.agente.protocolo.release.VerificadorAssinaturaRelease;

import java.io.IOException;
import java.io.InputStream;
import java.security.PublicKey;
import java.util.Properties;

/**
 * Chave pública de release embutida ({@code release.properties}) — quem verifica o {@code latest.json} assinado (F6).
 * Falha rápido se o recurso faltar ou o {@code kid} não bater com a chave: um binário com chave/kid inconsistentes não pode
 * ser publicado (o CI roda o teste que chama isto).
 */
final class ChaveDeRelease {

    private final PublicKey publica;
    private final String kid;

    private ChaveDeRelease(PublicKey publica, String kid) {
        this.publica = publica;
        this.kid = kid;
    }

    static ChaveDeRelease embutida() {
        Properties p = new Properties();
        try (InputStream in = ChaveDeRelease.class.getResourceAsStream("/release.properties")) {
            if (in == null) {
                throw new IllegalStateException("release.properties ausente — build inválido");
            }
            p.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("release.properties ilegível", e);
        }
        String b64 = p.getProperty("release.chave.publica", "").trim();
        String kid = p.getProperty("release.kid", "").trim();
        if (b64.isEmpty() || kid.isEmpty()) {
            throw new IllegalStateException("release.properties sem chave pública/kid — build inválido");
        }
        PublicKey publica = VerificadorAssinaturaRelease.importarPublica(b64);
        String calculado = VerificadorAssinaturaRelease.kid(publica);
        if (!calculado.equals(kid)) {
            throw new IllegalStateException("release.kid (" + kid + ") não corresponde à chave embutida (" + calculado + ")");
        }
        return new ChaveDeRelease(publica, kid);
    }

    PublicKey publica() {
        return publica;
    }

    String kid() {
        return kid;
    }

    boolean verificar(byte[] latestJson, byte[] assinatura) {
        return VerificadorAssinaturaRelease.verificar(latestJson, assinatura, publica);
    }
}

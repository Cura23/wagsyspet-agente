package br.com.wagner.wagsyspet.agente.protocolo.release;

import br.com.wagner.wagsyspet.agente.protocolo.ticket.ChavesTicket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.Signature;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Interop com o workflow de release: a fixture em {@code src/test/resources/release/} foi assinada pelo OpenSSL
 * ({@code openssl pkeyutl -sign -rawin}) com um par DESCARTÁVEL — a chave real de release nunca entra no repo.
 */
@DisplayName("VerificadorAssinaturaRelease — latest.json assinado pelo OpenSSL, verificado em Java")
class VerificadorAssinaturaReleaseTest {

    private static byte[] recurso(String nome) throws IOException {
        try (InputStream in = VerificadorAssinaturaReleaseTest.class.getResourceAsStream("/release/" + nome)) {
            assertThat(in).as("fixture " + nome).isNotNull();
            return in.readAllBytes();
        }
    }

    @Test
    @DisplayName("fixture do OpenSSL: assinatura de 64 bytes bate com a chave pública SPKI base64")
    void interopOpenssl() throws IOException {
        byte[] json = recurso("latest.json");
        byte[] sig = recurso("latest.json.sig");
        PublicKey pub = VerificadorAssinaturaRelease.importarPublica(new String(recurso("descartavel-pub.b64"), StandardCharsets.US_ASCII).trim());
        assertThat(sig).hasSize(64);
        assertThat(VerificadorAssinaturaRelease.verificar(json, sig, pub)).isTrue();
    }

    @Test
    @DisplayName("1 byte trocado no JSON, na assinatura, ou outra chave → false; assinatura de tamanho errado → false; nunca lança")
    void recusas() throws IOException {
        byte[] json = recurso("latest.json");
        byte[] sig = recurso("latest.json.sig");
        PublicKey pub = VerificadorAssinaturaRelease.importarPublica(new String(recurso("descartavel-pub.b64"), StandardCharsets.US_ASCII).trim());

        byte[] jsonAdulterado = json.clone();
        jsonAdulterado[10] ^= 1;
        assertThat(VerificadorAssinaturaRelease.verificar(jsonAdulterado, sig, pub)).isFalse();

        byte[] sigAdulterada = sig.clone();
        sigAdulterada[3] ^= 1;
        assertThat(VerificadorAssinaturaRelease.verificar(json, sigAdulterada, pub)).isFalse();

        assertThat(VerificadorAssinaturaRelease.verificar(json, sig, ChavesTicket.gerar().getPublic())).isFalse();
        assertThat(VerificadorAssinaturaRelease.verificar(json, new byte[63], pub)).isFalse();
        assertThat(VerificadorAssinaturaRelease.verificar(json, null, pub)).isFalse();
    }

    @Test
    @DisplayName("Java assina → Java verifica (mesmo algoritmo Ed25519 puro); kid = 16 hex estável")
    void javaJava() throws Exception {
        KeyPair kp = ChavesTicket.gerar();
        byte[] msg = "{\"versao\":\"9.9.9\"}".getBytes(StandardCharsets.UTF_8);
        Signature s = Signature.getInstance(ChavesTicket.ALGORITMO);
        s.initSign(kp.getPrivate());
        s.update(msg);
        byte[] sig = s.sign();
        assertThat(VerificadorAssinaturaRelease.verificar(msg, sig, kp.getPublic())).isTrue();
        String kid = VerificadorAssinaturaRelease.kid(kp.getPublic());
        assertThat(kid).matches("[0-9a-f]{16}");
        assertThat(VerificadorAssinaturaRelease.kid(kp.getPublic())).isEqualTo(kid);
        assertThat(VerificadorAssinaturaRelease.kid(ChavesTicket.gerar().getPublic())).isNotEqualTo(kid);
    }
}

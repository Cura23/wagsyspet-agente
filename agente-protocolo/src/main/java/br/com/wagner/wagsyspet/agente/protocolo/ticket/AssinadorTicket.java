package br.com.wagner.wagsyspet.agente.protocolo.ticket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Base64;
import java.util.Objects;

/**
 * Lado do BACKEND: assina as claims com a chave privada Ed25519 da instalação.
 *
 * <p>Formato do ticket: {@code v1.<base64url(payload JSON)>.<base64url(assinatura)>}, sem padding, só ASCII seguro
 * pra JSON e URL. A assinatura cobre os bytes ASCII de {@code "v1." + base64url(payload)} — o prefixo de versão está
 * DENTRO do que é assinado, então não dá pra rebaixar/trocar o algoritmo sem invalidar a assinatura. Não existe
 * header "alg": o verificador só sabe Ed25519.</p>
 *
 * <p><b>Forma canônica</b> (é o que a paridade dos vetores exige byte a byte): payload JSON compacto com os campos na
 * ordem {@code lojaId, agenteId, iat, exp, jti}; base64url <i>sem</i> padding; assinatura = exatamente 86 chars. O
 * verificador aceita qualquer serialização do payload (a assinatura cobre os bytes exatos, então não há risco), mas
 * exige a assinatura canônica. Quem copiar esta classe pro backend não pode trocar {@code ObjectNode} por outro
 * serializador sem regenerar os vetores.</p>
 *
 * <p><b>Separação de domínio:</b> a chave Ed25519 do ticket assina SÓ tickets. Se um dia outra coisa precisar de
 * assinatura (ex.: {@code latest.json} do auto-update), usa-se OUTRO par de chaves — nunca esta.</p>
 *
 * <p>Esta classe é copiada para o backend na F1 (mesmo pacote lógico). A paridade entre as cópias é garantida pelos
 * <b>vetores de teste</b> em {@code src/test/resources/vetores-ticket-v1.json}, que os dois repos rodam.</p>
 */
public final class AssinadorTicket {

    public static final String VERSAO = "v1";
    static final ObjectMapper JSON = new ObjectMapper();
    static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private final PrivateKey chavePrivada;

    public AssinadorTicket(PrivateKey chavePrivada) {
        this.chavePrivada = Objects.requireNonNull(chavePrivada, "chavePrivada");
    }

    public String assinar(TicketClaims claims) {
        ObjectNode n = JSON.createObjectNode();
        n.put("lojaId", claims.lojaId());
        n.put("agenteId", claims.agenteId());
        n.put("iat", claims.iat());
        n.put("exp", claims.exp());
        n.put("jti", claims.jti());
        return assinarBruto(n.toString());
    }

    /** Assina um payload JSON arbitrário — só pra testes adversariais (payload sem campo, tipo errado…). */
    String assinarBruto(String payloadJson) {
        String corpo = VERSAO + "." + B64.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        try {
            Signature sig = Signature.getInstance(ChavesTicket.ALGORITMO);
            sig.initSign(chavePrivada);
            sig.update(corpo.getBytes(StandardCharsets.US_ASCII));
            return corpo + "." + B64.encodeToString(sig.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Falha ao assinar ticket Ed25519", e);
        }
    }
}

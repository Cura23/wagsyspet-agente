package br.com.wagner.wagsyspet.agente.protocolo.ticket;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;

/**
 * Conteúdo assinado do ticket (plano §2.4): a loja e o caixa a que ele se destina, emissão/validade em segundos Unix
 * e um identificador único ({@code jti}) para o agente barrar replay.
 *
 * @param lojaId   loja do backend (tenant); o agente só aceita a loja com que foi pareado
 * @param agenteId identificador do caixa gerado no pareamento; o agente só aceita o próprio
 * @param iat      emissão (epoch segundos) — informativo, não validado (relógio do caixa pode estar atrasado)
 * @param exp      expiração (epoch segundos) — validado com tolerância
 * @param jti      16 bytes aleatórios em base64url sem padding
 */
public record TicketClaims(long lojaId, String agenteId, long iat, long exp, String jti) {

    private static final SecureRandom RANDOM = new SecureRandom();

    public TicketClaims {
        Objects.requireNonNull(agenteId, "agenteId");
        Objects.requireNonNull(jti, "jti");
        if (agenteId.isBlank()) {
            throw new IllegalArgumentException("agenteId em branco");
        }
        if (iat <= 0) {
            throw new IllegalArgumentException("iat inválido");
        }
        if (exp <= 0 || exp < iat) {
            throw new IllegalArgumentException("exp inválido (deve ser > 0 e >= iat)");
        }
    }

    /** Claims novas emitidas AGORA com validade {@code ttl} (o backend usa 10 min) e {@code jti} aleatório. */
    public static TicketClaims novo(long lojaId, String agenteId, Instant agora, Duration ttl) {
        long iat = agora.getEpochSecond();
        return new TicketClaims(lojaId, agenteId, iat, iat + ttl.toSeconds(), novoJti());
    }

    public static String novoJti() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}

package br.com.wagner.wagsyspet.agente.protocolo.ticket;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

import static br.com.wagner.wagsyspet.agente.protocolo.ticket.TicketInvalidoException.Motivo;

/**
 * Lado do AGENTE: verifica o ticket com a chave pública recebida no pareamento e as identidades pareadas.
 *
 * <p>Ordem das checagens (fail-closed, sem vazar informação antes da assinatura): forma → versão → <b>assinatura</b>
 * → campos → validade plausível → expiração (com tolerância de relógio) → loja → agente → replay ({@code jti}).
 * O cache de replay guarda só {@code jti → exp} e é purgado a cada verificação, então fica limitado ao TTL do ticket
 * (10 min no backend) — e o teto de validade garante que nenhum {@code jti} fica lá para sempre.</p>
 *
 * <p>Tolerância de relógio: o PC do caixa pode estar adiantado alguns minutos; um ticket com {@code exp} até
 * {@code tolerancia} no passado ainda vale. {@code iat} não é validado contra o relógio (relógio atrasado não pode
 * travar a loja), mas {@code exp − iat} tem teto ({@link #TETO_VALIDADE}) e {@code exp} não pode estar além de
 * {@code agora + teto}: um emissor com bug de unidade (ms em vez de s) produziria tickets "eternos".</p>
 *
 * <p>O que este módulo NÃO decide (F3): de onde vem a chave pública e as identidades (arquivo de pareamento, ACL do
 * usuário, fingerprint) e persistência do cache de replay entre reinícios — hoje é só em memória, trade-off aceito
 * porque o TTL é curto e o ticket é de uso único por conexão.</p>
 */
public final class VerificadorTicket {

    /** Maior ticket aceito; um ticket real tem ~250 bytes. Acima disso nem se tenta decodificar. */
    public static final int TAMANHO_MAXIMO = 4096;
    /** Maior validade plausível ({@code exp − iat} e {@code exp − agora}). O backend emite 10 min; 1 h dá folga. */
    public static final Duration TETO_VALIDADE = Duration.ofHours(1);

    private static final int TAMANHO_ASSINATURA_ED25519 = 64;
    /** 64 bytes em base64url sem padding = exatamente 86 chars do alfabeto url-safe: forma canônica ÚNICA. */
    private static final Pattern ASSINATURA_CANONICA = Pattern.compile("[A-Za-z0-9_-]{86}");
    /** Identificadores (agenteId = UUID, jti = 22 chars base64url): sem espaço, quebra de linha ou controle — vão pra log. */
    private static final Pattern IDENTIFICADOR = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern PREFIXO_VERSAO = Pattern.compile("v\\d{1,3}");
    private static final Base64.Decoder B64 = Base64.getUrlDecoder();
    /** Parser ESTRITO: chave duplicada e lixo depois do objeto são recusados (não "último vence"). */
    private static final ObjectMapper JSON_ESTRITO = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private final PublicKey chavePublica;
    private final long lojaIdPareada;
    private final String agenteIdProprio;
    private final Clock relogio;
    private final Duration tolerancia;
    private final ConcurrentMap<String, Long> jtisAceitos; // jti → exp (epoch s)

    /**
     * @throws IllegalArgumentException se a chave não for uma chave pública Ed25519 utilizável (outro algoritmo, ponto
     *                                  inválido) — falha aqui, na subida, e não no 1º ticket com mensagem enganosa
     */
    public VerificadorTicket(PublicKey chavePublica, long lojaIdPareada, String agenteIdProprio, Clock relogio, Duration tolerancia) {
        this(chavePublica, lojaIdPareada, agenteIdProprio, relogio, tolerancia, new ConcurrentHashMap<>());
        ChavesTicket.exigirPublicaUtilizavel(chavePublica);
    }

    private VerificadorTicket(PublicKey chavePublica, long lojaIdPareada, String agenteIdProprio, Clock relogio,
                              Duration tolerancia, ConcurrentMap<String, Long> jtisAceitos) {
        this.chavePublica = Objects.requireNonNull(chavePublica, "chavePublica");
        this.lojaIdPareada = lojaIdPareada;
        this.agenteIdProprio = Objects.requireNonNull(agenteIdProprio, "agenteIdProprio");
        this.relogio = Objects.requireNonNull(relogio, "relogio");
        this.tolerancia = Objects.requireNonNull(tolerancia, "tolerancia");
        this.jtisAceitos = jtisAceitos;
    }

    /** Mesmo verificador (mesma chave, identidades e cache de replay) com outro relógio — testes e injeção. */
    public VerificadorTicket comRelogio(Clock outroRelogio) {
        return new VerificadorTicket(chavePublica, lojaIdPareada, agenteIdProprio, outroRelogio, tolerancia, jtisAceitos);
    }

    /** Quantos {@code jti} ainda estão lembrados contra replay (observabilidade/testes). */
    public int jtisLembrados() {
        return jtisAceitos.size();
    }

    public TicketClaims verificar(String ticket) throws TicketInvalidoException {
        long agora = relogio.instant().getEpochSecond();
        purgarVencidos(agora);

        // 1) forma
        if (ticket == null || ticket.isEmpty()) {
            throw new TicketInvalidoException(Motivo.FORMATO, "ticket vazio");
        }
        if (ticket.length() > TAMANHO_MAXIMO) {
            throw new TicketInvalidoException(Motivo.FORMATO, "ticket com " + ticket.length() + " chars (máx " + TAMANHO_MAXIMO + ")");
        }
        String[] partes = ticket.split("\\.", -1);
        if (partes.length != 3 || partes[1].isEmpty() || partes[2].isEmpty()) {
            throw new TicketInvalidoException(Motivo.FORMATO, "esperado 'versao.payload.assinatura'");
        }
        // 2) versão
        if (!AssinadorTicket.VERSAO.equals(partes[0])) {
            if (PREFIXO_VERSAO.matcher(partes[0]).matches()) {
                throw new TicketInvalidoException(Motivo.VERSAO, "versão de ticket desconhecida: " + partes[0]);
            }
            throw new TicketInvalidoException(Motivo.FORMATO, "prefixo de versão ausente");
        }
        // 3) assinatura — forma canônica única (86 chars url-safe = 64 bytes), antes de olhar qualquer claim
        if (!ASSINATURA_CANONICA.matcher(partes[2]).matches()) {
            throw new TicketInvalidoException(Motivo.FORMATO, "assinatura fora da forma canônica (86 chars base64url sem padding)");
        }
        byte[] assinatura = decodificar(partes[2], "assinatura");
        if (assinatura.length != TAMANHO_ASSINATURA_ED25519) {
            throw new TicketInvalidoException(Motivo.ASSINATURA, "assinatura com " + assinatura.length + " bytes");
        }
        byte[] corpo = (partes[0] + "." + partes[1]).getBytes(StandardCharsets.US_ASCII);
        if (!assinaturaValida(corpo, assinatura)) {
            throw new TicketInvalidoException(Motivo.ASSINATURA, "assinatura não confere com a chave pareada");
        }
        // 4) campos
        TicketClaims claims = lerClaims(decodificar(partes[1], "payload"));
        // 5) validade plausível (protege de emissor com bug e de jti eterno no cache) — sem overflow: só subtrações
        long teto = TETO_VALIDADE.toSeconds();
        if (claims.exp() - claims.iat() > teto || claims.exp() - agora > teto) {
            throw new TicketInvalidoException(Motivo.VALIDADE_ABSURDA,
                    "exp=" + claims.exp() + " iat=" + claims.iat() + " agora=" + agora + " (teto " + teto + " s)");
        }
        // 6) expiração com tolerância
        if (claims.exp() < agora - tolerancia.toSeconds()) {
            throw new TicketInvalidoException(Motivo.EXPIRADO, "exp=" + claims.exp() + " agora=" + agora);
        }
        // 7) identidades pareadas
        if (claims.lojaId() != lojaIdPareada) {
            throw new TicketInvalidoException(Motivo.LOJA_DIVERGENTE, "ticket da loja " + claims.lojaId() + ", pareado com " + lojaIdPareada);
        }
        if (!agenteIdProprio.equals(claims.agenteId())) {
            throw new TicketInvalidoException(Motivo.AGENTE_DIVERGENTE, "ticket para outro caixa");
        }
        // 8) replay
        if (jtisAceitos.putIfAbsent(claims.jti(), claims.exp()) != null) {
            throw new TicketInvalidoException(Motivo.REPETIDO, "jti já aceito: " + claims.jti());
        }
        return claims;
    }

    private void purgarVencidos(long agora) {
        long limite = agora - tolerancia.toSeconds();
        jtisAceitos.entrySet().removeIf(e -> e.getValue() < limite);
    }

    private boolean assinaturaValida(byte[] corpo, byte[] assinatura) {
        try {
            Signature sig = Signature.getInstance(ChavesTicket.ALGORITMO);
            sig.initVerify(chavePublica);
            sig.update(corpo);
            return sig.verify(assinatura);
        } catch (java.security.SignatureException e) {
            return false; // assinatura malformada = inválida
        } catch (java.security.InvalidKeyException e) {
            // O construtor já provou a chave; se cair aqui a chave mudou por baixo — nunca culpar o runtime.
            throw new IllegalStateException("Chave pública Ed25519 inutilizável: " + e.getMessage(), e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 indisponível neste runtime (falta o módulo jdk.crypto.ec?)", e);
        }
    }

    private static byte[] decodificar(String b64url, String oQue) throws TicketInvalidoException {
        try {
            return B64.decode(b64url);
        } catch (IllegalArgumentException e) {
            throw new TicketInvalidoException(Motivo.FORMATO, oQue + " não é base64url");
        }
    }

    private static TicketClaims lerClaims(byte[] payload) throws TicketInvalidoException {
        JsonNode n;
        try (JsonParser p = JSON_ESTRITO.createParser(payload)) {
            n = JSON_ESTRITO.readTree(p);
            if (p.nextToken() != null) {
                throw new TicketInvalidoException(Motivo.FORMATO, "conteúdo depois do objeto JSON");
            }
        } catch (java.io.IOException e) {
            throw new TicketInvalidoException(Motivo.FORMATO, "payload não é JSON estrito");
        }
        if (n == null || !n.isObject()) {
            throw new TicketInvalidoException(Motivo.FORMATO, "payload não é objeto JSON");
        }
        long lojaId = inteiro(n, "lojaId");
        String agenteId = identificador(n, "agenteId");
        long iat = inteiro(n, "iat");
        long exp = inteiro(n, "exp");
        String jti = identificador(n, "jti");
        try {
            return new TicketClaims(lojaId, agenteId, iat, exp, jti);
        } catch (IllegalArgumentException e) {
            throw new TicketInvalidoException(Motivo.CAMPO_AUSENTE, e.getMessage());
        }
    }

    private static long inteiro(JsonNode n, String campo) throws TicketInvalidoException {
        JsonNode v = n.get(campo);
        if (v == null || !v.isIntegralNumber() || !v.canConvertToLong()) {
            throw new TicketInvalidoException(Motivo.CAMPO_AUSENTE, campo + " ausente ou não inteiro");
        }
        return v.longValue();
    }

    private static String identificador(JsonNode n, String campo) throws TicketInvalidoException {
        JsonNode v = n.get(campo);
        if (v == null || !v.isTextual() || !IDENTIFICADOR.matcher(v.textValue()).matches()) {
            throw new TicketInvalidoException(Motivo.CAMPO_AUSENTE, campo + " ausente ou fora de [A-Za-z0-9_-]{1,64}");
        }
        return v.textValue();
    }
}

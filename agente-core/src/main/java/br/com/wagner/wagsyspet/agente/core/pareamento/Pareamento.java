package br.com.wagner.wagsyspet.agente.core.pareamento;

import br.com.wagner.wagsyspet.agente.protocolo.ticket.ChavesTicket;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.security.PublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * O que o agente guarda do pareamento (plano F3 D14) — é a resposta do {@code POST /parear} do backend F1
 * ({@code PareamentoRespostaDTO}) mais a URL do backend usada e o instante. Tudo validado ao entrar (parser ESTRITO,
 * fail-closed): um campo torto aqui viraria erro enigmático semanas depois, no 1º ticket.
 *
 * <p>{@code agenteId} é escolhido pelo BACKEND (UUID) — o agente nunca inventa identidade. {@code credencial} é guardada
 * mas não tem consumidor no F1 (reservada). {@code chavePublicaTicket} é a SPKI Ed25519 em base64 padrão que o
 * {@code VerificadorTicket} usa.</p>
 */
public record Pareamento(String agenteId, long lojaId, String credencial, String chavePublicaTicket,
                         List<String> origensPermitidas, int portaSugerida, String versaoMinima,
                         String backendUrl, Instant pareadoEm) {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern IDENTIFICADOR = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    /** Origin exata: esquema + host [+ porta], minúsculas, sem barra final (é comparada byte a byte no handshake). */
    private static final Pattern ORIGIN = Pattern.compile("^(https://[a-z0-9.-]+(:\\d{1,5})?|http://(localhost|127\\.0\\.0\\.1)(:\\d{1,5})?)$");

    public Pareamento {
        Objects.requireNonNull(agenteId, "agenteId");
        Objects.requireNonNull(chavePublicaTicket, "chavePublicaTicket");
        Objects.requireNonNull(backendUrl, "backendUrl");
        Objects.requireNonNull(pareadoEm, "pareadoEm");
        origensPermitidas = List.copyOf(Objects.requireNonNull(origensPermitidas, "origensPermitidas"));
    }

    /** Monta e valida a partir do JSON devolvido pelo backend. */
    public static Pareamento daResposta(JsonNode n, String backendUrl, Instant agora) throws PareamentoException {
        if (n == null || !n.isObject()) {
            throw invalida("resposta não é um objeto JSON");
        }
        String agenteId = texto(n, "agenteId");
        if (agenteId == null || !IDENTIFICADOR.matcher(agenteId).matches()) {
            throw invalida("agenteId ausente ou inválido");
        }
        JsonNode loja = n.get("lojaId");
        if (loja == null || !loja.isIntegralNumber() || loja.asLong() <= 0) {
            throw invalida("lojaId ausente ou inválido");
        }
        String chave = texto(n, "chavePublicaTicket");
        if (chave == null) {
            throw invalida("chave pública do ticket ausente");
        }
        try {
            ChavesTicket.exigirPublicaUtilizavel(ChavesTicket.importarPublica(chave));
        } catch (RuntimeException e) {
            throw invalida("chave pública do ticket inválida: " + e.getMessage());
        }
        JsonNode origens = n.get("origensPermitidas");
        if (origens == null || !origens.isArray() || origens.isEmpty()) {
            throw invalida("origensPermitidas ausente ou vazia — o servidor não informou de onde o PWA pode conectar");
        }
        List<String> lista = new ArrayList<>();
        for (JsonNode o : origens) {
            String v = o.isTextual() ? o.asText().trim() : "";
            if (!ORIGIN.matcher(v).matches()) {
                throw invalida("origensPermitidas contém um valor que não é uma Origin exata: '" + v + "'");
            }
            lista.add(v);
        }
        JsonNode porta = n.get("portaSugerida");
        int portaSugerida = porta == null || porta.isNull() ? 0 : porta.asInt(-1);
        if (portaSugerida < 1 || portaSugerida > 65535) {
            throw invalida("portaSugerida fora de 1..65535");
        }
        String versaoMinima = texto(n, "versaoMinima");
        String credencial = texto(n, "credencial");
        return new Pareamento(agenteId, loja.asLong(), credencial, chave, lista, portaSugerida, versaoMinima,
                Objects.requireNonNull(backendUrl), Objects.requireNonNull(agora));
    }

    /** Resposta 200 do BACKEND fora do contrato: o código JÁ foi consumido lá (o CAS vem antes da resposta) — não prometer reuso. */
    private static PareamentoException invalida(String detalhe) {
        return new PareamentoException(PareamentoException.Motivo.RESPOSTA_INVALIDA,
                "O servidor respondeu fora do esperado (" + detalhe + "). O código foi consumido: gere OUTRO no painel e avise o suporte AgroEase.", false);
    }

    private static String texto(JsonNode n, String campo) {
        JsonNode v = n.get(campo);
        if (v == null || v.isNull() || !v.isTextual()) {
            return null;
        }
        String s = v.asText().trim();
        return s.isEmpty() ? null : s;
    }

    public PublicKey chavePublica() {
        return ChavesTicket.importarPublica(chavePublicaTicket);
    }

    /** Fingerprint curto da chave pública (para log e {@code --status}, nunca a chave inteira). */
    public String fingerprintChave() {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(chavePublica().getEncoded());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format(Locale.ROOT, "%02x", d[i]));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return "?";
        }
    }

    // ── persistência (o cofre cifra este JSON) ────────────────────────────────────────────────────────────────

    public String paraJson() {
        ObjectNode n = JSON.createObjectNode();
        n.put("formato", 1);
        n.put("agenteId", agenteId);
        n.put("lojaId", lojaId);
        if (credencial != null) {
            n.put("credencial", credencial);
        }
        n.put("chavePublicaTicket", chavePublicaTicket);
        ArrayNode arr = n.putArray("origensPermitidas");
        origensPermitidas.forEach(arr::add);
        n.put("portaSugerida", portaSugerida);
        if (versaoMinima != null) {
            n.put("versaoMinima", versaoMinima);
        }
        n.put("backendUrl", backendUrl);
        n.put("pareadoEm", pareadoEm.toString());
        return n.toString();
    }

    public static Pareamento deJson(String json) throws IOException {
        JsonNode n = JSON.readTree(json);
        try {
            String backend = texto(n, "backendUrl");
            String pareadoEm = texto(n, "pareadoEm");
            if (backend == null || pareadoEm == null) {
                throw new IOException("pareamento gravado sem backendUrl/pareadoEm");
            }
            return daResposta(n, backend, Instant.parse(pareadoEm));
        } catch (PareamentoException | RuntimeException e) {
            throw new IOException("pareamento gravado ilegível: " + e.getMessage(), e);
        }
    }
}

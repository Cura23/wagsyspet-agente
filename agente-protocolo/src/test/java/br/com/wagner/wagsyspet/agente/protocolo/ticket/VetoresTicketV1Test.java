package br.com.wagner.wagsyspet.agente.protocolo.ticket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * VETORES DE TESTE do ticket v1 — o contrato entre os dois repos.
 *
 * <p>O arquivo {@code src/test/resources/vetores-ticket-v1.json} é COMMITADO e copiado para o backend na F1. Cada lado
 * roda os mesmos casos contra a própria cópia do código; se alguém mudar formato, campos ou ordem de checagem sem
 * regenerar o arquivo, o outro lado fica vermelho. Para regenerar (mudança INTENCIONAL de contrato):
 * {@code ./mvnw -pl agente-protocolo test -Dtest=VetoresTicketV1Test -Dvetores.gerar=src/test/resources/vetores-ticket-v1.json}</p>
 *
 * <p>A chave privada no arquivo é SÓ de teste (gerada para os vetores) — nunca a de produção.</p>
 */
@DisplayName("Vetores de teste do ticket v1 (contrato backend ↔ agente)")
class VetoresTicketV1Test {

    static final String RECURSO = "/vetores-ticket-v1.json";
    static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    record Vetores(PublicKey publica, PrivateKey privada, Instant agora, long lojaId, String agenteId,
                   Duration tolerancia, JsonNode casos) {
    }

    static Vetores carregar() throws Exception {
        try (InputStream in = VetoresTicketV1Test.class.getResourceAsStream(RECURSO)) {
            assertThat(in).as("arquivo de vetores %s presente no classpath de teste", RECURSO).isNotNull();
            JsonNode raiz = JSON.readTree(in);
            return new Vetores(
                    ChavesTicket.importarPublica(raiz.get("chavePublica").asText()),
                    ChavesTicket.importarPrivada(raiz.get("chavePrivadaSoTeste").asText()),
                    Instant.parse(raiz.get("agora").asText()),
                    raiz.get("lojaId").asLong(),
                    raiz.get("agenteId").asText(),
                    Duration.ofSeconds(raiz.get("toleranciaSegundos").asLong()),
                    raiz.get("casos"));
        }
    }

    @TestFactory
    @DisplayName("cada caso do arquivo produz exatamente o resultado esperado")
    List<DynamicTest> casosDoArquivo() throws Exception {
        Vetores v = carregar();
        List<DynamicTest> testes = new ArrayList<>();
        for (JsonNode caso : v.casos()) {
            String nome = caso.get("nome").asText();
            String ticket = caso.get("ticket").isNull() ? null : caso.get("ticket").asText();
            String esperado = caso.get("esperado").asText();
            testes.add(DynamicTest.dynamicTest(nome + " → " + esperado, () -> {
                // verificador NOVO por caso: o cache de replay não vaza entre casos (o caso de replay verifica 2× ele mesmo)
                VerificadorTicket verificador = new VerificadorTicket(v.publica(), v.lojaId(), v.agenteId(),
                        Clock.fixed(v.agora(), ZoneOffset.UTC), v.tolerancia());
                if ("OK".equals(esperado)) {
                    TicketClaims c = verificador.verificar(ticket);
                    JsonNode esperadas = caso.get("claims");
                    assertThat(c.lojaId()).isEqualTo(esperadas.get("lojaId").asLong());
                    assertThat(c.agenteId()).isEqualTo(esperadas.get("agenteId").asText());
                    assertThat(c.iat()).isEqualTo(esperadas.get("iat").asLong());
                    assertThat(c.exp()).isEqualTo(esperadas.get("exp").asLong());
                    assertThat(c.jti()).isEqualTo(esperadas.get("jti").asText());
                } else if ("REPETIDO".equals(esperado)) {
                    verificador.verificar(ticket);
                    assertMotivo(verificador, ticket, TicketInvalidoException.Motivo.REPETIDO);
                } else {
                    assertMotivo(verificador, ticket, TicketInvalidoException.Motivo.valueOf(esperado));
                }
            }));
        }
        assertThat(testes).hasSizeGreaterThanOrEqualTo(36);
        return testes;
    }

    @Test
    @DisplayName("PARIDADE do assinador: re-assinar as claims do caso OK com a chave de teste reproduz o ticket byte a byte")
    void assinadorReproduzOVetor() throws Exception {
        Vetores v = carregar();
        AssinadorTicket assinador = new AssinadorTicket(v.privada());
        int conferidos = 0;
        for (JsonNode caso : v.casos()) {
            if (!caso.has("claims") || !"OK".equals(caso.get("esperado").asText())) {
                continue;
            }
            JsonNode c = caso.get("claims");
            TicketClaims claims = new TicketClaims(c.get("lojaId").asLong(), c.get("agenteId").asText(),
                    c.get("iat").asLong(), c.get("exp").asLong(), c.get("jti").asText());
            assertThat(assinador.assinar(claims)).as(caso.get("nome").asText()).isEqualTo(caso.get("ticket").asText());
            conferidos++;
        }
        assertThat(conferidos).isGreaterThanOrEqualTo(1);
    }

    static void assertMotivo(VerificadorTicket verificador, String ticket, TicketInvalidoException.Motivo esperado) {
        assertThatThrownBy(() -> verificador.verificar(ticket))
                .isInstanceOf(TicketInvalidoException.class)
                .extracting(e -> ((TicketInvalidoException) e).motivo())
                .isEqualTo(esperado);
    }

    // ───────────────────────────── gerador (só com -Dvetores.gerar=<caminho>) ─────────────────────────────

    @Test
    @EnabledIfSystemProperty(named = "vetores.gerar", matches = ".+")
    @DisplayName("GERAR vetores (mudança intencional de contrato) e gravar em -Dvetores.gerar")
    void gerar() throws Exception {
        Instant agora = Instant.parse("2026-09-07T12:00:00Z");
        long loja = 42L;
        String agente = "0f6b7d1e-3c2a-4f9e-9a1b-7c8d9e0f1a2b";
        Duration tol = Duration.ofMinutes(2);
        // REUSA a chave do arquivo existente: regenerar só muda os casos alterados (diff revisável); chave nova só no 1º uso
        KeyPair chaves = chaveExistenteOuNova();
        KeyPair impostor = ChavesTicket.gerar();
        AssinadorTicket ass = new AssinadorTicket(chaves.getPrivate());
        long t = agora.getEpochSecond();

        ObjectNode raiz = JSON.createObjectNode();
        raiz.put("formato", "v1.base64url(payloadJson).base64url(Ed25519(ASCII('v1.' + base64url(payloadJson))))");
        raiz.put("descricao", "Vetores do ticket Ed25519 v1 — backend assina, agente verifica. Chave privada É SÓ DE TESTE.");
        ObjectNode protocolo = raiz.putObject("protocolo");
        protocolo.put("base64", "url-safe SEM padding (RFC 4648 §5); assinatura = exatamente 86 chars [A-Za-z0-9_-] (forma canônica única, senão FORMATO)");
        protocolo.put("corpoAssinado", "bytes US-ASCII de 'v1.' + base64url(payload) — a versão está DENTRO do assinado");
        protocolo.put("payloadCanonico", "JSON compacto, campos na ordem lojaId,agenteId,iat,exp,jti (é o que o assinador emite e a paridade exige byte a byte; o verificador aceita qualquer ordem/espaço porque a assinatura cobre os bytes exatos)");
        protocolo.put("payloadEstrito", "objeto JSON único; chave duplicada ou conteúdo após o objeto → FORMATO; agenteId e jti casam [A-Za-z0-9_-]{1,64}; lojaId/iat/exp inteiros (long), iat>0, exp>=iat");
        protocolo.put("ordemChecagens", "forma → versão (v\\d{1,3} desconhecida = VERSAO, outro prefixo = FORMATO) → assinatura → campos → validade plausível (exp-iat <= tetoValidadeSegundos E exp-agora <= tetoValidadeSegundos, senão VALIDADE_ABSURDA) → expiração (exp < agora - toleranciaSegundos = EXPIRADO) → lojaId → agenteId → replay (jti repetido = REPETIDO)");
        protocolo.put("tamanhoMaximoChars", VerificadorTicket.TAMANHO_MAXIMO);
        protocolo.put("tetoValidadeSegundos", VerificadorTicket.TETO_VALIDADE.toSeconds());
        protocolo.put("semanticaEsperado", "OK = verificar() devolve as 'claims' do caso; REPETIDO = o MESMO ticket verificado 2× no MESMO verificador (1ª vez OK, 2ª REPETIDO); demais = motivo da TicketInvalidoException. Cada caso roda num verificador NOVO (cache de replay vazio), relógio fixo em 'agora'");
        raiz.put("chavePublica", ChavesTicket.exportarPublica(chaves.getPublic()));
        raiz.put("chavePrivadaSoTeste", ChavesTicket.exportarPrivada(chaves.getPrivate()));
        raiz.put("agora", agora.toString());
        raiz.put("lojaId", loja);
        raiz.put("agenteId", agente);
        raiz.put("toleranciaSegundos", tol.toSeconds());
        ArrayNode casos = raiz.putArray("casos");

        TicketClaims ok = new TicketClaims(loja, agente, t, t + 600, "AAECAwQFBgcICQoLDA0ODw");
        caso(casos, "valido_10min", ass.assinar(ok), "OK", ok);
        TicketClaims naTolerancia = new TicketClaims(loja, agente, t - 660, t - 60, "EBESExQVFhcYGRobHB0eHw");
        caso(casos, "vencido_60s_dentro_da_tolerancia_120s", ass.assinar(naTolerancia), "OK", naTolerancia);
        caso(casos, "vencido_121s_fora_da_tolerancia", ass.assinar(new TicketClaims(loja, agente, t - 721, t - 121, "ICEiIyQlJicoKSorLC0uLw")), "EXPIRADO", null);
        caso(casos, "outra_loja_43", ass.assinar(new TicketClaims(loja + 1, agente, t, t + 600, "MDEyMzQ1Njc4OTo7PD0-Pw")), "LOJA_DIVERGENTE", null);
        caso(casos, "outro_agente", ass.assinar(new TicketClaims(loja, "outro-caixa", t, t + 600, "QEFCQ0RFRkdISUpLTE1OTw")), "AGENTE_DIVERGENTE", null);
        caso(casos, "assinado_por_impostor", new AssinadorTicket(impostor.getPrivate()).assinar(ok), "ASSINATURA", null);
        String[] p = ass.assinar(ok).split("\\.");
        String adulterado = Base64.getUrlEncoder().withoutPadding().encodeToString(
                new String(Base64.getUrlDecoder().decode(p[1]), StandardCharsets.UTF_8).replace("\"lojaId\":42", "\"lojaId\":43").getBytes(StandardCharsets.UTF_8));
        caso(casos, "payload_adulterado_loja43_assinatura_original", p[0] + "." + adulterado + "." + p[2], "ASSINATURA", null);
        caso(casos, "replay_mesmo_ticket_2x", ass.assinar(new TicketClaims(loja, agente, t, t + 600, "UFFSU1RVVldYWVpbXF1eXw")), "REPETIDO", null);
        caso(casos, "versao_v2", "v2" + ass.assinar(ok).substring(2), "VERSAO", null);
        caso(casos, "sem_jti", ass.assinarBruto("{\"lojaId\":42,\"agenteId\":\"" + agente + "\",\"iat\":" + t + ",\"exp\":" + (t + 600) + "}"), "CAMPO_AUSENTE", null);
        caso(casos, "lojaId_como_string", ass.assinarBruto("{\"lojaId\":\"42\",\"agenteId\":\"" + agente + "\",\"iat\":" + t + ",\"exp\":" + (t + 600) + ",\"jti\":\"x\"}"), "CAMPO_AUSENTE", null);
        caso(casos, "jwt_alg_none", "eyJhbGciOiJub25lIn0.eyJsb2phSWQiOjQyfQ.", "FORMATO", null);
        caso(casos, "vazio", "", "FORMATO", null);
        caso(casos, "nulo", null, "FORMATO", null);
        caso(casos, "so_duas_partes", "v1.abc", "FORMATO", null);
        caso(casos, "assinatura_zeros", "v1." + p[1] + "." + "A".repeat(86), "ASSINATURA", null);
        // ── 2ª rodada (adversarial 2026-09-07): ramos sem vetor + endurecimentos ──
        caso(casos, "exp_em_milissegundos_bug_do_emissor", ass.assinar(new TicketClaims(loja, agente, t, agora.toEpochMilli() + 600_000, "YGFiY2RlZmdoaWprbG1ubw")), "VALIDADE_ABSURDA", null);
        caso(casos, "validade_2h_acima_do_teto_1h", ass.assinar(new TicketClaims(loja, agente, t, t + 7200, "cHFyc3R1dnd4eXp7fH1-fw")), "VALIDADE_ABSURDA", null);
        caso(casos, "exp_2_dias_a_frente_do_relogio_com_iat_forjado", ass.assinar(new TicketClaims(loja, agente, t + 172_800, t + 172_800 + 600, "gIGCg4SFhoeIiYqLjI2Ojw")), "VALIDADE_ABSURDA", null);
        caso(casos, "exp_long_max", ass.assinar(new TicketClaims(loja, agente, 1, Long.MAX_VALUE, "kJGSk5SVlpeYmZqbnJ2enw")), "VALIDADE_ABSURDA", null);
        caso(casos, "exp_antes_de_iat", ass.assinarBruto("{\"lojaId\":42,\"agenteId\":\"" + agente + "\",\"iat\":" + (t + 600) + ",\"exp\":" + t + ",\"jti\":\"oKGio6SlpqeoqaqrrK2urw\"}"), "CAMPO_AUSENTE", null);
        caso(casos, "exp_negativo", ass.assinarBruto("{\"lojaId\":42,\"agenteId\":\"" + agente + "\",\"iat\":" + t + ",\"exp\":-5,\"jti\":\"x\"}"), "CAMPO_AUSENTE", null);
        caso(casos, "exp_float", ass.assinarBruto("{\"lojaId\":42,\"agenteId\":\"" + agente + "\",\"iat\":" + t + ",\"exp\":" + (t + 600) + ".0,\"jti\":\"x\"}"), "CAMPO_AUSENTE", null);
        caso(casos, "agenteId_vazio", ass.assinarBruto("{\"lojaId\":42,\"agenteId\":\"\",\"iat\":" + t + ",\"exp\":" + (t + 600) + ",\"jti\":\"x\"}"), "CAMPO_AUSENTE", null);
        caso(casos, "jti_com_quebra_de_linha_injecao_em_log", ass.assinarBruto("{\"lojaId\":42,\"agenteId\":\"" + agente + "\",\"iat\":" + t + ",\"exp\":" + (t + 600) + ",\"jti\":\"abc\\nFAKE LOG\"}"), "CAMPO_AUSENTE", null);
        caso(casos, "chave_duplicada_lojaId_43_depois_42", ass.assinarBruto("{\"lojaId\":43,\"lojaId\":42,\"agenteId\":\"" + agente + "\",\"iat\":" + t + ",\"exp\":" + (t + 600) + ",\"jti\":\"x\"}"), "FORMATO", null);
        caso(casos, "lixo_apos_o_objeto_json", ass.assinarBruto("{\"lojaId\":42,\"agenteId\":\"" + agente + "\",\"iat\":" + t + ",\"exp\":" + (t + 600) + ",\"jti\":\"x\"} xyz"), "FORMATO", null);
        caso(casos, "payload_nao_json", ass.assinarBruto("nao e json"), "FORMATO", null);
        caso(casos, "payload_array", ass.assinarBruto("[1,2]"), "FORMATO", null);
        caso(casos, "assinatura_com_padding", ass.assinar(ok) + "==", "FORMATO", null);
        caso(casos, "assinatura_84_chars", "v1." + p[1] + "." + p[2].substring(0, 84), "FORMATO", null);
        caso(casos, "prefixo_nao_versao", "abc." + p[1] + "." + p[2], "FORMATO", null);
        caso(casos, "versao_v999", "v999." + p[1] + "." + p[2], "VERSAO", null);
        caso(casos, "versao_v1000_nao_casa_regex", "v1000." + p[1] + "." + p[2], "FORMATO", null);
        caso(casos, "quatro_partes", ass.assinar(ok) + ".extra", "FORMATO", null);
        caso(casos, "payload_vazio", "v1.." + p[2], "FORMATO", null);
        caso(casos, "tamanho_4097_chars", "v1." + "A".repeat(4097 - 3 - 87) + "." + p[2], "FORMATO", null);

        Path destino = Path.of(System.getProperty("vetores.gerar"));
        Files.createDirectories(destino.toAbsolutePath().getParent());
        Files.writeString(destino, JSON.writeValueAsString(raiz) + "\n", StandardCharsets.UTF_8);
        System.out.println("Vetores gravados em " + destino.toAbsolutePath() + " (" + casos.size() + " casos)");
    }

    private static KeyPair chaveExistenteOuNova() throws Exception {
        try (InputStream in = VetoresTicketV1Test.class.getResourceAsStream(RECURSO)) {
            if (in == null) {
                return ChavesTicket.gerar();
            }
            JsonNode raiz = JSON.readTree(in);
            return new KeyPair(ChavesTicket.importarPublica(raiz.get("chavePublica").asText()),
                    ChavesTicket.importarPrivada(raiz.get("chavePrivadaSoTeste").asText()));
        }
    }

    private static void caso(ArrayNode casos, String nome, String ticket, String esperado, TicketClaims claims) {
        ObjectNode c = casos.addObject();
        c.put("nome", nome);
        if (ticket == null) {
            c.putNull("ticket");
        } else {
            c.put("ticket", ticket);
        }
        c.put("esperado", esperado);
        if (claims != null) {
            ObjectNode cl = c.putObject("claims");
            cl.put("lojaId", claims.lojaId());
            cl.put("agenteId", claims.agenteId());
            cl.put("iat", claims.iat());
            cl.put("exp", claims.exp());
            cl.put("jti", claims.jti());
        }
    }
}

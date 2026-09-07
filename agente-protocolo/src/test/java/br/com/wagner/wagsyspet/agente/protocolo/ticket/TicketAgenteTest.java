package br.com.wagner.wagsyspet.agente.protocolo.ticket;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ticket Ed25519 (3ª barreira do agente, plano §2.4): o BACKEND assina {lojaId, agenteId, iat, exp, jti}; o AGENTE
 * verifica com a chave pública recebida no pareamento. Tudo JDK 21 nativo — nenhuma lib de JWT.
 * Relógio fixo nos testes: 2026-09-07T12:00:00Z.
 */
@DisplayName("Ticket Ed25519 — assinador (backend) ↔ verificador (agente)")
class TicketAgenteTest {

    static final Instant AGORA = Instant.parse("2026-09-07T12:00:00Z");
    static final Clock RELOGIO = Clock.fixed(AGORA, ZoneOffset.UTC);
    static final long LOJA = 42L;
    static final String AGENTE = "0f6b7d1e-3c2a-4f9e-9a1b-7c8d9e0f1a2b";
    static final Duration TOLERANCIA = Duration.ofMinutes(2);

    KeyPair chaves;
    AssinadorTicket assinador;
    VerificadorTicket verificador;

    @BeforeEach
    void setUp() {
        chaves = ChavesTicket.gerar();
        assinador = new AssinadorTicket(chaves.getPrivate());
        verificador = new VerificadorTicket(chaves.getPublic(), LOJA, AGENTE, RELOGIO, TOLERANCIA);
    }

    TicketClaims claimsValidos() {
        return TicketClaims.novo(LOJA, AGENTE, AGORA, Duration.ofMinutes(10));
    }

    @Nested
    @DisplayName("caminho feliz")
    class Feliz {
        @Test
        @DisplayName("assinar → verificar devolve as MESMAS claims")
        void roundTrip() throws Exception {
            TicketClaims c = claimsValidos();
            String ticket = assinador.assinar(c);
            TicketClaims lidas = verificador.verificar(ticket);
            assertThat(lidas).isEqualTo(c);
            assertThat(lidas.jti()).isNotBlank();
            assertThat(lidas.exp()).isEqualTo(AGORA.getEpochSecond() + 600);
        }

        @Test
        @DisplayName("formato compacto: 'v1.<payload b64url>.<assinatura b64url>', só ASCII seguro pra JSON/URL")
        void formato() {
            String ticket = assinador.assinar(claimsValidos());
            assertThat(ticket).matches("^v1\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]{86}$"); // Ed25519 = 64 bytes → 86 chars b64url sem padding
        }

        @Test
        @DisplayName("Ed25519 é determinístico: mesmas claims + mesma chave → mesmo ticket (base dos vetores de teste)")
        void deterministico() {
            TicketClaims c = claimsValidos();
            assertThat(assinador.assinar(c)).isEqualTo(new AssinadorTicket(chaves.getPrivate()).assinar(c));
        }

        @Test
        @DisplayName("jti é aleatório: dois tickets pras mesmas claims-base nunca colidem")
        void jtiAleatorio() {
            TicketClaims a = TicketClaims.novo(LOJA, AGENTE, AGORA, Duration.ofMinutes(10));
            TicketClaims b = TicketClaims.novo(LOJA, AGENTE, AGORA, Duration.ofMinutes(10));
            assertThat(a.jti()).isNotEqualTo(b.jti());
            assertThat(Base64.getUrlDecoder().decode(a.jti())).hasSize(16);
        }

        @Test
        @DisplayName("vencido dentro da tolerância de relógio ainda passa; fora dela não")
        void toleranciaDeRelogio() throws Exception {
            // exp 60 s no passado, tolerância 120 s → passa (relógio do caixa pode estar adiantado)
            TicketClaims quaseVencido = new TicketClaims(LOJA, AGENTE, AGORA.getEpochSecond() - 660, AGORA.getEpochSecond() - 60, TicketClaims.novoJti());
            assertThat(verificador.verificar(assinador.assinar(quaseVencido))).isEqualTo(quaseVencido);
            // exp 121 s no passado → EXPIRADO
            TicketClaims vencido = new TicketClaims(LOJA, AGENTE, AGORA.getEpochSecond() - 721, AGORA.getEpochSecond() - 121, TicketClaims.novoJti());
            assertMotivo(assinador.assinar(vencido), TicketInvalidoException.Motivo.EXPIRADO);
        }
    }

    @Nested
    @DisplayName("chaves em texto (viajam no pareamento / env do backend)")
    class Chaves {
        @Test
        @DisplayName("pública exporta/importa (SPKI base64) e ainda verifica")
        void publicaRoundTrip() throws Exception {
            String texto = ChavesTicket.exportarPublica(chaves.getPublic());
            assertThat(texto).matches("^[A-Za-z0-9+/=]+$");
            PublicKey importada = ChavesTicket.importarPublica(texto);
            assertThat(importada.getEncoded()).isEqualTo(chaves.getPublic().getEncoded());
            VerificadorTicket v2 = new VerificadorTicket(importada, LOJA, AGENTE, RELOGIO, TOLERANCIA);
            assertThat(v2.verificar(assinador.assinar(claimsValidos()))).isNotNull();
        }

        @Test
        @DisplayName("privada exporta/importa (PKCS#8 base64) e assina igual")
        void privadaRoundTrip() {
            String texto = ChavesTicket.exportarPrivada(chaves.getPrivate());
            PrivateKey importada = ChavesTicket.importarPrivada(texto);
            TicketClaims c = claimsValidos();
            assertThat(new AssinadorTicket(importada).assinar(c)).isEqualTo(assinador.assinar(c));
        }

        @Test
        @DisplayName("texto que não é chave Ed25519 → IllegalArgumentException clara (não vaza stack de provider)")
        void chaveInvalida() {
            assertThatThrownBy(() -> ChavesTicket.importarPublica("bm90IGEga2V5"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Ed25519");
            assertThatThrownBy(() -> ChavesTicket.importarPublica("###"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("adversarial — tudo que NÃO pode passar")
    class Adversarial {
        @Test
        @DisplayName("assinado com OUTRA chave (backend falso) → ASSINATURA")
        void outraChave() {
            AssinadorTicket impostor = new AssinadorTicket(ChavesTicket.gerar().getPrivate());
            assertMotivo(impostor.assinar(claimsValidos()), TicketInvalidoException.Motivo.ASSINATURA);
        }

        @Test
        @DisplayName("payload alterado depois de assinado (lojaId 42→43, mesma assinatura) → ASSINATURA")
        void payloadAdulterado() {
            String ticket = assinador.assinar(claimsValidos());
            String[] p = ticket.split("\\.");
            String json = new String(Base64.getUrlDecoder().decode(p[1]), StandardCharsets.UTF_8);
            assertThat(json).contains("\"lojaId\":42");
            String adulterado = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(json.replace("\"lojaId\":42", "\"lojaId\":43").getBytes(StandardCharsets.UTF_8));
            assertMotivo(p[0] + "." + adulterado + "." + p[2], TicketInvalidoException.Motivo.ASSINATURA);
        }

        @Test
        @DisplayName("assinatura de OUTRO ticket válido colada neste → ASSINATURA")
        void assinaturaTransplantada() {
            String a = assinador.assinar(claimsValidos());
            String b = assinador.assinar(claimsValidos());
            String[] pa = a.split("\\."), pb = b.split("\\.");
            assertMotivo(pa[0] + "." + pa[1] + "." + pb[2], TicketInvalidoException.Motivo.ASSINATURA);
        }

        @Test
        @DisplayName("ticket de OUTRA loja (assinado pelo backend verdadeiro) → LOJA_DIVERGENTE")
        void outraLoja() {
            assertMotivo(assinador.assinar(TicketClaims.novo(LOJA + 1, AGENTE, AGORA, Duration.ofMinutes(10))),
                    TicketInvalidoException.Motivo.LOJA_DIVERGENTE);
        }

        @Test
        @DisplayName("ticket pra OUTRO agente da mesma loja (outro caixa) → AGENTE_DIVERGENTE")
        void outroAgente() {
            assertMotivo(assinador.assinar(TicketClaims.novo(LOJA, "outro-caixa", AGORA, Duration.ofMinutes(10))),
                    TicketInvalidoException.Motivo.AGENTE_DIVERGENTE);
        }

        @Test
        @DisplayName("replay: o MESMO ticket apresentado 2× → 2ª vez REPETIDO")
        void replay() throws Exception {
            String ticket = assinador.assinar(claimsValidos());
            verificador.verificar(ticket);
            assertMotivo(ticket, TicketInvalidoException.Motivo.REPETIDO);
        }

        @Test
        @DisplayName("cache de replay não cresce pra sempre: jti vencidos são esquecidos")
        void cacheDeReplayPurga() throws Exception {
            for (int i = 0; i < 50; i++) {
                verificador.verificar(assinador.assinar(claimsValidos()));
            }
            assertThat(verificador.jtisLembrados()).isEqualTo(50);
            // relógio avança além de exp + tolerância → purga
            VerificadorTicket depois = verificador.comRelogio(Clock.fixed(AGORA.plus(Duration.ofMinutes(13)), ZoneOffset.UTC));
            assertThatThrownBy(() -> depois.verificar(assinador.assinar(claimsValidos())))
                    .isInstanceOf(TicketInvalidoException.class); // vencido no novo relógio — só pra disparar a purga
            assertThat(depois.jtisLembrados()).isZero();
        }

        @Test
        @DisplayName("versão desconhecida ('v2.') → VERSAO, mesmo com assinatura válida do payload")
        void versaoDesconhecida() {
            String ticket = assinador.assinar(claimsValidos());
            assertMotivo("v2" + ticket.substring(2), TicketInvalidoException.Motivo.VERSAO);
        }

        @ParameterizedTest(name = "lixo: {0}")
        @ValueSource(strings = {
            "",
            "v1",
            "v1..",
            "v1.abc",
            "v1.abc.def.ghi",
            "v1.###.###",
            "eyJhbGciOiJub25lIn0.eyJsb2phSWQiOjQyfQ.",              // JWT alg=none
            "v1.bm90IGpzb24.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
        })
        void lixoNaoDerruba(String lixo) {
            assertThatThrownBy(() -> verificador.verificar(lixo))
                    .isInstanceOf(TicketInvalidoException.class)
                    .extracting(e -> ((TicketInvalidoException) e).motivo())
                    .isIn(TicketInvalidoException.Motivo.FORMATO, TicketInvalidoException.Motivo.VERSAO,
                          TicketInvalidoException.Motivo.ASSINATURA);
        }

        @Test
        @DisplayName("ticket gigante (1 MB) → FORMATO antes de qualquer decode")
        void gigante() {
            assertMotivo("v1." + "A".repeat(1024 * 1024) + ".B", TicketInvalidoException.Motivo.FORMATO);
        }

        @Test
        @DisplayName("payload assinado mas sem campo obrigatório → CAMPO_AUSENTE (nunca NPE)")
        void campoAusente() {
            String json = "{\"lojaId\":42,\"agenteId\":\"" + AGENTE + "\",\"exp\":" + (AGORA.getEpochSecond() + 600) + "}"; // sem jti/iat
            assertMotivo(assinador.assinarBruto(json), TicketInvalidoException.Motivo.CAMPO_AUSENTE);
        }

        @Test
        @DisplayName("payload assinado com tipo errado (lojaId string) → CAMPO_AUSENTE/FORMATO, nunca ClassCast")
        void tipoErrado() {
            String json = "{\"lojaId\":\"42\",\"agenteId\":\"" + AGENTE + "\",\"iat\":1,\"exp\":" + (AGORA.getEpochSecond() + 600) + ",\"jti\":\"x\"}";
            assertThatThrownBy(() -> verificador.verificar(assinador.assinarBruto(json)))
                    .isInstanceOf(TicketInvalidoException.class);
        }

        @Test
        @DisplayName("null → FORMATO")
        void nulo() {
            assertMotivo(null, TicketInvalidoException.Motivo.FORMATO);
        }

        // ── adversarial 2ª rodada (workflow 2026-09-07) ──

        @Test
        @DisplayName("exp em MILISSEGUNDOS por bug do backend (ticket de 58 mil anos) → VALIDADE_ABSURDA, não aceito")
        void expEmMilissegundos() {
            long t = AGORA.getEpochSecond();
            assertMotivo(assinador.assinar(new TicketClaims(LOJA, AGENTE, t, AGORA.toEpochMilli() + 600_000, TicketClaims.novoJti())),
                    TicketInvalidoException.Motivo.VALIDADE_ABSURDA);
        }

        @Test
        @DisplayName("validade acima do teto (exp − iat > 1 h) → VALIDADE_ABSURDA mesmo com relógio do caixa errado")
        void validadeAcimaDoTeto() {
            long t = AGORA.getEpochSecond();
            assertMotivo(assinador.assinar(new TicketClaims(LOJA, AGENTE, t, t + 3601, TicketClaims.novoJti())),
                    TicketInvalidoException.Motivo.VALIDADE_ABSURDA);
            assertMotivo(assinador.assinar(new TicketClaims(LOJA, AGENTE, t, t + 100L * 365 * 24 * 3600, TicketClaims.novoJti())),
                    TicketInvalidoException.Motivo.VALIDADE_ABSURDA);
        }

        @Test
        @DisplayName("exp = Long.MAX_VALUE → VALIDADE_ABSURDA (sem overflow disfarçado de EXPIRADO)")
        void expLongMax() {
            assertMotivo(assinador.assinar(new TicketClaims(LOJA, AGENTE, 1, Long.MAX_VALUE, TicketClaims.novoJti())),
                    TicketInvalidoException.Motivo.VALIDADE_ABSURDA);
        }

        @Test
        @DisplayName("exp bem no futuro em relação ao relógio do caixa (iat 'certo' mas exp > agora + teto) → VALIDADE_ABSURDA")
        void expMuitoNoFuturoDoRelogio() {
            long t = AGORA.getEpochSecond();
            // iat forjado junto do exp pra passar no teto exp−iat; ainda assim está 2 dias à frente do relógio do agente
            assertMotivo(assinador.assinar(new TicketClaims(LOJA, AGENTE, t + 172_800, t + 172_800 + 600, TicketClaims.novoJti())),
                    TicketInvalidoException.Motivo.VALIDADE_ABSURDA);
        }

        @Test
        @DisplayName("JSON com chave DUPLICADA (lojaId 43 depois 42) → FORMATO (parser estrito, nunca 'último vence')")
        void chaveDuplicada() {
            String json = "{\"lojaId\":43,\"lojaId\":42,\"agenteId\":\"" + AGENTE + "\",\"iat\":1,\"exp\":" + (AGORA.getEpochSecond() + 600) + ",\"jti\":\"abc\"}";
            assertMotivo(assinador.assinarBruto(json), TicketInvalidoException.Motivo.FORMATO);
        }

        @Test
        @DisplayName("lixo depois do objeto JSON (2º objeto, texto) → FORMATO")
        void lixoAposJson() {
            String base = "{\"lojaId\":42,\"agenteId\":\"" + AGENTE + "\",\"iat\":1,\"exp\":" + (AGORA.getEpochSecond() + 600) + ",\"jti\":\"abc\"}";
            assertMotivo(assinador.assinarBruto(base + " xyz"), TicketInvalidoException.Motivo.FORMATO);
            assertMotivo(assinador.assinarBruto(base + base), TicketInvalidoException.Motivo.FORMATO);
        }

        @Test
        @DisplayName("jti/agenteId com caracteres fora de [A-Za-z0-9_-] (quebra de linha → injeção em log) → CAMPO_AUSENTE")
        void identificadoresComLixo() {
            long t = AGORA.getEpochSecond();
            String jsonJti = "{\"lojaId\":42,\"agenteId\":\"" + AGENTE + "\",\"iat\":" + t + ",\"exp\":" + (t + 600) + ",\"jti\":\"abc\\nFAKE LOG\"}";
            assertMotivo(assinador.assinarBruto(jsonJti), TicketInvalidoException.Motivo.CAMPO_AUSENTE);
            String jsonAgente = "{\"lojaId\":42,\"agenteId\":\"caixa 1\",\"iat\":" + t + ",\"exp\":" + (t + 600) + ",\"jti\":\"abc\"}";
            assertMotivo(assinador.assinarBruto(jsonAgente), TicketInvalidoException.Motivo.CAMPO_AUSENTE);
        }

        @Test
        @DisplayName("assinatura fora da forma canônica (com padding '=', base64 padrão com '+'/'/') → FORMATO")
        void assinaturaNaoCanonica() {
            String ticket = assinador.assinar(claimsValidos());
            String[] p = ticket.split("\\.");
            assertMotivo(p[0] + "." + p[1] + "." + p[2] + "==", TicketInvalidoException.Motivo.FORMATO);
            String padrao = Base64.getEncoder().encodeToString(Base64.getUrlDecoder().decode(p[2]));
            assertMotivo(p[0] + "." + p[1] + "." + padrao, TicketInvalidoException.Motivo.FORMATO);
        }
    }

    @Nested
    @DisplayName("fail-fast em chave pública inválida (arquivo de pareamento corrompido)")
    class ChaveInvalida {
        /** SPKI Ed25519 sintaticamente válido cujo ponto (y = 0xFF…FF) não existe: KeyFactory aceita, initVerify não. */
        static final String SPKI_PONTO_INVALIDO = Base64.getEncoder().encodeToString(hex("302a300506032b6570032100" + "ff".repeat(32)));

        @Test
        @DisplayName("importarPublica recusa ponto inválido com IllegalArgumentException clara")
        void importarRecusaPontoInvalido() {
            assertThatThrownBy(() -> ChavesTicket.importarPublica(SPKI_PONTO_INVALIDO))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Ed25519");
        }

        @Test
        @DisplayName("construtor do verificador recusa chave de OUTRO algoritmo (X25519) na hora, não no 1º ticket")
        void construtorRecusaOutroAlgoritmo() throws Exception {
            PublicKey x25519 = java.security.KeyPairGenerator.getInstance("X25519").generateKeyPair().getPublic();
            assertThatThrownBy(() -> new VerificadorTicket(x25519, LOJA, AGENTE, RELOGIO, TOLERANCIA))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Ed25519");
        }

        static byte[] hex(String h) {
            byte[] b = new byte[h.length() / 2];
            for (int i = 0; i < b.length; i++) {
                b[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
            }
            return b;
        }
    }

    void assertMotivo(String ticket, TicketInvalidoException.Motivo esperado) {
        assertThatThrownBy(() -> verificador.verificar(ticket))
                .isInstanceOf(TicketInvalidoException.class)
                .extracting(e -> ((TicketInvalidoException) e).motivo())
                .isEqualTo(esperado);
    }
}

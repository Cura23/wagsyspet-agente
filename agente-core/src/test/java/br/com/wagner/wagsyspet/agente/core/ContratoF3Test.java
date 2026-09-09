package br.com.wagner.wagsyspet.agente.core;

import br.com.wagner.wagsyspet.agente.protocolo.ticket.AssinadorTicket;
import br.com.wagner.wagsyspet.agente.protocolo.ticket.ChavesTicket;
import br.com.wagner.wagsyspet.agente.protocolo.ticket.TicketClaims;
import br.com.wagner.wagsyspet.agente.protocolo.ticket.VerificadorTicket;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F3-L1 — CONTRATO v1 do protocolo PWA ↔ agente (plano canônico §7.4), caso a caso, com sockets REAIS e tickets
 * cunhados pelo {@link AssinadorTicket} (o mesmo do backend). É o espelho em Java do {@code roteiroF3} do
 * {@code FakeSocket.ts} do frontend: o que o PWA espera do agente, o agente prova aqui.
 *
 * <p>Regras que o PWA impõe e que estes testes fixam: tipos JSON exatos ({@code protocolo} número, o resto string),
 * {@code id} ecoado em toda resposta correlacionada, frame {@code erro} ANTES do {@code close 1008}, reason exata
 * {@code OCUPADO}, e nenhuma resposta bloqueante (o motor fica em executor próprio).</p>
 */
@DisplayName("ContratoF3 — protocolo v1 completo (hello/auth/listar/selecionar/imprimir/OCUPADO/prazos)")
class ContratoF3Test {

    private static final String ORIGIN = "https://app.agroease.com.br";
    private static final String OUTRA_ORIGIN = "https://wagsyspet-frontend.vercel.app";
    private static final long LOJA = 7L;
    private static final String AGENTE_ID = "3f1c2b6e-0f1a-4a2b-9c3d-000000000001";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final byte[] PDF = "%PDF-1.4\n%cupom de teste\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);

    private KeyPair chaves;
    private AssinadorTicket assinador;
    private PortaImpressaoFake impressao;
    private ConfiguracaoLocalMemoria config;
    private ServidorAgente servidor;
    private ServidorAgente.Prazos prazos;

    @BeforeEach
    void subir() throws Exception {
        chaves = ChavesTicket.gerar();
        assinador = new AssinadorTicket(chaves.getPrivate());
        impressao = new PortaImpressaoFake("EPSON TM-T20", "PDF");
        config = new ConfiguracaoLocalMemoria();
        config.impressoraSelecionada("EPSON TM-T20");
        prazos = new ServidorAgente.Prazos();
        prazos.auth = Duration.ofSeconds(90);
        prazos.impressao = Duration.ofMillis(1500);
        prazos.ociosoAutenticado = Duration.ofMinutes(2);
        servidor = novoServidor();
        servidor.iniciar(Duration.ofSeconds(10));
    }

    private ServidorAgente novoServidor() {
        VerificadorTicket verificador = new VerificadorTicket(chaves.getPublic(), LOJA, AGENTE_ID, Clock.systemUTC(), Duration.ofMinutes(5));
        InfoAgente info = new InfoAgente("1.0.0-teste", 1, AGENTE_ID);
        return new ServidorAgente(0, Set.of(ORIGIN, OUTRA_ORIGIN),
                new ServidorAgente.Dependencias(info, verificador, impressao, config), prazos);
    }

    @AfterEach
    void derrubar() throws Exception {
        servidor.stop(1000);
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────────────────────────

    private String ticketValido() {
        return assinador.assinar(TicketClaims.novo(LOJA, AGENTE_ID, Instant.now(), Duration.ofMinutes(10)));
    }

    private static String json(Object... kv) {
        ObjectNode n = JSON.createObjectNode();
        for (int i = 0; i < kv.length; i += 2) {
            Object v = kv[i + 1];
            if (v == null) n.putNull((String) kv[i]);
            else if (v instanceof Integer x) n.put((String) kv[i], x);
            else n.put((String) kv[i], String.valueOf(v));
        }
        return n.toString();
    }

    private ClienteTeste conectarEAutenticar(String origin) throws Exception {
        ClienteTeste c = ClienteTeste.conectarAberto(servidor.getPort(), origin);
        c.send(json("tipo", "hello", "versaoProtocolo", 1));
        assertThat(c.proximaMensagem().get("tipo").asText()).isEqualTo("hello_ok");
        c.send(json("tipo", "auth", "ticket", ticketValido()));
        assertThat(c.proximaMensagem().get("tipo").asText()).isEqualTo("auth_ok");
        return c;
    }

    private static String base64(byte[] b) {
        return Base64.getEncoder().encodeToString(b); // mesmo alfabeto/padding do btoa() do navegador
    }

    // ── 1. hello ──────────────────────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("1. hello (pré-auth)")
    class Hello {
        @Test
        @DisplayName("hello_ok com os 4 campos nos TIPOS que o parser do PWA exige; responde mesmo a versaoProtocolo desconhecido")
        void helloOkTipado() throws Exception {
            ClienteTeste c = ClienteTeste.conectarAberto(servidor.getPort(), ORIGIN);
            c.send(json("tipo", "hello", "versaoProtocolo", 99));
            JsonNode r = c.proximaMensagem();
            assertThat(r.get("tipo").asText()).isEqualTo("hello_ok");
            assertThat(r.get("agenteVersao").isTextual()).isTrue();
            assertThat(r.get("protocolo").isInt()).as("protocolo NÚMERO").isTrue();
            assertThat(r.get("so").isTextual()).isTrue();
            assertThat(r.get("agenteId").asText()).isEqualTo(AGENTE_ID);
            c.close();
        }
    }

    // ── 2. gate pré-auth ──────────────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("2. antes do auth só hello/ping/auth")
    class GatePreAuth {
        @Test
        @DisplayName("imprimir sem auth → erro{NAO_AUTENTICADO, id} e DEPOIS close 1008 'NAO_AUTENTICADO' (roteiroF3)")
        void semAuthRecusaEFecha() throws Exception {
            ClienteTeste c = ClienteTeste.conectarAberto(servidor.getPort(), ORIGIN);
            c.send(json("tipo", "imprimir", "id", "j1", "formato", "pdf", "bytesBase64", base64(PDF), "impressora", "PDF"));
            JsonNode r = c.proximaMensagem();
            assertThat(r.get("tipo").asText()).isEqualTo("erro");
            assertThat(r.get("codigo").asText()).isEqualTo("NAO_AUTENTICADO");
            assertThat(r.get("id").asText()).isEqualTo("j1");
            assertThat(c.esperarFechar(5)).isTrue();
            assertThat(c.codigoFechamento.get()).isEqualTo(1008);
            assertThat(c.motivoFechamento.get()).isEqualTo("NAO_AUTENTICADO");
            assertThat(impressao.jobs).isEmpty();
        }

        @Test
        @DisplayName("listar_impressoras sem auth → NAO_AUTENTICADO (nenhuma informação da máquina vaza antes do ticket)")
        void listarSemAuth() throws Exception {
            ClienteTeste c = ClienteTeste.conectarAberto(servidor.getPort(), ORIGIN);
            c.send(json("tipo", "listar_impressoras", "id", "l1"));
            JsonNode r = c.proximaMensagem();
            assertThat(r.get("codigo").asText()).isEqualTo("NAO_AUTENTICADO");
            assertThat(c.esperarFechar(5)).isTrue();
            assertThat(c.codigoFechamento.get()).isEqualTo(1008);
        }
    }

    // ── 3/4. auth ─────────────────────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("3/4. auth com ticket Ed25519")
    class Auth {
        @Test
        @DisplayName("ticket válido → auth_ok{}; depois listar_impressoras{id} → impressoras{id, nomes[], selecionada}")
        void authOkEListar() throws Exception {
            ClienteTeste c = conectarEAutenticar(ORIGIN);
            c.send(json("tipo", "listar_impressoras", "id", "l-1"));
            JsonNode r = c.proximaMensagem();
            assertThat(r.get("tipo").asText()).isEqualTo("impressoras");
            assertThat(r.get("id").asText()).isEqualTo("l-1");
            assertThat(r.get("nomes").isArray()).isTrue();
            assertThat(r.get("nomes")).extracting(JsonNode::asText).containsExactly("EPSON TM-T20", "PDF");
            assertThat(r.get("selecionada").asText()).isEqualTo("EPSON TM-T20");
            c.close();
        }

        @Test
        @DisplayName("selecionada persistida que NÃO está mais na lista → selecionada: null (nunca um nome que vai falhar)")
        void selecionadaForaDaListaViraNull() throws Exception {
            config.impressoraSelecionada("REMOVIDA");
            ClienteTeste c = conectarEAutenticar(ORIGIN);
            c.send(json("tipo", "listar_impressoras", "id", "l-2"));
            JsonNode r = c.proximaMensagem();
            assertThat(r.get("selecionada").isNull()).isTrue();
            c.close();
        }

        @Test
        @DisplayName("lista de impressoras VAZIA (spooler fora) mantém a selecionada — o PWA confia nela e o motor devolve a mensagem certa (adversarial A2)")
        void listaVaziaMantemSelecionada() throws Exception {
            servidor.stop(1000);
            impressao = new PortaImpressaoFake(); // nenhuma impressora enumerada
            config.impressoraSelecionada("EPSON TM-T20");
            servidor = novoServidor();
            servidor.iniciar(Duration.ofSeconds(10));
            ClienteTeste c = conectarEAutenticar(ORIGIN);
            c.send(json("tipo", "listar_impressoras", "id", "l-3"));
            JsonNode r = c.proximaMensagem();
            assertThat(r.get("nomes")).isEmpty();
            assertThat(r.get("selecionada").asText()).isEqualTo("EPSON TM-T20");
            c.close();
        }

        @Test
        @DisplayName("hello e auth REPETIDOS são idempotentes: hello_ok de novo; 2º auth → auth_ok sem gastar outro ticket")
        void helloEAuthRepetidos() throws Exception {
            ClienteTeste c = conectarEAutenticar(ORIGIN);
            c.send(json("tipo", "hello", "versaoProtocolo", 1));
            assertThat(c.proximaMensagem().get("tipo").asText()).isEqualTo("hello_ok");
            String outro = ticketValido();
            c.send(json("tipo", "auth", "ticket", outro));
            assertThat(c.proximaMensagem().get("tipo").asText()).isEqualTo("auth_ok");
            c.close();
            assertThat(c.esperarFechar(5)).isTrue();
            // o 2º ticket NÃO foi consumido: serve numa conexão nova
            ClienteTeste d = ClienteTeste.conectarAberto(servidor.getPort(), ORIGIN);
            d.send(json("tipo", "auth", "ticket", outro));
            assertThat(d.proximaMensagem().get("tipo").asText()).isEqualTo("auth_ok");
            d.close();
        }

        @Test
        @DisplayName("ticket EXPIRADO → erro{TICKET_INVALIDO, motivo:'EXPIRADO'} ANTES do close 1008 'TICKET_INVALIDO' (o PWA renova e reconecta 1×)")
        void expirado() throws Exception {
            String velho = assinador.assinar(TicketClaims.novo(LOJA, AGENTE_ID, Instant.now().minus(Duration.ofMinutes(30)), Duration.ofMinutes(10)));
            assertRecusa(velho, "EXPIRADO");
        }

        @Test
        @DisplayName("mesmo jti duas vezes → 2ª conexão recebe REPETIDO")
        void repetido() throws Exception {
            String t = ticketValido();
            ClienteTeste c1 = ClienteTeste.conectarAberto(servidor.getPort(), ORIGIN);
            c1.send(json("tipo", "auth", "ticket", t));
            assertThat(c1.proximaMensagem().get("tipo").asText()).isEqualTo("auth_ok");
            c1.close();
            assertThat(c1.esperarFechar(5)).isTrue();
            assertRecusa(t, "REPETIDO");
        }

        @Test
        @DisplayName("ticket de OUTRA loja → LOJA_DIVERGENTE; de outro caixa → AGENTE_DIVERGENTE; assinado por outra chave → ASSINATURA")
        void divergentes() throws Exception {
            assertRecusa(assinador.assinar(TicketClaims.novo(99L, AGENTE_ID, Instant.now(), Duration.ofMinutes(10))), "LOJA_DIVERGENTE");
            assertRecusa(assinador.assinar(TicketClaims.novo(LOJA, "outro-caixa", Instant.now(), Duration.ofMinutes(10))), "AGENTE_DIVERGENTE");
            AssinadorTicket impostor = new AssinadorTicket(ChavesTicket.gerar().getPrivate());
            assertRecusa(impostor.assinar(TicketClaims.novo(LOJA, AGENTE_ID, Instant.now(), Duration.ofMinutes(10))), "ASSINATURA");
            assertRecusa("lixo", "FORMATO");
        }

        @Test
        @DisplayName("auth sem campo ticket → TICKET_INVALIDO/FORMATO (nunca NPE, nunca silêncio)")
        void authSemTicket() throws Exception {
            ClienteTeste c = ClienteTeste.conectarAberto(servidor.getPort(), ORIGIN);
            c.send(json("tipo", "auth"));
            JsonNode r = c.proximaMensagem();
            assertThat(r.get("codigo").asText()).isEqualTo("TICKET_INVALIDO");
            assertThat(r.get("motivo").asText()).isEqualTo("FORMATO");
            assertThat(c.esperarFechar(5)).isTrue();
        }

        private void assertRecusa(String ticket, String motivo) throws Exception {
            ClienteTeste c = ClienteTeste.conectarAberto(servidor.getPort(), ORIGIN);
            c.send(json("tipo", "auth", "ticket", ticket));
            JsonNode r = c.proximaMensagem();
            assertThat(r.get("tipo").asText()).isEqualTo("erro");
            assertThat(r.get("codigo").asText()).isEqualTo("TICKET_INVALIDO");
            assertThat(r.get("motivo").asText()).isEqualTo(motivo);
            assertThat(r.get("mensagem").asText()).isNotBlank();
            // relógio errado no caixa é a causa real de EXPIRADO/VALIDADE_ABSURDA: a mensagem carrega a dica (L3-A2); assinatura, não
            boolean temporal = motivo.equals("EXPIRADO") || motivo.equals("VALIDADE_ABSURDA");
            assertThat(r.get("mensagem").asText().contains("data/hora")).as("dica de relógio só nos motivos temporais").isEqualTo(temporal);
            assertThat(c.esperarFechar(5)).as("close depois do frame erro").isTrue();
            assertThat(c.codigoFechamento.get()).isEqualTo(1008);
            assertThat(c.motivoFechamento.get()).isEqualTo("TICKET_INVALIDO");
        }
    }

    // ── 5. OCUPADO ────────────────────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("5. uma conexão autenticada por Origin")
    class Ocupado {
        @Test
        @DisplayName("2ª conexão da MESMA Origin com a 1ª viva → erro{OCUPADO} + close 1008 'OCUPADO', ticket NÃO consumido (autentica depois que a 1ª fecha)")
        void segundaAbaOcupado() throws Exception {
            ClienteTeste primeira = conectarEAutenticar(ORIGIN);

            String t = ticketValido();
            ClienteTeste segunda = ClienteTeste.conectarAberto(servidor.getPort(), ORIGIN);
            segunda.send(json("tipo", "auth", "ticket", t));
            JsonNode r = segunda.proximaMensagem();
            assertThat(r.get("tipo").asText()).isEqualTo("erro");
            assertThat(r.get("codigo").asText()).isEqualTo("OCUPADO");
            assertThat(segunda.esperarFechar(5)).isTrue();
            assertThat(segunda.codigoFechamento.get()).isEqualTo(1008);
            assertThat(segunda.motivoFechamento.get()).as("o PWA só reconhece a reason EXATA").isEqualTo("OCUPADO");

            // a 1ª fecha → a vaga libera → o MESMO ticket ainda serve (não foi gasto na recusa)
            primeira.close();
            assertThat(primeira.esperarFechar(5)).isTrue();
            ClienteTeste terceira = ClienteTeste.conectarAberto(servidor.getPort(), ORIGIN);
            terceira.send(json("tipo", "auth", "ticket", t));
            assertThat(terceira.proximaMensagem(10).get("tipo").asText()).isEqualTo("auth_ok");
            terceira.close();
        }

        @Test
        @DisplayName("duas abas autenticando AO MESMO TEMPO: uma ganha, a outra recebe OCUPADO e o ticket dela NÃO foi gasto (adversarial A4)")
        void disputaSimultaneaNaoGastaTicket() throws Exception {
            String t1 = ticketValido();
            String t2 = ticketValido();
            ClienteTeste a = ClienteTeste.conectarAberto(servidor.getPort(), ORIGIN);
            ClienteTeste b = ClienteTeste.conectarAberto(servidor.getPort(), ORIGIN);
            CountDownLatch largada = new CountDownLatch(1);
            Runnable ra = () -> { try { largada.await(); a.send(json("tipo", "auth", "ticket", t1)); } catch (InterruptedException ignored) { } };
            Runnable rb = () -> { try { largada.await(); b.send(json("tipo", "auth", "ticket", t2)); } catch (InterruptedException ignored) { } };
            Thread ta = new Thread(ra); Thread tb = new Thread(rb);
            ta.start(); tb.start(); largada.countDown(); ta.join(); tb.join();
            JsonNode ra1 = a.proximaMensagem();
            JsonNode rb1 = b.proximaMensagem();
            String tipos = ra1.get("tipo").asText() + "/" + rb1.get("tipo").asText();
            assertThat(tipos).as("exatamente uma autentica").isIn("auth_ok/erro", "erro/auth_ok");
            ClienteTeste perdedora = ra1.get("tipo").asText().equals("erro") ? a : b;
            ClienteTeste vencedora = perdedora == a ? b : a;
            String ticketPerdedora = perdedora == a ? t1 : t2;
            JsonNode erro = perdedora == a ? ra1 : rb1;
            assertThat(erro.get("codigo").asText()).isEqualTo("OCUPADO");
            assertThat(perdedora.esperarFechar(5)).isTrue();
            vencedora.close();
            assertThat(vencedora.esperarFechar(5)).isTrue();
            // o ticket da perdedora ainda vale (não foi consumido na disputa)
            ClienteTeste c = ClienteTeste.conectarAberto(servidor.getPort(), ORIGIN);
            c.send(json("tipo", "auth", "ticket", ticketPerdedora));
            assertThat(c.proximaMensagem(10).get("tipo").asText()).isEqualTo("auth_ok");
            c.close();
        }

        @Test
        @DisplayName("Origin DIFERENTE não disputa a vaga (Vercel e app.agroease.com.br convivem)")
        void outraOriginAutentica() throws Exception {
            ClienteTeste a = conectarEAutenticar(ORIGIN);
            ClienteTeste b = conectarEAutenticar(OUTRA_ORIGIN);
            a.close();
            b.close();
        }
    }

    // ── 6. prazo do auth ──────────────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("6. prazo do auth")
    class PrazoAuth {
        @Test
        @DisplayName("sem auth dentro do prazo → close 1008 'AUTH_TIMEOUT'; hello sozinho não segura a conexão")
        void semAuthFechaNoPrazo() throws Exception {
            servidor.stop(1000);
            prazos.auth = Duration.ofMillis(700);
            servidor = novoServidor();
            servidor.iniciar(Duration.ofSeconds(10));

            ClienteTeste c = ClienteTeste.conectarAberto(servidor.getPort(), ORIGIN);
            c.send(json("tipo", "hello", "versaoProtocolo", 1));
            assertThat(c.proximaMensagem().get("tipo").asText()).isEqualTo("hello_ok");
            assertThat(c.esperarFechar(5)).isTrue();
            assertThat(c.codigoFechamento.get()).isEqualTo(1008);
            assertThat(c.motivoFechamento.get()).isEqualTo("AUTH_TIMEOUT");
        }

        @Test
        @DisplayName("autenticada dentro do prazo NÃO é derrubada pelo timer")
        void autenticadaSobrevive() throws Exception {
            servidor.stop(1000);
            prazos.auth = Duration.ofMillis(700);
            servidor = novoServidor();
            servidor.iniciar(Duration.ofSeconds(10));

            ClienteTeste c = conectarEAutenticar(ORIGIN);
            assertThat(c.esperarFechar(2)).as("não pode fechar").isFalse();
            c.send(json("tipo", "ping"));
            assertThat(c.proximaMensagem().get("tipo").asText()).isEqualTo("pong");
            c.close();
        }
    }

    // ── 7. selecionar_impressora ──────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("7. selecionar_impressora")
    class Selecionar {
        @Test
        @DisplayName("nome da lista → selecionar_impressora_ok{id, selecionada} e fica persistido; nome fora da lista → erro{id, IMPRESSORA_INDISPONIVEL}")
        void selecionar() throws Exception {
            ClienteTeste c = conectarEAutenticar(ORIGIN);
            c.send(json("tipo", "selecionar_impressora", "id", "s1", "nome", "PDF"));
            JsonNode ok = c.proximaMensagem();
            assertThat(ok.get("tipo").asText()).isEqualTo("selecionar_impressora_ok");
            assertThat(ok.get("id").asText()).isEqualTo("s1");
            assertThat(ok.get("selecionada").asText()).isEqualTo("PDF");
            assertThat(config.impressoraSelecionada()).contains("PDF");

            c.send(json("tipo", "selecionar_impressora", "id", "s2", "nome", "NAO-EXISTE"));
            JsonNode erro = c.proximaMensagem();
            assertThat(erro.get("tipo").asText()).isEqualTo("erro");
            assertThat(erro.get("id").asText()).isEqualTo("s2");
            assertThat(erro.get("codigo").asText()).isEqualTo("IMPRESSORA_INDISPONIVEL");
            assertThat(config.impressoraSelecionada()).as("não troca para um nome inválido").contains("PDF");
            c.close();
        }
    }

    // ── 8/9. imprimir ─────────────────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("8/9. imprimir")
    class Imprimir {
        @Test
        @DisplayName("feliz: bytes chegam iguais ao PDF, na impressora pedida → imprimir_ok{id, estado:'ACEITO_SPOOLER'}")
        void feliz() throws Exception {
            ClienteTeste c = conectarEAutenticar(ORIGIN);
            c.send(json("tipo", "imprimir", "id", "j-1", "formato", "pdf", "bytesBase64", base64(PDF), "impressora", "PDF"));
            JsonNode r = c.proximaMensagem();
            assertThat(r.get("tipo").asText()).isEqualTo("imprimir_ok");
            assertThat(r.get("id").asText()).isEqualTo("j-1");
            assertThat(r.get("estado").asText()).isEqualTo("ACEITO_SPOOLER");
            assertThat(impressao.jobs).hasSize(1);
            assertThat(impressao.jobs.get(0).pdf()).isEqualTo(PDF);
            assertThat(impressao.jobs.get(0).impressora()).isEqualTo("PDF");
            assertThat(impressao.jobs.get(0).nomeJob()).containsIgnoringCase("AgroEase");
            c.close();
        }

        @Test
        @DisplayName("sem `impressora` no frame → usa a selecionada persistida")
        void semImpressoraUsaSelecionada() throws Exception {
            ClienteTeste c = conectarEAutenticar(ORIGIN);
            c.send(json("tipo", "imprimir", "id", "j-2", "formato", "pdf", "bytesBase64", base64(PDF)));
            assertThat(c.proximaMensagem().get("tipo").asText()).isEqualTo("imprimir_ok");
            assertThat(impressao.jobs.get(0).impressora()).isEqualTo("EPSON TM-T20");
            c.close();
        }

        @Test
        @DisplayName("sem impressora no frame E nenhuma selecionada → imprimir_erro{IMPRESSORA_INDISPONIVEL} com mensagem pt-BR, sem tocar o motor")
        void nenhumaImpressora() throws Exception {
            config.limpar();
            ClienteTeste c = conectarEAutenticar(ORIGIN);
            c.send(json("tipo", "imprimir", "id", "j-3", "formato", "pdf", "bytesBase64", base64(PDF)));
            JsonNode r = c.proximaMensagem();
            assertThat(r.get("tipo").asText()).isEqualTo("imprimir_erro");
            assertThat(r.get("id").asText()).isEqualTo("j-3");
            assertThat(r.get("codigo").asText()).isEqualTo("IMPRESSORA_INDISPONIVEL");
            assertThat(r.get("mensagem").asText()).contains("impressora");
            assertThat(impressao.jobs).isEmpty();
            c.close();
        }

        @Test
        @DisplayName("motor devolve IMPRESSORA_INDISPONIVEL / ERRO → imprimir_erro{id, codigo, mensagem pt-BR curta} (detalhe técnico fica no log)")
        void falhasDoMotor() throws Exception {
            impressao.impressoraComErro = "PDF";
            ClienteTeste c = conectarEAutenticar(ORIGIN);
            c.send(json("tipo", "imprimir", "id", "j-4", "formato", "pdf", "bytesBase64", base64(PDF), "impressora", "SUMIDA"));
            JsonNode r1 = c.proximaMensagem();
            assertThat(r1.get("tipo").asText()).isEqualTo("imprimir_erro");
            assertThat(r1.get("codigo").asText()).isEqualTo("IMPRESSORA_INDISPONIVEL");
            assertThat(r1.get("mensagem").asText()).doesNotContain("fake").contains("SUMIDA");

            c.send(json("tipo", "imprimir", "id", "j-5", "formato", "pdf", "bytesBase64", base64(PDF), "impressora", "PDF"));
            JsonNode r2 = c.proximaMensagem();
            assertThat(r2.get("codigo").asText()).isEqualTo("ERRO");
            assertThat(r2.get("id").asText()).isEqualTo("j-5");
            assertThat(r2.get("mensagem").asText()).doesNotContain("fake");
            c.close();
        }

        @Test
        @DisplayName("frame inválido (base64 quebrado / sem %PDF- / formato html / > 2 MiB) → erro{id, MENSAGEM_INVALIDA}, motor intocado")
        void framesInvalidos() throws Exception {
            ClienteTeste c = conectarEAutenticar(ORIGIN);
            c.send(json("tipo", "imprimir", "id", "i1", "formato", "pdf", "bytesBase64", "@@@nao-e-base64@@@", "impressora", "PDF"));
            assertErroInvalido(c.proximaMensagem(), "i1");
            c.send(json("tipo", "imprimir", "id", "i2", "formato", "pdf", "bytesBase64", base64("<html>oi</html>".getBytes(StandardCharsets.UTF_8)), "impressora", "PDF"));
            assertErroInvalido(c.proximaMensagem(), "i2");
            c.send(json("tipo", "imprimir", "id", "i3", "formato", "html", "bytesBase64", base64(PDF), "impressora", "PDF"));
            assertErroInvalido(c.proximaMensagem(), "i3");
            byte[] grande = new byte[2 * 1024 * 1024 + 1];
            System.arraycopy(PDF, 0, grande, 0, PDF.length);
            c.send(json("tipo", "imprimir", "id", "i4", "formato", "pdf", "bytesBase64", base64(grande), "impressora", "PDF"));
            assertErroInvalido(c.proximaMensagem(10), "i4");
            assertThat(impressao.jobs).isEmpty();
            c.close();
        }

        @Test
        @DisplayName("PDF de EXATAMENTE 2 MiB é aceito (limite inclusivo, igual ao PWA); frame FRAGMENTADO (Chrome fragmenta) é remontado")
        void limiteInclusivoEFragmentado() throws Exception {
            ClienteTeste c = conectarEAutenticar(ORIGIN);
            byte[] exato = new byte[2 * 1024 * 1024];
            System.arraycopy(PDF, 0, exato, 0, PDF.length);
            c.send(json("tipo", "imprimir", "id", "g1", "formato", "pdf", "bytesBase64", base64(exato), "impressora", "PDF"));
            JsonNode r = c.proximaMensagem(15);
            assertThat(r.get("tipo").asText()).as("2 MiB exato: %s", r).isEqualTo("imprimir_ok");
            assertThat(impressao.jobs.get(0).pdf()).hasSize(2 * 1024 * 1024);

            // mesma mensagem cortada em 3 fragmentos (continuation frames)
            byte[] msg = json("tipo", "imprimir", "id", "g2", "formato", "pdf", "bytesBase64", base64(PDF), "impressora", "PDF").getBytes(StandardCharsets.UTF_8);
            int a = msg.length / 3, b = 2 * msg.length / 3;
            c.sendFragmentedFrame(org.java_websocket.enums.Opcode.TEXT, java.nio.ByteBuffer.wrap(msg, 0, a), false);
            c.sendFragmentedFrame(org.java_websocket.enums.Opcode.TEXT, java.nio.ByteBuffer.wrap(msg, a, b - a), false);
            c.sendFragmentedFrame(org.java_websocket.enums.Opcode.TEXT, java.nio.ByteBuffer.wrap(msg, b, msg.length - b), true);
            JsonNode r2 = c.proximaMensagem();
            assertThat(r2.get("tipo").asText()).isEqualTo("imprimir_ok");
            assertThat(r2.get("id").asText()).isEqualTo("g2");
            c.close();
        }

        @Test
        @DisplayName("id fora de [A-Za-z0-9_.:-]{1,64} → erro{MENSAGEM_INVALIDA} SEM id (o PWA rejeita o único pendente)")
        void idInvalido() throws Exception {
            ClienteTeste c = conectarEAutenticar(ORIGIN);
            c.send(json("tipo", "listar_impressoras", "id", "tem espaço"));
            JsonNode r = c.proximaMensagem();
            assertThat(r.get("tipo").asText()).isEqualTo("erro");
            assertThat(r.get("codigo").asText()).isEqualTo("MENSAGEM_INVALIDA");
            assertThat(r.has("id")).isFalse();
            c.close();
        }

        @Test
        @DisplayName("tipo desconhecido pós-auth → erro{TIPO_DESCONHECIDO, id}, conexão SEGUE aberta")
        void tipoDesconhecidoPosAuth() throws Exception {
            ClienteTeste c = conectarEAutenticar(ORIGIN);
            c.send(json("tipo", "reiniciar", "id", "x1"));
            JsonNode r = c.proximaMensagem();
            assertThat(r.get("codigo").asText()).isEqualTo("TIPO_DESCONHECIDO");
            assertThat(r.get("id").asText()).isEqualTo("x1");
            c.send(json("tipo", "ping"));
            assertThat(c.proximaMensagem().get("tipo").asText()).isEqualTo("pong");
            c.close();
        }

        private void assertErroInvalido(JsonNode r, String id) {
            assertThat(r.get("tipo").asText()).isEqualTo("erro");
            assertThat(r.get("codigo").asText()).isEqualTo("MENSAGEM_INVALIDA");
            assertThat(r.get("id").asText()).isEqualTo(id);
        }
    }

    // ── 10. fila e não-bloqueio ───────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("10. fila de impressão não bloqueia o servidor")
    class Fila {
        @Test
        @DisplayName("job TRAVADO no motor: ping da mesma conexão e hello de OUTRA conexão respondem; após o timeout interno → imprimir_erro{ERRO}; fila cheia → imprimir_erro imediato")
        void jobTravadoNaoTravaServidor() throws Exception {
            CountDownLatch trinco = new CountDownLatch(1);
            CountDownLatch entrou = new CountDownLatch(1);
            impressao.trinco.set(trinco);
            impressao.entrou.set(entrou);
            try {
                ClienteTeste c = conectarEAutenticar(ORIGIN);
                c.send(json("tipo", "imprimir", "id", "t1", "formato", "pdf", "bytesBase64", base64(PDF), "impressora", "PDF"));
                assertThat(entrou.await(5, TimeUnit.SECONDS)).as("job entrou no motor e travou").isTrue();

                // o servidor continua vivo para ESTA e para OUTRA conexão enquanto o motor está preso
                c.send(json("tipo", "ping"));
                assertThat(c.proximaMensagem().get("tipo").asText()).isEqualTo("pong");
                ClienteTeste outra = ClienteTeste.conectarAberto(servidor.getPort(), OUTRA_ORIGIN);
                outra.send(json("tipo", "hello", "versaoProtocolo", 1));
                assertThat(outra.proximaMensagem().get("tipo").asText()).isEqualTo("hello_ok");
                outra.close();

                // fila: 1 executando + 2 esperando; o 4º é recusado na hora
                c.send(json("tipo", "imprimir", "id", "t2", "formato", "pdf", "bytesBase64", base64(PDF), "impressora", "PDF"));
                c.send(json("tipo", "imprimir", "id", "t3", "formato", "pdf", "bytesBase64", base64(PDF), "impressora", "PDF"));
                c.send(json("tipo", "imprimir", "id", "t4", "formato", "pdf", "bytesBase64", base64(PDF), "impressora", "PDF"));
                JsonNode cheia = c.proximaMensagem();
                assertThat(cheia.get("tipo").asText()).isEqualTo("imprimir_erro");
                assertThat(cheia.get("id").asText()).isEqualTo("t4");
                assertThat(cheia.get("codigo").asText()).isEqualTo("ERRO");
                assertThat(cheia.get("mensagem").asText()).containsIgnoringCase("ocupado");

                // timeout interno (1,5 s no teste; 12 s em produção) → erro para t1 SEM esperar o motor soltar
                JsonNode t1 = c.proximaMensagem(5);
                assertThat(t1.get("tipo").asText()).isEqualTo("imprimir_erro");
                assertThat(t1.get("id").asText()).isEqualTo("t1");
                assertThat(t1.get("codigo").asText()).isEqualTo("ERRO");
                assertThat(t1.get("mensagem").asText()).containsIgnoringCase("não respondeu");

                trinco.countDown(); // o motor solta: t1 termina tarde (só log), t2 e t3 rodam
                impressao.trinco.set(null);
                JsonNode t2 = c.proximaMensagem(10);
                JsonNode t3 = c.proximaMensagem(10);
                assertThat(t2.get("id").asText()).isEqualTo("t2");
                assertThat(t3.get("id").asText()).isEqualTo("t3");
                assertThat(t2.get("tipo").asText()).isEqualTo("imprimir_ok");
                assertThat(t3.get("tipo").asText()).isEqualTo("imprimir_ok");
                // o resultado atrasado de t1 NÃO é enviado (já respondemos ERRO) — nada mais na fila do cliente
                assertThat(c.talvezProximaMensagem(500)).isNull();
                c.close();
            } finally {
                trinco.countDown();
            }
        }

        @Test
        @DisplayName("motor PRESO (job rodando há > 10× o prazo): imprimir seguinte → imprimir_erro{ERRO 'travou'} na hora, sem entrar na fila; solto → volta a imprimir")
        void motorPresoRecusaNaHora() throws Exception {
            servidor.stop(1000);
            prazos.impressao = Duration.ofMillis(120); // motor preso = 1,2 s
            servidor = novoServidor();
            servidor.iniciar(Duration.ofSeconds(10));
            CountDownLatch trinco = new CountDownLatch(1);
            CountDownLatch entrou = new CountDownLatch(1);
            impressao.trinco.set(trinco);
            impressao.entrou.set(entrou);
            try {
                ClienteTeste c = conectarEAutenticar(ORIGIN);
                c.send(json("tipo", "imprimir", "id", "p1", "formato", "pdf", "bytesBase64", base64(PDF), "impressora", "PDF"));
                assertThat(entrou.await(5, TimeUnit.SECONDS)).isTrue();
                JsonNode p1 = c.proximaMensagem(5);
                assertThat(p1.get("id").asText()).isEqualTo("p1");
                assertThat(p1.get("mensagem").asText()).containsIgnoringCase("não respondeu"); // timeout normal: ainda não é "preso"

                Thread.sleep(1500); // > 10 × 120 ms com o job ainda dentro do motor
                c.send(json("tipo", "imprimir", "id", "p2", "formato", "pdf", "bytesBase64", base64(PDF), "impressora", "PDF"));
                JsonNode p2 = c.proximaMensagem(2);
                assertThat(p2.get("tipo").asText()).isEqualTo("imprimir_erro");
                assertThat(p2.get("id").asText()).isEqualTo("p2");
                assertThat(p2.get("codigo").asText()).isEqualTo("ERRO");
                assertThat(p2.get("mensagem").asText()).containsIgnoringCase("travou");

                trinco.countDown(); // o SO soltou o motor: p2 nunca entrou na fila; p3 imprime normalmente
                impressao.trinco.set(null);
                Thread.sleep(200);
                c.send(json("tipo", "imprimir", "id", "p3", "formato", "pdf", "bytesBase64", base64(PDF), "impressora", "PDF"));
                JsonNode p3 = c.proximaMensagem(5);
                assertThat(p3.get("id").asText()).isEqualTo("p3");
                assertThat(p3.get("tipo").asText()).isEqualTo("imprimir_ok");
                c.close();
            } finally {
                trinco.countDown();
            }
        }
    }

    @Nested
    @DisplayName("10b. teto de conexões simultâneas (8 em produção)")
    class TetoDeConexoes {
        @Test
        @DisplayName("acima do teto → close 1013 'LIMITE_CONEXOES' na abertura; fechando uma, a próxima entra")
        void tetoDeConexoes() throws Exception {
            servidor.stop(1000);
            prazos.tetoConexoes = 2;
            servidor = novoServidor();
            servidor.iniciar(Duration.ofSeconds(10));
            ClienteTeste c1 = ClienteTeste.conectarAberto(servidor.getPort(), ORIGIN);
            ClienteTeste c2 = ClienteTeste.conectarAberto(servidor.getPort(), OUTRA_ORIGIN);
            ClienteTeste c3 = ClienteTeste.conectarAberto(servidor.getPort(), ORIGIN);
            assertThat(c3.esperarFechar(5)).as("3ª conexão recusada").isTrue();
            assertThat(c3.codigoFechamento.get()).isEqualTo(1013);
            assertThat(c3.motivoFechamento.get()).isEqualTo("LIMITE_CONEXOES");
            // as duas dentro do teto seguem vivas e funcionais
            c1.send(json("tipo", "ping"));
            assertThat(c1.proximaMensagem().get("tipo").asText()).isEqualTo("pong");
            c2.close();
            assertThat(c2.esperarFechar(5)).isTrue();
            Thread.sleep(200);
            ClienteTeste c4 = ClienteTeste.conectarAberto(servidor.getPort(), OUTRA_ORIGIN);
            c4.send(json("tipo", "ping"));
            assertThat(c4.proximaMensagem().get("tipo").asText()).isEqualTo("pong");
            c1.close();
            c4.close();
        }
    }

    @Nested
    @DisplayName("10b. listagem com prazo (adversarial A3)")
    class Listagem {
        @Test
        @DisplayName("motor de listagem TRAVADO → erro{id, ERRO} dentro do prazo (não silêncio); imprimir com impressora explícita segue ok; ao soltar, a resposta atrasada é descartada")
        void listagemPresaRespondeErro() throws Exception {
            servidor.stop(1000);
            prazos.listagem = Duration.ofMillis(600);
            servidor = novoServidor();
            servidor.iniciar(Duration.ofSeconds(10));
            CountDownLatch trinco = new CountDownLatch(1);
            impressao.trincoListar.set(trinco);
            try {
                ClienteTeste c = conectarEAutenticar(ORIGIN);
                c.send(json("tipo", "listar_impressoras", "id", "lp1"));
                JsonNode r = c.proximaMensagem(5);
                assertThat(r.get("tipo").asText()).isEqualTo("erro");
                assertThat(r.get("id").asText()).isEqualTo("lp1");
                assertThat(r.get("codigo").asText()).isEqualTo("ERRO");
                assertThat(r.get("mensagem").asText()).containsIgnoringCase("não responderam");
                c.send(json("tipo", "imprimir", "id", "lp2", "formato", "pdf", "bytesBase64", base64(PDF), "impressora", "PDF"));
                assertThat(c.proximaMensagem(5).get("tipo").asText()).isEqualTo("imprimir_ok");
                trinco.countDown();
                impressao.trincoListar.set(null);
                assertThat(c.talvezProximaMensagem(700)).as("resposta atrasada da listagem NÃO é enviada").isNull();
                c.close();
            } finally {
                trinco.countDown();
            }
        }
    }

    // ── 11. peer morto / ocioso ───────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("11. ociosidade e peer morto liberam a vaga")
    class Ocioso {
        @Test
        @DisplayName("autenticada sem tráfego além do teto → close 1000 'ocioso' e a Origin volta a poder autenticar")
        void ociosoFecha() throws Exception {
            servidor.stop(1000);
            prazos.ociosoAutenticado = Duration.ofMillis(800);
            servidor = novoServidor();
            servidor.iniciar(Duration.ofSeconds(10));

            ClienteTeste c = conectarEAutenticar(ORIGIN);
            assertThat(c.esperarFechar(5)).isTrue();
            assertThat(c.codigoFechamento.get()).isEqualTo(1000);
            assertThat(c.motivoFechamento.get()).isEqualTo("ocioso");

            ClienteTeste d = conectarEAutenticar(ORIGIN); // vaga liberada
            d.close();
        }

        @Test
        @DisplayName("peer MORTO (não responde ping) → connectionLostTimeout derruba a conexão e a Origin volta a poder autenticar (adversarial A5)")
        void peerMortoLiberaVaga() throws Exception {
            servidor.stop(1000);
            prazos.conexaoPerdidaSegundos = 1;
            servidor = novoServidor();
            servidor.iniciar(Duration.ofSeconds(10));
            ClienteTeste morto = conectarEAutenticar(ORIGIN);
            morto.mudoParaPing = true;
            assertThat(morto.esperarFechar(15)).as("servidor detecta o peer morto").isTrue();
            ClienteTeste vivo = conectarEAutenticar(ORIGIN); // a vaga da Origin foi liberada
            vivo.close();
        }
    }

    // ── 12. transporte (já existia no F0, continua valendo) ───────────────────────────────────────────────────

    @Nested
    @DisplayName("12. transporte")
    class Transporte {
        @Test
        @DisplayName("JSON inválido → erro{MENSAGEM_INVALIDA} sem id; binário → TIPO_BINARIO_NAO_SUPORTADO; conexão segue")
        void lixo() throws Exception {
            ClienteTeste c = ClienteTeste.conectarAberto(servidor.getPort(), ORIGIN);
            c.send("{isso nao e json");
            JsonNode r = c.proximaMensagem();
            assertThat(r.get("codigo").asText()).isEqualTo("MENSAGEM_INVALIDA");
            assertThat(r.has("id")).isFalse();
            c.send(new byte[]{1, 2, 3});
            assertThat(c.proximaMensagem().get("codigo").asText()).isEqualTo("TIPO_BINARIO_NAO_SUPORTADO");
            c.send(json("tipo", "ping"));
            assertThat(c.proximaMensagem().get("tipo").asText()).isEqualTo("pong");
            c.close();
        }
    }
}

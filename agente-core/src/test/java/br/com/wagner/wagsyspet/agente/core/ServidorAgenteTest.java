package br.com.wagner.wagsyspet.agente.core;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Servidor WebSocket do agente com sockets REAIS em 127.0.0.1 (porta efêmera): o porteiro está ligado ao handshake,
 * o protocolo mínimo responde, e o bind é só loopback.
 */
@DisplayName("ServidorAgente — WebSocket em 127.0.0.1 com porteiro no handshake")
class ServidorAgenteTest {

    private static final String ORIGIN_PWA = "https://wagsyspet-frontend.vercel.app";
    /** Identidade que o agente anuncia: versão do binário, protocolo e o agenteId recebido no pareamento (F3-L0). */
    static final InfoAgente INFO_TESTE = new InfoAgente("1.2.3", 1, "uid-teste-0001");

    private ServidorAgente servidor;

    @BeforeEach
    void subir() throws Exception {
        servidor = new ServidorAgente(0, Set.of(ORIGIN_PWA), INFO_TESTE);
        servidor.iniciar(Duration.ofSeconds(10));
    }

    @AfterEach
    void derrubar() throws Exception {
        servidor.stop(1000);
    }

    @Test
    @DisplayName("escuta SÓ em 127.0.0.1 (loopback), numa porta efêmera")
    void bindSoLoopback() {
        assertThat(servidor.getAddress().getAddress()).isEqualTo(InetAddress.getLoopbackAddress());
        assertThat(servidor.getPort()).isGreaterThan(0);
    }

    @Test
    @DisplayName("Origin do PWA → conecta; hello → hello_ok com versão, protocolo (NÚMERO), so e agenteId (contrato §7.4-1)")
    void originPermitidaRecebeHelloOk() throws Exception {
        ClienteTeste c = ClienteTeste.conectar(servidor.getPort(), Map.of("Origin", ORIGIN_PWA));
        assertThat(c.abriu.await(5, TimeUnit.SECONDS)).as("handshake deve completar").isTrue();

        c.send("{\"tipo\":\"hello\",\"versaoProtocolo\":1}");
        JsonNode resposta = c.proximaMensagem();
        assertThat(resposta.get("tipo").asText()).isEqualTo("hello_ok");
        assertThat(resposta.get("agenteVersao").asText()).isEqualTo("1.2.3");
        // o parser do PWA exige protocolo NUMÉRICO (string → frame descartado → "sem resposta")
        assertThat(resposta.get("protocolo").isInt()).as("protocolo deve ser número JSON").isTrue();
        assertThat(resposta.get("protocolo").asInt()).isEqualTo(1);
        assertThat(resposta.get("so").asText()).isNotBlank();
        // agenteId: sem ele o PWA fecha 1000 'desatualizado' e nunca envia auth
        assertThat(resposta.path("agenteId").isTextual()).as("agenteId deve ser string").isTrue();
        assertThat(resposta.get("agenteId").asText()).isEqualTo("uid-teste-0001");
        c.close();
    }

    @Test
    @DisplayName("hello com versaoProtocolo desconhecido AINDA responde hello_ok (o PWA decide pelo /release; erro aqui = 'agente não encontrado')")
    void helloComProtocoloDesconhecidoRespondeHelloOk() throws Exception {
        ClienteTeste c = ClienteTeste.conectar(servidor.getPort(), Map.of("Origin", ORIGIN_PWA));
        assertThat(c.abriu.await(5, TimeUnit.SECONDS)).isTrue();
        c.send("{\"tipo\":\"hello\",\"versaoProtocolo\":99}");
        JsonNode resposta = c.proximaMensagem();
        assertThat(resposta.get("tipo").asText()).isEqualTo("hello_ok");
        assertThat(resposta.get("agenteId").asText()).isEqualTo("uid-teste-0001");
        c.close();
    }

    @Test
    @DisplayName("ping → pong")
    void pingPong() throws Exception {
        ClienteTeste c = ClienteTeste.conectar(servidor.getPort(), Map.of("Origin", ORIGIN_PWA));
        assertThat(c.abriu.await(5, TimeUnit.SECONDS)).isTrue();
        c.send("{\"tipo\":\"ping\"}");
        assertThat(c.proximaMensagem().get("tipo").asText()).isEqualTo("pong");
        c.close();
    }

    @Test
    @DisplayName("mensagem desconhecida ANTES do auth → erro NAO_AUTENTICADO + close 1008 (F3 §7.4-1: pré-auth só hello/ping/auth; pós-auth vira TIPO_DESCONHECIDO — ContratoF3Test)")
    void mensagemDesconhecidaPreAuth() throws Exception {
        ClienteTeste c = ClienteTeste.conectar(servidor.getPort(), Map.of("Origin", ORIGIN_PWA));
        assertThat(c.abriu.await(5, TimeUnit.SECONDS)).isTrue();
        c.send("{\"tipo\":\"formatar_disco\"}");
        JsonNode r = c.proximaMensagem();
        assertThat(r.get("tipo").asText()).isEqualTo("erro");
        assertThat(r.get("codigo").asText()).isEqualTo("NAO_AUTENTICADO");
        assertThat(c.esperarFechar(5)).isTrue();
        assertThat(c.codigoFechamento.get()).isEqualTo(1008);
    }

    @Test
    @DisplayName("host de DEV sem pareamento: auth → erro NAO_PAREADO + close 1008 (nunca autentica sem chave da loja)")
    void semPareamentoRecusaAuth() throws Exception {
        ClienteTeste c = ClienteTeste.conectar(servidor.getPort(), Map.of("Origin", ORIGIN_PWA));
        assertThat(c.abriu.await(5, TimeUnit.SECONDS)).isTrue();
        c.send("{\"tipo\":\"auth\",\"ticket\":\"v1.x.y\"}");
        JsonNode r = c.proximaMensagem();
        assertThat(r.get("codigo").asText()).isEqualTo("NAO_PAREADO");
        assertThat(c.esperarFechar(5)).isTrue();
        assertThat(c.codigoFechamento.get()).isEqualTo(1008);
        assertThat(c.motivoFechamento.get()).isEqualTo("NAO_PAREADO");
    }

    @Test
    @DisplayName("frame BINÁRIO → erro tipado explícito (protocolo é JSON/texto), conexão segue aberta")
    void frameBinarioRecusadoExplicitamente() throws Exception {
        ClienteTeste c = ClienteTeste.conectar(servidor.getPort(), Map.of("Origin", ORIGIN_PWA));
        assertThat(c.abriu.await(5, TimeUnit.SECONDS)).isTrue();
        c.send(new byte[] {0x25, 0x50, 0x44, 0x46}); // "%PDF" cru
        JsonNode r = c.proximaMensagem();
        assertThat(r.get("tipo").asText()).isEqualTo("erro");
        assertThat(r.get("codigo").asText()).isEqualTo("TIPO_BINARIO_NAO_SUPORTADO");
        assertThat(c.isOpen()).isTrue();
        c.close();
    }

    @Test
    @DisplayName("frame acima do teto (4 MB) → conexão fechada (1009), servidor SEGUE VIVO; abaixo do teto passa")
    void tetoDeFrameProtegeAMemoria() throws Exception {
        // Abaixo do teto: 1 MB de JSON válido é processado normalmente.
        ClienteTeste ok = ClienteTeste.conectar(servidor.getPort(), Map.of("Origin", ORIGIN_PWA));
        assertThat(ok.abriu.await(5, TimeUnit.SECONDS)).isTrue();
        ok.send("{\"tipo\":\"ping\",\"lastro\":\"" + "x".repeat(1024 * 1024) + "\"}");
        assertThat(ok.proximaMensagem().get("tipo").asText()).isEqualTo("pong");
        ok.close();

        // Acima do teto: um único frame de 5 MB deve ser recusado pela camada WS (antes de virar String/JSON).
        ClienteTeste grande = ClienteTeste.conectar(servidor.getPort(), Map.of("Origin", ORIGIN_PWA));
        assertThat(grande.abriu.await(5, TimeUnit.SECONDS)).isTrue();
        grande.send("{\"tipo\":\"ping\",\"lastro\":\"" + "x".repeat(5 * 1024 * 1024) + "\"}");
        assertThat(grande.fechou.await(10, TimeUnit.SECONDS)).as("conexão deve ser fechada").isTrue();
        assertThat(grande.codigoFechamento.get()).as("close code TOOBIG").isEqualTo(1009);
        assertThat(grande.recebidas).isEmpty();

        // O servidor continua servindo outras conexões.
        assertThat(servidor.estaEscutando()).isTrue();
        ClienteTeste depois = ClienteTeste.conectar(servidor.getPort(), Map.of("Origin", ORIGIN_PWA));
        assertThat(depois.abriu.await(5, TimeUnit.SECONDS)).isTrue();
        depois.send("{\"tipo\":\"ping\"}");
        assertThat(depois.proximaMensagem().get("tipo").asText()).isEqualTo("pong");
        depois.close();
    }

    @Test
    @DisplayName("Origin de outro site → handshake RECUSADO pelo servidor (404, nunca 101)")
    void originEstranhaRecusada() throws Exception {
        ClienteTeste c = ClienteTeste.conectar(servidor.getPort(), Map.of("Origin", "https://site-malicioso.com"));
        assertRecusadoPeloServidor(c);
    }

    @Test
    @DisplayName("sem Origin (cliente não-navegador) → handshake RECUSADO pelo servidor")
    void semOriginRecusado() throws Exception {
        ClienteTeste c = ClienteTeste.conectar(servidor.getPort(), Map.of());
        assertRecusadoPeloServidor(c);
    }

    @Test
    @DisplayName("Origin OK mas Host de DNS rebinding → handshake RECUSADO (barreira 2 ligada no socket)")
    void hostRebindingRecusado() throws Exception {
        ClienteTeste c = ClienteTeste.conectar(servidor.getPort(),
                Map.of("Origin", ORIGIN_PWA, "Host", "agente.evil.com:" + servidor.getPort()));
        assertRecusadoPeloServidor(c);
    }

    @Test
    @DisplayName("Origin OK mas Host loopback em OUTRA porta → handshake RECUSADO")
    void hostPortaErradaRecusado() throws Exception {
        ClienteTeste c = ClienteTeste.conectar(servidor.getPort(),
                Map.of("Origin", ORIGIN_PWA, "Host", "127.0.0.1:" + (servidor.getPort() + 1)));
        assertRecusadoPeloServidor(c);
    }

    /**
     * Recusa REAL = o servidor respondeu ao handshake com status ≠ 101 (a lib cliente fecha com 1002 e cita o 404).
     * Distingue de "servidor morto/porta fechada" (onError com ConnectException) — esse cenário NÃO pode passar.
     */
    private static void assertRecusadoPeloServidor(ClienteTeste c) throws Exception {
        assertThat(c.fechou.await(5, TimeUnit.SECONDS)).as("deve fechar/recusar").isTrue();
        assertThat(c.abriu.getCount()).as("onOpen nunca deve disparar").isEqualTo(1);
        assertThat(c.erro.get()).as("não é falha de conexão, é recusa do servidor").isNull();
        assertThat(c.motivoFechamento.get()).as("cliente viu a resposta HTTP de recusa").contains("404");
    }

    @Test
    @DisplayName("porta já ocupada → iniciar() falha RÁPIDO com a causa (BindException), não espera o timeout")
    void portaOcupadaFalhaRapido() throws Exception {
        ServidorAgente segundo = new ServidorAgente(servidor.getPort(), Set.of(ORIGIN_PWA), INFO_TESTE);
        long t0 = System.nanoTime();
        try {
            assertThatThrownBy(() -> segundo.iniciar(Duration.ofSeconds(10)))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining(String.valueOf(servidor.getPort()));
            assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(5));
        } finally {
            segundo.stop(1000);
        }
    }

    @Test
    @DisplayName("erro dentro do onStart (a lib NÃO protege) → iniciar() falha rápido e o servidor é derrubado")
    void erroNoOnStartFalhaRapidoESemOrfao() throws Exception {
        ServidorAgente quebrado = new ServidorAgente(0, Set.of(ORIGIN_PWA), INFO_TESTE) {
            @Override
            protected void aoIniciar() {
                throw new IllegalStateException("boom no onStart");
            }
        };
        long t0 = System.nanoTime();
        assertThatThrownBy(() -> quebrado.iniciar(Duration.ofSeconds(10)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("boom no onStart");
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(5));
        assertThat(quebrado.estaEscutando()).as("não pode ficar escutando órfão").isFalse();
    }

    @Test
    @DisplayName("estaEscutando(): true enquanto vivo, false depois de stop()")
    void estaEscutandoReflete() throws Exception {
        assertThat(servidor.estaEscutando()).isTrue();
        servidor.stop(1000);
        assertThat(servidor.estaEscutando()).isFalse();
    }

    @Test
    @DisplayName("falhaFatal(): pendente enquanto vivo (o AgenteMain espera nela em vez de join eterno)")
    void falhaFatalPendenteEnquantoVivo() {
        assertThat(servidor.falhaFatal().isDone()).isFalse();
    }
}

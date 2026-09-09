package br.com.wagner.wagsyspet.agente.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.java_websocket.WebSocket;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.framing.Framedata;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Cliente Java-WebSocket de teste (socket REAL) com headers customizados e fila de mensagens recebidas. */
final class ClienteTeste extends WebSocketClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    final CountDownLatch abriu = new CountDownLatch(1);
    final CountDownLatch fechou = new CountDownLatch(1);
    final BlockingQueue<String> recebidas = new LinkedBlockingQueue<>();
    final AtomicReference<Integer> codigoFechamento = new AtomicReference<>();
    final AtomicReference<String> motivoFechamento = new AtomicReference<>("");
    final AtomicReference<Exception> erro = new AtomicReference<>();
    /** true = simula peer MORTO: não responde aos pings do servidor (connectionLostTimeout deve derrubar a conexão). */
    volatile boolean mudoParaPing;

    private ClienteTeste(URI uri, Map<String, String> headers) {
        super(uri, headers);
    }

    /** A lib responde pong automaticamente; um peer morto não responde nada. */
    @Override
    public void onWebsocketPing(WebSocket conn, Framedata f) {
        if (!mudoParaPing) {
            super.onWebsocketPing(conn, f);
        }
    }

    static ClienteTeste conectar(int porta, Map<String, String> headers) {
        ClienteTeste c = new ClienteTeste(URI.create("ws://127.0.0.1:" + porta), headers);
        c.connect();
        return c;
    }

    /** Conecta com a Origin dada e espera o handshake completar. */
    static ClienteTeste conectarAberto(int porta, String origin) throws InterruptedException {
        ClienteTeste c = conectar(porta, Map.of("Origin", origin));
        assertThat(c.abriu.await(5, TimeUnit.SECONDS)).as("handshake deve completar").isTrue();
        return c;
    }

    JsonNode proximaMensagem() throws Exception {
        return proximaMensagem(5);
    }

    JsonNode proximaMensagem(long segundos) throws Exception {
        String m = recebidas.poll(segundos, TimeUnit.SECONDS);
        assertThat(m).as("resposta em " + segundos + "s").isNotNull();
        return JSON.readTree(m);
    }

    /** Devolve null se nada chegar no prazo (para provar SILÊNCIO, ex.: frame ignorado). */
    JsonNode talvezProximaMensagem(long millis) throws Exception {
        String m = recebidas.poll(millis, TimeUnit.MILLISECONDS);
        return m == null ? null : JSON.readTree(m);
    }

    boolean esperarFechar(long segundos) throws InterruptedException {
        return fechou.await(segundos, TimeUnit.SECONDS);
    }

    @Override public void onOpen(ServerHandshake h) { abriu.countDown(); }
    @Override public void onMessage(String message) { recebidas.add(message); }

    @Override public void onClose(int code, String reason, boolean remote) {
        codigoFechamento.set(code);
        motivoFechamento.set(reason == null ? "" : reason);
        fechou.countDown();
    }

    @Override public void onError(Exception ex) {
        erro.set(ex);
        fechou.countDown();
    }
}

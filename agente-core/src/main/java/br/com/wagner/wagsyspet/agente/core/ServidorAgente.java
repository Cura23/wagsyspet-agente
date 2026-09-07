package br.com.wagner.wagsyspet.agente.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.java_websocket.WebSocket;
import org.java_websocket.drafts.Draft;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.exceptions.InvalidDataException;
import org.java_websocket.framing.CloseFrame;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.handshake.ServerHandshakeBuilder;
import org.java_websocket.protocols.Protocol;
import org.java_websocket.server.WebSocketServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Servidor WebSocket local do agente (plano §2.1/§2.4): escuta <b>só em 127.0.0.1</b>; no handshake aplica o
 * {@link PorteiroHandshake} (Origin exata + Host loopback) e recusa ANTES do upgrade (a lib responde
 * {@code 404 WebSocket Upgrade Failure} e fecha — ver {@link PorteiroHandshake.Decisao}); depois fala o
 * protocolo do PDV em JSON ({@code hello} → {@code hello_ok}, {@code ping} → {@code pong}, …).
 *
 * <p>Defesas da camada de transporte (adversarial F0):</p>
 * <ul>
 *   <li><b>Teto de frame {@value #MAX_FRAME_BYTES} bytes</b>: sem ele o {@code Draft_6455} padrão aceita 2 GB e a lib
 *       <i>pré-aloca o tamanho DECLARADO no header</i> — 14 bytes anunciando 100 MB derrubavam o agente por OOM antes
 *       de qualquer mensagem. O plano limita o PDF a 2 MB base64; 4 MB dá folga. Frame maior → close 1009, servidor
 *       segue.</li>
 *   <li><b>Frame binário → erro tipado</b> (o default da lib engole em silêncio e o cliente fica esperando).</li>
 *   <li><b>Subida à prova de falha</b>: bind falho ou exceção no {@code onStart} (a lib não protege) fazem
 *       {@link #iniciar} lançar rápido com a causa e derrubar o servidor; timeout também derruba (sem órfão em thread
 *       não-daemon).</li>
 *   <li><b>Sinal de morte pós-start</b> ({@link #falhaFatal()}) e <b>auto-checagem</b> ({@link #estaEscutando()}):
 *       a lib fecha o canal de LISTEN em silêncio num {@code EMFILE} e trava a própria selector-thread em self-join
 *       num erro fatal — o host precisa observar de fora, não confiar em "processo vivo".</li>
 * </ul>
 *
 * <p>Estado de spike F0: protocolo mínimo. A 3ª barreira (ticket assinado pelo backend como 1ª mensagem) e
 * {@code imprimir} entram na F3 com TDD.</p>
 */
public class ServidorAgente extends WebSocketServer {

    private static final Logger log = LoggerFactory.getLogger(ServidorAgente.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Maior frame aceito (bytes). Plano: PDF ≤ 2 MB em base64 dentro do JSON de {@code imprimir}. */
    public static final int MAX_FRAME_BYTES = 4 * 1024 * 1024;

    private final Set<String> originsPermitidas;
    private final InfoAgente info;
    private final CountDownLatch iniciado = new CountDownLatch(1);
    private final CompletableFuture<Exception> morte = new CompletableFuture<>();
    private volatile PorteiroHandshake porteiro;
    /** Erro fatal de subida (ex.: porta ocupada) — a lib entrega via {@code onError(null, ex)} e nunca chama onStart. */
    private volatile Exception erroFatal;

    /**
     * @param porta 0 = efêmera (testes); em produção, a porta fixa do agente
     * @param originsPermitidas Origins exatas do PWA (vêm do backend no pareamento)
     */
    public ServidorAgente(int porta, Set<String> originsPermitidas, InfoAgente info) {
        // bind exclusivo em loopback — nunca 0.0.0.0; draft explícito só pra impor o teto de frame
        super(new InetSocketAddress("127.0.0.1", porta), List.of(draftComTeto()));
        this.originsPermitidas = Set.copyOf(Objects.requireNonNull(originsPermitidas));
        this.info = Objects.requireNonNull(info);
        setReuseAddr(true);
    }

    /** RFC 6455 igual ao default da lib (sem extensões, subprotocolo vazio), só com {@link #MAX_FRAME_BYTES}. */
    private static Draft draftComTeto() {
        return new Draft_6455(List.of(), List.of(new Protocol("")), MAX_FRAME_BYTES);
    }

    /**
     * Sobe o servidor e espera ele estar escutando.
     *
     * @throws IOException      se o bind falhar (porta ocupada) ou o {@code onStart} lançar — falha RÁPIDO, com a causa,
     *                          e o servidor já está derrubado
     * @throws TimeoutException se a lib não sinalizar nem sucesso nem erro dentro do prazo (servidor derrubado)
     */
    public void iniciar(Duration timeout) throws InterruptedException, TimeoutException, IOException {
        start();
        if (!iniciado.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            derrubarSilencioso();
            throw new TimeoutException("Servidor do agente não subiu em " + timeout);
        }
        Exception fatal = erroFatal;
        if (fatal != null) {
            derrubarSilencioso();
            throw new IOException("Não foi possível escutar em 127.0.0.1:" + getAddress().getPort()
                    + " — porta ocupada? (" + fatal + ")", fatal);
        }
    }

    /**
     * Completa quando o servidor morre DEPOIS de ter subido (erro fatal da lib). Enquanto vivo, fica pendente —
     * o host espera nela (em vez de {@code join()} eterno) e encerra o processo com código ≠ 0 pro supervisor reiniciar.
     */
    public CompletableFuture<Exception> falhaFatal() {
        return morte;
    }

    /**
     * Prova de fora que a porta aceita conexão TCP agora (a lib pode fechar o LISTEN em silêncio — {@code EMFILE}
     * no accept — mantendo o processo vivo). Conexão crua sem handshake: a lib não chama {@code onOpen/onClose}.
     */
    public boolean estaEscutando() {
        int porta = getPort();
        if (porta <= 0) {
            return false;
        }
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", porta), 1000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public void onStart() {
        try {
            // A porta só é conhecida aqui quando se pede 0 (efêmera); o Host permitido depende dela.
            porteiro = new PorteiroHandshake(originsPermitidas, getPort());
            aoIniciar();
            log.info("Agente escutando em ws://127.0.0.1:{} (origins permitidas: {})", getPort(), originsPermitidas);
        } catch (RuntimeException e) {
            // A lib só protege IOException em volta do onStart: uma RuntimeException mataria a selector-thread
            // sem onError e o latch nunca zeraria (timeout com socket BOUND preso). Sinalizamos nós mesmos.
            erroFatal = e;
            log.error("Falha ao iniciar o agente: {}", e.toString());
        } finally {
            iniciado.countDown();
        }
    }

    /** Gancho de subida (F3: pareamento/bandeja). Roda dentro do {@code onStart}, protegido. Spike: vazio. */
    protected void aoIniciar() {
    }

    @Override
    public ServerHandshakeBuilder onWebsocketHandshakeReceivedAsServer(WebSocket conn, Draft draft, ClientHandshake request)
            throws InvalidDataException {
        ServerHandshakeBuilder builder = super.onWebsocketHandshakeReceivedAsServer(conn, draft, request);
        String origin = request.getFieldValue("Origin");
        String host = request.getFieldValue("Host");
        PorteiroHandshake.Decisao d = porteiro.avaliar(origin, host);
        if (!d.permitido()) {
            log.warn("Handshake recusado ({}): {} | Origin='{}' Host='{}'", d.status(), d.motivo(), origin, host);
            throw new InvalidDataException(CloseFrame.POLICY_VALIDATION, d.motivo());
        }
        return builder;
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        log.info("Conexão aberta de Origin='{}'", handshake.getFieldValue("Origin"));
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        log.info("Conexão fechada (code={}, remote={}): {}", code, remote, reason);
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        try {
            JsonNode msg = JSON.readTree(message);
            String tipo = msg.path("tipo").asText("");
            switch (tipo) {
                case "hello" -> conn.send(helloOk());
                case "ping" -> conn.send(JSON.createObjectNode().put("tipo", "pong").toString());
                default -> conn.send(erro("TIPO_DESCONHECIDO", "Tipo de mensagem não reconhecido: " + tipo));
            }
        } catch (Exception e) {
            conn.send(erro("MENSAGEM_INVALIDA", "JSON inválido: " + e.getMessage()));
        }
    }

    /** O protocolo é texto/JSON; o default da lib engoliria o binário em silêncio e o cliente ficaria esperando. */
    @Override
    public void onMessage(WebSocket conn, ByteBuffer message) {
        conn.send(erro("TIPO_BINARIO_NAO_SUPORTADO",
                "O agente só aceita mensagens de texto JSON (" + message.remaining() + " bytes binários ignorados)"));
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        if (conn == null) {
            // Erro do SERVIDOR (não de uma conexão). Na subida: bind falho → iniciar() lança com a causa.
            // Depois de subir: a lib vai chamar stop(0) na própria selector-thread (self-join eterno) — só quem está
            // de fora, esperando em falhaFatal(), consegue reagir.
            erroFatal = ex;
            log.error("Erro fatal do servidor do agente: {}", ex.toString());
            iniciado.countDown();
            morte.complete(ex);
            return;
        }
        log.warn("Erro na conexão {}: {}", conn.getRemoteSocketAddress(), ex.toString());
    }

    private void derrubarSilencioso() {
        try {
            stop(1000); // stop(0) = join(0) infinito se a selector-thread estiver presa num onStart lento
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.debug("stop() após falha de subida: {}", e.toString());
        }
    }

    private String helloOk() {
        ObjectNode n = JSON.createObjectNode();
        n.put("tipo", "hello_ok");
        n.put("agenteVersao", info.versao());
        n.put("protocolo", info.protocolo());
        n.put("so", info.sistemaOperacional());
        return n.toString();
    }

    private static String erro(String codigo, String mensagem) {
        ObjectNode n = JSON.createObjectNode();
        n.put("tipo", "erro");
        n.put("codigo", codigo);
        n.put("mensagem", mensagem);
        return n.toString();
    }
}

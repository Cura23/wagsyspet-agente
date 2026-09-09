package br.com.wagner.wagsyspet.agente.core;

import br.com.wagner.wagsyspet.agente.impressao.ImpressoraJavaxPrint.Resultado;
import br.com.wagner.wagsyspet.agente.protocolo.ticket.TicketClaims;
import br.com.wagner.wagsyspet.agente.protocolo.ticket.TicketInvalidoException;
import br.com.wagner.wagsyspet.agente.protocolo.ticket.VerificadorTicket;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.java_websocket.WebSocket;
import org.java_websocket.drafts.Draft;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.exceptions.InvalidDataException;
import org.java_websocket.exceptions.WebsocketNotConnectedException;
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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Servidor WebSocket local do agente (plano canônico §2.1/§2.4/§7.4): escuta <b>só em 127.0.0.1</b>; no handshake aplica o
 * {@link PorteiroHandshake} (Origin exata + Host loopback) e recusa ANTES do upgrade (a lib responde
 * {@code 404 WebSocket Upgrade Failure} e fecha — ver {@link PorteiroHandshake.Decisao}); depois fala o protocolo v1 do
 * PDV em JSON, cujo contrato é o cliente F2 ({@code agenteClient.ts}) e o teste {@code ContratoF3Test}:
 *
 * <ol>
 *   <li>{@code hello} → {@code hello_ok{agenteVersao, protocolo, so, agenteId}} — pré-auth, sempre responde;</li>
 *   <li>{@code auth{ticket}} → {@code auth_ok} ou {@code erro{TICKET_INVALIDO, motivo}} + {@code close 1008}; uma conexão
 *       autenticada por Origin ({@code OCUPADO}); prazo para o auth contado do open;</li>
 *   <li>pós-auth: {@code listar_impressoras}, {@code selecionar_impressora}, {@code imprimir} (correlacionados por {@code id});
 *       antes do auth qualquer outro tipo → {@code erro{NAO_AUTENTICADO}} + {@code close 1008}.</li>
 * </ol>
 *
 * <p>Threads: a lib entrega {@code onMessage} num {@code WebSocketWorker} fixo por conexão (nº = CPUs) — <b>nada aqui pode
 * bloquear</b>: impressão e listagem vão para executores próprios e respondem por callback ({@link FilaImpressao}).
 * {@code onClose} pode vir de 3 threads → estado em {@link Sessao} (volatile) e mapa concorrente de autenticadas.</p>
 *
 * <p>Defesas da camada de transporte (adversarial F0): teto de frame {@value #MAX_FRAME_BYTES} (o default da lib aceita 2 GB e
 * pré-aloca o tamanho DECLARADO); frame binário → erro tipado; subida à prova de falha ({@link #iniciar}); sinal de morte
 * pós-start ({@link #falhaFatal()}) e auto-checagem ({@link #estaEscutando()}).</p>
 */
public class ServidorAgente extends WebSocketServer {

    private static final Logger log = LoggerFactory.getLogger(ServidorAgente.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Maior frame aceito (bytes). Plano: PDF ≤ 2 MiB em base64 dentro do JSON de {@code imprimir} (≈ 2,8 MB). */
    public static final int MAX_FRAME_BYTES = 4 * 1024 * 1024;
    /** Maior PDF aceito (bytes decodificados) — espelho de {@code AGENTE_PDF_MAX_BYTES} do PWA. */
    public static final int MAX_PDF_BYTES = 2 * 1024 * 1024;
    /** Base64 de 2 MiB (com padding). Checado ANTES de decodificar. */
    static final int MAX_BASE64_CHARS = 4 * ((MAX_PDF_BYTES + 2) / 3);
    private static final byte[] ASSINATURA_PDF = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    /** Prefixo do nome do job no spooler (o lojista vê na fila; o cups-pdf usa como nome do arquivo) — o id do PWA vai junto. */
    static final String NOME_JOB = "AgroEase cupom";

    /** O que o servidor precisa de fora (plano F3 D8/D11/D5). {@code verificador == null} = agente NÃO pareado: recusa todo auth. */
    public record Dependencias(InfoAgente info, VerificadorTicket verificador, PortaImpressao impressao, ConfiguracaoLocal config) {
        public Dependencias {
            Objects.requireNonNull(info, "info");
            Objects.requireNonNull(impressao, "impressao");
            Objects.requireNonNull(config, "config");
        }
    }

    /** Prazos e limites (defaults de produção; os testes encurtam). */
    public static final class Prazos {
        /** Do {@code open} até o {@code auth} (≥ 60 s do {@code hello_ok}: o PWA pode buscar release + ticket no cold start). */
        public Duration auth = Duration.ofSeconds(90);
        /** Conexão autenticada sem tráfego (o PWA fecha sozinho em 20 s; isto é o teto do lado do agente). */
        public Duration ociosoAutenticado = Duration.ofSeconds(120);
        /** Prazo interno de um job de impressão (o PWA espera 15 s e fecha o socket). */
        public Duration impressao = Duration.ofSeconds(12);
        /** Prazo interno de listar/selecionar impressora (o PWA espera 5 s; um spooler preso não pode virar silêncio). */
        public Duration listagem = Duration.ofSeconds(4);
        /** Detecção de peer morto pela lib (ping/pong próprios do WebSocket), em segundos. */
        public int conexaoPerdidaSegundos = 20;
        /** Conexões simultâneas (pré e pós-auth). Heap de 128 MB × frames de até 4 MiB. */
        public int tetoConexoes = 8;
    }

    private final Set<String> originsPermitidas;
    private final InfoAgente info;
    private final VerificadorTicket verificador;
    private final PortaImpressao impressao;
    private final ConfiguracaoLocal config;
    private final Prazos prazos;

    private final CountDownLatch iniciado = new CountDownLatch(1);
    private final CompletableFuture<Exception> morte = new CompletableFuture<>();
    private volatile PorteiroHandshake porteiro;
    /** Erro fatal de subida (ex.: porta ocupada) — a lib entrega via {@code onError(null, ex)} e nunca chama onStart. */
    private volatile Exception erroFatal;

    /** Conexões AUTENTICADAS por Origin (uma só por vez — §7.4-5). */
    private final ConcurrentMap<String, WebSocket> autenticadas = new ConcurrentHashMap<>();
    private final ScheduledExecutorService agenda;
    private final ExecutorService listagem;
    private final FilaImpressao fila;
    private volatile ScheduledFuture<?> zelador;

    /**
     * @param porta             0 = efêmera (testes); em produção, a porta fixa do agente
     * @param originsPermitidas Origins exatas do PWA (vêm do backend no pareamento)
     */
    public ServidorAgente(int porta, Set<String> originsPermitidas, Dependencias deps, Prazos prazos) {
        // bind exclusivo em loopback — nunca 0.0.0.0; draft explícito só pra impor o teto de frame
        super(new InetSocketAddress("127.0.0.1", porta), List.of(draftComTeto()));
        this.originsPermitidas = Set.copyOf(Objects.requireNonNull(originsPermitidas));
        Objects.requireNonNull(deps, "deps");
        this.info = deps.info();
        this.verificador = deps.verificador();
        this.impressao = deps.impressao();
        this.config = deps.config();
        this.prazos = Objects.requireNonNull(prazos, "prazos");
        this.agenda = Executors.newSingleThreadScheduledExecutor(daemon("agente-agenda"));
        // 1 thread + fila curta: um lookupPrintServices preso (CUPS remoto fora, spooler travado) não acumula pedidos sem fim
        this.listagem = new java.util.concurrent.ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(4), daemon("agente-listagem"));
        this.fila = new FilaImpressao(agenda, prazos.impressao);
        setReuseAddr(true);
        setConnectionLostTimeout(prazos.conexaoPerdidaSegundos);
    }

    /** Host de DESENVOLVIMENTO sem pareamento: todo {@code auth} é recusado ({@code NAO_PAREADO}); impressão real; config em memória. */
    public ServidorAgente(int porta, Set<String> originsPermitidas, InfoAgente info) {
        this(porta, originsPermitidas, new Dependencias(info, null, PortaImpressao.real(), new ConfiguracaoLocalMemoria()), new Prazos());
    }

    /** RFC 6455 igual ao default da lib (sem extensões, subprotocolo vazio), só com {@link #MAX_FRAME_BYTES}. */
    private static Draft draftComTeto() {
        return new Draft_6455(List.of(), List.of(new Protocol("")), MAX_FRAME_BYTES);
    }

    private static ThreadFactory daemon(String nome) {
        return r -> {
            Thread t = new Thread(r, nome);
            t.setDaemon(true);
            return t;
        };
    }

    // ── ciclo de vida ──────────────────────────────────────────────────────────────────────────────────────────

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
            long periodo = Math.max(200, Math.min(5000, prazos.ociosoAutenticado.toMillis() / 4));
            zelador = agenda.scheduleWithFixedDelay(this::fecharOciosas, periodo, periodo, TimeUnit.MILLISECONDS);
            aoIniciar();
            log.info("Agente escutando em ws://127.0.0.1:{} (origins permitidas: {}; pareado: {})", getPort(), originsPermitidas,
                    verificador != null);
        } catch (RuntimeException e) {
            // A lib só protege IOException em volta do onStart: uma RuntimeException mataria a selector-thread
            // sem onError e o latch nunca zeraria (timeout com socket BOUND preso). Sinalizamos nós mesmos.
            erroFatal = e;
            log.error("Falha ao iniciar o agente: {}", e.toString());
        } finally {
            iniciado.countDown();
        }
    }

    /** Gancho de subida (bandeja/casca). Roda dentro do {@code onStart}, protegido. */
    protected void aoIniciar() {
    }

    @Override
    public void stop(int timeout, String closeMessage) throws InterruptedException {
        try {
            super.stop(timeout, closeMessage);
        } finally {
            encerrarRecursos();
        }
    }

    private void encerrarRecursos() {
        ScheduledFuture<?> z = zelador;
        if (z != null) {
            z.cancel(false);
        }
        fila.close();
        listagem.shutdownNow();
        agenda.shutdownNow();
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

    // ── handshake / abertura / fechamento ──────────────────────────────────────────────────────────────────────

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
        String origin = handshake.getFieldValue("Origin");
        Sessao sessao = new Sessao(origin, System.nanoTime());
        conn.setAttachment(sessao);
        if (getConnections().size() > prazos.tetoConexoes) {
            log.warn("Teto de {} conexões atingido; recusando a nova de Origin='{}'", prazos.tetoConexoes, origin);
            fechar(conn, CloseFrame.TRY_AGAIN_LATER, "LIMITE_CONEXOES");
            return;
        }
        // prazo do auth contado do open (≥ 60 s do hello_ok, que chega logo após o open)
        sessao.prazoAuth = agenda.schedule(() -> {
            if (!sessao.autenticada() && conn.isOpen()) {
                log.info("Conexão de Origin='{}' sem auth em {} — fechando", sessao.origin, prazos.auth);
                fechar(conn, CloseFrame.POLICY_VALIDATION, "AUTH_TIMEOUT");
            }
        }, prazos.auth.toMillis(), TimeUnit.MILLISECONDS);
        log.info("Conexão aberta de Origin='{}'", origin);
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        Sessao sessao = sessao(conn);
        if (sessao != null) {
            ScheduledFuture<?> t = sessao.prazoAuth;
            if (t != null) {
                t.cancel(false);
            }
            // só remove se ESTA conexão era a dona da vaga (outra pode ter assumido depois de uma conexão morta)
            autenticadas.remove(sessao.origin, conn);
        }
        log.info("Conexão fechada (code={}, remote={}, autenticada={}): {}", code, remote,
                sessao != null && sessao.autenticada(), reason);
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

    // ── mensagens ──────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    public void onMessage(WebSocket conn, String message) {
        Sessao sessao = sessao(conn);
        if (sessao == null) {
            return; // fechada antes de anexar (teto de conexões)
        }
        sessao.tocar(System.nanoTime());
        JsonNode msg;
        try {
            msg = JSON.readTree(message);
        } catch (Exception e) {
            enviar(conn, Mensagens.erro(null, Mensagens.MENSAGEM_INVALIDA, "JSON inválido: " + e.getMessage()));
            return;
        }
        if (msg == null || !msg.isObject()) {
            enviar(conn, Mensagens.erro(null, Mensagens.MENSAGEM_INVALIDA, "A mensagem deve ser um objeto JSON"));
            return;
        }
        String tipo = msg.path("tipo").asText("");
        String id = null;
        if (msg.has("id")) {
            String bruto = msg.get("id").isTextual() ? msg.get("id").asText() : null;
            if (!Mensagens.idValido(bruto)) {
                enviar(conn, Mensagens.erro(null, Mensagens.MENSAGEM_INVALIDA, "id de correlação inválido"));
                return;
            }
            id = bruto;
        }
        try {
            switch (tipo) {
                case "hello" -> {
                    sessao.helloRecebido = true;
                    enviar(conn, Mensagens.helloOk(info));
                }
                case "ping" -> enviar(conn, Mensagens.pong());
                case "auth" -> autenticar(conn, sessao, msg);
                default -> {
                    if (!sessao.autenticada()) {
                        // §7.4-1: antes do ticket, nada além de hello/ping/auth — nem "tipo desconhecido" vaza
                        enviar(conn, Mensagens.erro(id, Mensagens.NAO_AUTENTICADO, "Autentique com o ticket antes de usar o agente"));
                        fechar(conn, CloseFrame.POLICY_VALIDATION, Mensagens.NAO_AUTENTICADO);
                        return;
                    }
                    switch (tipo) {
                        case "listar_impressoras" -> listarImpressoras(conn, id);
                        case "selecionar_impressora" -> selecionarImpressora(conn, sessao, id, msg);
                        case "imprimir" -> imprimir(conn, sessao, id, msg);
                        default -> enviar(conn, Mensagens.erro(id, Mensagens.TIPO_DESCONHECIDO, "Tipo de mensagem não reconhecido: " + tipo));
                    }
                }
            }
        } catch (RuntimeException e) {
            // uma exceção que escapasse seria engolida pelo worker da lib e o PWA ficaria sem resposta até o timeout
            log.error("Falha ao tratar '{}' de Origin='{}': {}", tipo, sessao.origin, e.toString(), e);
            enviar(conn, Mensagens.erro(id, Mensagens.ERRO, "Falha interna do agente ao tratar a mensagem"));
        }
    }

    /** O protocolo é texto/JSON; o default da lib engoliria o binário em silêncio e o cliente ficaria esperando. */
    @Override
    public void onMessage(WebSocket conn, ByteBuffer message) {
        enviar(conn, Mensagens.erro(null, Mensagens.TIPO_BINARIO_NAO_SUPORTADO,
                "O agente só aceita mensagens de texto JSON (" + message.remaining() + " bytes binários ignorados)"));
    }

    // ── auth (3ª barreira) ─────────────────────────────────────────────────────────────────────────────────────

    private void autenticar(WebSocket conn, Sessao sessao, JsonNode msg) {
        if (sessao.autenticada()) {
            enviar(conn, Mensagens.authOk()); // idempotente: não gasta outro ticket
            return;
        }
        if (verificador == null) {
            enviar(conn, Mensagens.erro(null, Mensagens.NAO_PAREADO, "Este computador ainda não foi pareado com a loja"));
            fechar(conn, CloseFrame.POLICY_VALIDATION, Mensagens.NAO_PAREADO);
            return;
        }
        // 1º RESERVA a vaga por Origin de forma atômica (duas abas autenticando juntas): quem perde recebe OCUPADO
        // SEM ter gastado o ticket (§7.4-5 / D5). Se o ticket falhar, a reserva é desfeita.
        WebSocket dona = autenticadas.compute(sessao.origin, (k, atual) -> (atual == null || !atual.isOpen()) ? conn : atual);
        if (dona != conn) {
            recusarOcupado(conn, sessao);
            return;
        }
        String ticket = msg.path("ticket").isTextual() ? msg.get("ticket").asText() : null;
        TicketClaims claims;
        try {
            claims = verificador.verificar(ticket);
        } catch (TicketInvalidoException e) {
            autenticadas.remove(sessao.origin, conn);
            log.warn("Ticket recusado de Origin='{}': {}{}", sessao.origin, e.motivo(), dicaDeRelogio(e.motivo()));
            enviar(conn, Mensagens.erro(null, Mensagens.TICKET_INVALIDO, "Ticket recusado: " + e.motivo() + dicaDeRelogio(e.motivo()), e.motivo().name()));
            fechar(conn, CloseFrame.POLICY_VALIDATION, Mensagens.TICKET_INVALIDO);
            return;
        }
        sessao.jti = claims.jti();
        sessao.fase = Sessao.Fase.AUTENTICADA;
        ScheduledFuture<?> t = sessao.prazoAuth;
        if (t != null) {
            t.cancel(false);
        }
        log.info("Conexão autenticada: Origin='{}' loja={} jti={}", sessao.origin, claims.lojaId(), claims.jti());
        enviar(conn, Mensagens.authOk());
    }

    private void recusarOcupado(WebSocket conn, Sessao sessao) {
        log.info("Origin='{}' já tem uma conexão autenticada; recusando a nova (OCUPADO)", sessao.origin);
        enviar(conn, Mensagens.erro(null, Mensagens.OCUPADO, "Outra aba deste computador já está usando o agente"));
        fechar(conn, CloseFrame.POLICY_VALIDATION, Mensagens.OCUPADO);
    }

    // ── impressoras ────────────────────────────────────────────────────────────────────────────────────────────

    private void listarImpressoras(WebSocket conn, String id) {
        if (id == null) {
            enviar(conn, Mensagens.erro(null, Mensagens.MENSAGEM_INVALIDA, "listar_impressoras exige id"));
            return;
        }
        comPrazoDeListagem(conn, id, "listar as impressoras", () -> {
            List<String> nomes = impressao.listar();
            java.util.Optional<String> sel = config.impressoraSelecionada();
            // seleção antiga que sumiu da máquina não é oferecida (o PWA a usaria e falharia); lista VAZIA (spooler fora) mantém a
            // selecionada — o PWA confia nela nesse caso e o motor devolve IMPRESSORA_INDISPONIVEL, a mensagem certa (adversarial A2)
            String selecionada = nomes.isEmpty() ? sel.orElse(null) : sel.filter(nomes::contains).orElse(null);
            return Mensagens.impressoras(id, nomes, selecionada);
        });
    }

    /**
     * Roda uma tarefa de listagem no executor próprio com PRAZO (adversarial A3/C2): o PWA espera 5 s; sem prazo um spooler
     * preso virava silêncio + reconexões enfileiradas atrás dele. Exatamente uma resposta: o frame da tarefa OU o erro do prazo.
     */
    private void comPrazoDeListagem(WebSocket conn, String id, String oQue, java.util.concurrent.Callable<String> tarefa) {
        java.util.concurrent.atomic.AtomicBoolean respondido = new java.util.concurrent.atomic.AtomicBoolean(false);
        try {
            listagem.execute(() -> {
                String frame;
                try {
                    frame = tarefa.call();
                } catch (Exception | Error e) {
                    log.error("Falha ao {}: {}", oQue, e.toString());
                    frame = Mensagens.erro(id, Mensagens.ERRO, "Não foi possível " + oQue + " deste computador");
                }
                if (respondido.compareAndSet(false, true)) {
                    enviar(conn, frame);
                } else {
                    log.warn("{} concluiu depois do prazo de {}", oQue, prazos.listagem);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException cheia) {
            enviar(conn, Mensagens.erro(id, Mensagens.ERRO, "O agente está ocupado consultando as impressoras. Tente de novo em instantes."));
            return;
        }
        agenda.schedule(() -> {
            if (respondido.compareAndSet(false, true)) {
                log.warn("{} não respondeu em {} (spooler/CUPS preso?)", oQue, prazos.listagem);
                enviar(conn, Mensagens.erro(id, Mensagens.ERRO, "As impressoras deste computador não responderam a tempo. Confira o serviço de impressão do sistema."));
            }
        }, prazos.listagem.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void selecionarImpressora(WebSocket conn, Sessao sessao, String id, JsonNode msg) {
        if (id == null) {
            enviar(conn, Mensagens.erro(null, Mensagens.MENSAGEM_INVALIDA, "selecionar_impressora exige id"));
            return;
        }
        String nome = msg.path("nome").isTextual() ? msg.get("nome").asText().trim() : "";
        if (nome.isEmpty()) {
            enviar(conn, Mensagens.erro(id, Mensagens.MENSAGEM_INVALIDA, "selecionar_impressora exige nome"));
            return;
        }
        comPrazoDeListagem(conn, id, "selecionar a impressora", () -> {
            if (!impressao.listar().contains(nome)) {
                return Mensagens.erro(id, Mensagens.IMPRESSORA_INDISPONIVEL, "Impressora '" + nome + "' não encontrada neste computador");
            }
            config.impressoraSelecionada(nome);
            log.info("Impressora deste computador selecionada por Origin='{}': {}", sessao.origin, nome);
            return Mensagens.selecionarImpressoraOk(id, nome);
        });
    }

    // ── imprimir ───────────────────────────────────────────────────────────────────────────────────────────────

    private void imprimir(WebSocket conn, Sessao sessao, String id, JsonNode msg) {
        if (id == null) {
            enviar(conn, Mensagens.erro(null, Mensagens.MENSAGEM_INVALIDA, "imprimir exige id"));
            return;
        }
        if (!"pdf".equals(msg.path("formato").asText(""))) {
            enviar(conn, Mensagens.erro(id, Mensagens.MENSAGEM_INVALIDA, "formato deve ser 'pdf'"));
            return;
        }
        JsonNode b64 = msg.get("bytesBase64");
        if (b64 == null || !b64.isTextual() || b64.asText().isEmpty()) {
            enviar(conn, Mensagens.erro(id, Mensagens.MENSAGEM_INVALIDA, "bytesBase64 ausente"));
            return;
        }
        String texto = b64.asText();
        if (texto.length() > MAX_BASE64_CHARS) {
            enviar(conn, Mensagens.erro(id, Mensagens.MENSAGEM_INVALIDA, "PDF acima de 2 MiB"));
            return;
        }
        byte[] pdf;
        try {
            pdf = Base64.getDecoder().decode(texto); // alfabeto/padding do btoa() do navegador
        } catch (IllegalArgumentException e) {
            enviar(conn, Mensagens.erro(id, Mensagens.MENSAGEM_INVALIDA, "bytesBase64 não é base64 válido"));
            return;
        }
        if (pdf.length > MAX_PDF_BYTES) {
            enviar(conn, Mensagens.erro(id, Mensagens.MENSAGEM_INVALIDA, "PDF acima de 2 MiB"));
            return;
        }
        if (pdf.length < ASSINATURA_PDF.length
                || !Arrays.equals(pdf, 0, ASSINATURA_PDF.length, ASSINATURA_PDF, 0, ASSINATURA_PDF.length)) {
            enviar(conn, Mensagens.erro(id, Mensagens.MENSAGEM_INVALIDA, "conteúdo não é um PDF"));
            return;
        }
        String pedida = msg.path("impressora").isTextual() ? msg.get("impressora").asText().trim() : "";
        String impressora = !pedida.isEmpty() ? pedida : config.impressoraSelecionada().orElse(null);
        if (impressora == null) {
            enviar(conn, Mensagens.imprimirErro(id, Mensagens.IMPRESSORA_INDISPONIVEL,
                    "Nenhuma impressora escolhida neste computador. Escolha uma em Configurações → Geral → Impressão de Cupom."));
            return;
        }
        String descricao = "id=" + id + " origin=" + sessao.origin + " jti=" + sessao.jti + " impressora='" + impressora
                + "' bytes=" + pdf.length;
        if (fila.motorPreso()) {
            log.error("Motor de impressão TRAVADO (job há mais de {} × {}); recusando {}", FilaImpressao.FATOR_MOTOR_PRESO, prazos.impressao, descricao);
            enviar(conn, Mensagens.imprimirErro(id, Mensagens.ERRO,
                    "O serviço de impressão deste computador travou. Reinicie o agente (e a impressora) e tente de novo."));
            return;
        }
        log.info("Impressão pedida: {}", descricao);
        String nomeJob = NOME_JOB + " " + id;
        fila.submeter(descricao, () -> impressao.imprimir(pdf, impressora, nomeJob), new FilaImpressao.Resposta() {
            @Override
            public void concluido(Resultado r) {
                log.info("Impressão {} → {} ({})", descricao, r.estado(), r.detalhe());
                switch (r.estado()) {
                    case ACEITO_SPOOLER -> enviar(conn, Mensagens.imprimirOk(id));
                    case IMPRESSORA_INDISPONIVEL -> enviar(conn, Mensagens.imprimirErro(id, Mensagens.IMPRESSORA_INDISPONIVEL,
                            "Impressora '" + impressora + "' não encontrada neste computador. Confira se está ligada e instalada."));
                    default -> enviar(conn, Mensagens.imprimirErro(id, Mensagens.ERRO,
                            "Não foi possível enviar o cupom para '" + impressora + "'. Confira a impressora e tente de novo."));
                }
            }

            @Override
            public void prazoEstourado() {
                enviar(conn, Mensagens.imprimirErro(id, Mensagens.ERRO,
                        "A impressora '" + impressora + "' não respondeu em " + prazos.impressao.toSeconds()
                                + " s. Confira a impressora antes de reimprimir (o cupom pode sair atrasado)."));
            }

            @Override
            public void filaCheia() {
                log.warn("Fila de impressão cheia; recusando {}", descricao);
                enviar(conn, Mensagens.imprimirErro(id, Mensagens.ERRO,
                        "O agente está ocupado com outra impressão. Tente de novo em instantes."));
            }
        });
    }

    // ── zelador / utilitários ──────────────────────────────────────────────────────────────────────────────────

    private void fecharOciosas() {
        long agora = System.nanoTime();
        long teto = prazos.ociosoAutenticado.toNanos();
        for (WebSocket c : getConnections()) {
            Sessao s = sessao(c);
            if (s != null && s.autenticada() && agora - s.ultimaAtividadeNanos > teto && c.isOpen()) {
                log.info("Conexão autenticada de Origin='{}' ociosa há mais de {} — fechando", s.origin, prazos.ociosoAutenticado);
                fechar(c, CloseFrame.NORMAL, "ocioso");
            }
        }
    }

    private static Sessao sessao(WebSocket conn) {
        Object a = conn.getAttachment();
        return a instanceof Sessao s ? s : null;
    }

    /** {@code send} depois que o peer fechou lança — e uma exceção aqui derrubaria o worker da lib. */
    private static void enviar(WebSocket conn, String frame) {
        try {
            conn.send(frame);
        } catch (WebsocketNotConnectedException e) {
            log.debug("Frame descartado: conexão já fechada");
        }
    }

    /**
     * EXPIRADO (relógio adiantado ≥ 10 min) e VALIDADE_ABSURDA (relógio atrasado ≥ 50 min, pois {@code exp − agora > 1 h}) são,
     * na prática, data/hora errada no caixa — o ticket é emitido pelo servidor segundos antes. O verificador é contrato
     * compartilhado com o backend (não se mexe aqui); a dica vai no log e no {@code erro} para o PWA (adversarial F3 L3-A2).
     */
    static String dicaDeRelogio(TicketInvalidoException.Motivo motivo) {
        return switch (motivo) {
            case EXPIRADO, VALIDADE_ABSURDA -> " (confira a data/hora deste computador; use --diagnostico)";
            default -> "";
        };
    }

    private static void fechar(WebSocket conn, int codigo, String reason) {
        try {
            conn.close(codigo, reason);
        } catch (RuntimeException e) {
            log.debug("close() em conexão já fechada: {}", e.toString());
        }
    }
}

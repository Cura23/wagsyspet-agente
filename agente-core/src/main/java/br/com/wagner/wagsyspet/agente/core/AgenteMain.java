package br.com.wagner.wagsyspet.agente.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import br.com.wagner.wagsyspet.agente.protocolo.ProtocoloVersao;

import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Ponto de entrada de DESENVOLVIMENTO do agente (spike F0): sobe o {@link ServidorAgente} numa porta fixa com a
 * allowlist de Origins e fica escutando até Ctrl+C. Serve pra conectar o PWA de verdade e capturar o prompt de
 * Local Network Access do navegador.
 *
 * <p>Configuração por propriedades de sistema (defaults casam com o {@code cors.allowed-origins} do backend):</p>
 * <pre>
 *   -Dagente.porta=28421
 *   -Dagente.origins=https://wagsyspet-frontend.vercel.app,http://localhost:5173
 *   -Dagente.versao=1.0.0-dev        (obrigatória fora do jar — sem MANIFEST não há versão; nunca "dev")
 *   -Dagente.id=dev-sem-pareamento   (até o L2 ligar o pareamento, o agenteId anunciado vem daqui)
 * </pre>
 * O binário instalado entra por {@code agente-app/Main}, que monta o {@link InfoAgente} a partir do MANIFEST e chama
 * {@link #main(String[], InfoAgente)}; a leitura de config vai pro arquivo do agente (L2), não pra -D.
 */
public final class AgenteMain {

    private static final Logger log = LoggerFactory.getLogger(AgenteMain.class);

    /** Porta padrão do agente (plano §6: fixa, alta, fora das faixas comuns). */
    public static final int PORTA_PADRAO = 28421;
    public static final String ORIGINS_PADRAO = "https://wagsyspet-frontend.vercel.app,http://localhost:5173";
    /** agenteId de desenvolvimento enquanto o pareamento (L2) não fornece o real. */
    public static final String AGENTE_ID_DEV = "dev-sem-pareamento";

    private AgenteMain() {
    }

    /** Intervalo da auto-checagem de escuta (a lib pode fechar o LISTEN em silêncio — ver {@link ServidorAgente}). */
    static final Duration INTERVALO_WATCHDOG = Duration.ofSeconds(30);
    /** Código de saída quando o servidor morre depois de subir: o supervisor do SO (F3) reinicia o processo. */
    static final int SAIDA_SERVIDOR_MORTO = 3;

    /** Entrada de DEV ({@code exec:java}): versão por {@code -Dagente.versao} (sem MANIFEST), agenteId por {@code -Dagente.id}. */
    public static void main(String[] args) throws Exception {
        String versao = VersaoDoBinario.doClasspath(AgenteMain.class);
        main(args, new InfoAgente(versao, ProtocoloVersao.ATUAL, System.getProperty("agente.id", AGENTE_ID_DEV)));
    }

    /** Host de dev SEM pareamento (todo auth recusado): Origins e porta por -D. */
    public static void main(String[] args, InfoAgente info) throws Exception {
        int porta = Integer.getInteger("agente.porta", PORTA_PADRAO);
        Set<String> origins = parseOrigins(System.getProperty("agente.origins", ORIGINS_PADRAO));
        rodar(new ServidorAgente(porta, origins, info), info);
    }

    /**
     * Sobe o servidor já montado (dev ou produção via {@link MontadorServidor}) e segura o processo: shutdown hook,
     * watchdog de escuta e saída {@value #SAIDA_SERVIDOR_MORTO} se o servidor morrer depois de subir (supervisor reinicia).
     * Só retorna lançando (porta ocupada) ou encerrando o processo.
     */
    public static void rodar(ServidorAgente servidor, InfoAgente info) throws Exception {
        servidor.iniciar(Duration.ofSeconds(10)); // porta ocupada → lança aqui, processo sai ≠ 0 (não fica zumbi)
        log.info("Agente de Impressão AgroEase {} pronto em ws://127.0.0.1:{} (agenteId={}). Ctrl+C para encerrar.",
                info.versao(), servidor.getPort(), info.agenteId());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                servidor.stop(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "agente-shutdown"));

        vigiar(servidor);

        // Em vez de join() eterno: acorda quando a lib sinalizar erro fatal OU o watchdog vir a porta fechada.
        Exception causa = servidor.falhaFatal().get();
        log.error("Servidor do agente morreu depois de subir ({}); encerrando com código {} para o supervisor reiniciar.",
                causa, SAIDA_SERVIDOR_MORTO);
        System.exit(SAIDA_SERVIDOR_MORTO);
    }

    /**
     * Prova periódica, de fora, de que a porta ainda aceita conexão. Cobre o caso em que a lib fecha o canal de
     * LISTEN em silêncio (IOException no accept, ex.: sem file descriptors) e o processo segue "vivo" sem escutar.
     */
    public static void vigiar(ServidorAgente servidor) {
        Thread t = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(INTERVALO_WATCHDOG.toMillis());
                } catch (InterruptedException e) {
                    return;
                }
                if (servidor.falhaFatal().isDone()) {
                    return;
                }
                if (!servidor.estaEscutando()) {
                    servidor.falhaFatal().complete(new IOException(
                            "watchdog: 127.0.0.1:" + servidor.getPort() + " parou de aceitar conexões"));
                    return;
                }
            }
        }, "agente-watchdog");
        t.setDaemon(true);
        t.start();
    }

    static Set<String> parseOrigins(String csv) {
        Set<String> out = new LinkedHashSet<>();
        Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .forEach(out::add);
        if (out.isEmpty()) {
            throw new IllegalArgumentException("agente.origins vazio — o agente exige pelo menos uma Origin permitida");
        }
        return out;
    }
}

package br.com.wagner.wagsyspet.agente.impressao.spooler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Acompanhamento de um job na fila do Windows (plano F6 D9). Duas particularidades mandam no desenho:
 * <ol>
 *   <li>o JDK descarta o job id que o {@code StartDoc} devolve → o job é achado pelo NOME do documento, que o agente já faz único
 *       ({@code "AgroEase cupom <id do PWA>"});</li>
 *   <li>o Windows APAGA o job ao imprimir, e um cupom pequeno imprime em centenas de ms → quem olha a fila tem de estar olhando
 *       ANTES do {@code print()}: {@link #armar} abre a impressora e sobe uma thread que fotografa a fila a cada
 *       {@value #CADENCIA_RAPIDA_MS} ms nos primeiros segundos (o job existe durante todo o spool, então é visto) e depois a cada
 *       {@value #CADENCIA_LENTA_MS} ms. O estado final sai do {@link HistoricoJobWindows}.</li>
 * </ol>
 * A fila real é lida por JNA ({@code winspool.drv}); qualquer falha de biblioteca nativa vira "sem acompanhamento" — a impressão
 * NUNCA depende disto.
 */
public final class AcompanhamentoWindows implements AcompanhamentoSpooler {

    private static final Logger log = LoggerFactory.getLogger(AcompanhamentoWindows.class);
    static final long CADENCIA_RAPIDA_MS = 40;
    static final long CADENCIA_LENTA_MS = 500;
    static final Duration FASE_RAPIDA = Duration.ofSeconds(5);
    /** Teto de vida da thread se ninguém fechar (o observador fecha ao fim da janela). */
    static final Duration VIDA_MAXIMA = Duration.ofMinutes(10);
    /** Prefixo mínimo para aceitar um nome de documento truncado pelo driver/spooler. */
    static final int PREFIXO_MINIMO = 24;

    public record JobNaFila(int id, String documento, int status) { }

    /** Fotos da fila de UMA impressora. */
    public interface FonteDaFila extends AutoCloseable {
        List<JobNaFila> jobs() throws IOException;

        /** {@code PRINTER_INFO_2.Status}; 0 se não deu para ler. */
        int statusImpressora();

        @Override
        void close();
    }

    private final String documento;
    private final FonteDaFila fonte;
    private final HistoricoJobWindows historico;
    private final AtomicBoolean fechado = new AtomicBoolean();
    private final AtomicBoolean fonteFechada = new AtomicBoolean();
    /** A thread que fotografa é a ÚNICA dona do handle da impressora (a doc do OpenPrinter: o handle NÃO é thread-safe). */
    private volatile Thread dona;
    private volatile int statusImpressora;

    AcompanhamentoWindows(String documento, FonteDaFila fonte) {
        this.documento = documento;
        this.fonte = fonte;
        this.historico = new HistoricoJobWindows(documento);
    }

    /**
     * Abre a fila e começa a olhar JÁ. Vazio fora do Windows, sem a biblioteca nativa, ou se a impressora não abrir — nunca lança.
     */
    public static Optional<AcompanhamentoSpooler> armar(String impressora, String documento) {
        if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            return Optional.empty();
        }
        try {
            AcompanhamentoWindows a = new AcompanhamentoWindows(documento, new FilaWindowsJna(impressora));
            Thread t = new Thread(a::vigiar, "agente-spooler-" + Integer.toHexString(documento.hashCode()));
            t.setDaemon(true);
            a.dona = t;
            t.start();
            return Optional.of(a);
        } catch (IOException | RuntimeException | LinkageError e) { // UnsatisfiedLinkError/NoClassDefFoundError: JNA indisponível
            log.warn("Sem acompanhamento do spooler para '{}': {}", impressora, e.toString());
            return Optional.empty();
        }
    }

    /**
     * Para o {@code --diagnostico}: o estado do job no spooler está disponível NESTE binário? No Windows carrega de verdade a
     * biblioteca nativa (JNA extrai a {@code jnidispatch.dll} do jar no 1º uso) e o {@code winspool.drv}; fora dele quem acompanha é o
     * {@code lpstat}.
     */
    public static String diagnostico() {
        if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            return AcompanhamentoCups.lpstatDisponivel() ? "disponível (CUPS: lpstat)" : "indisponível (/usr/bin/lpstat ausente — instale cups-client)";
        }
        try {
            com.sun.jna.platform.win32.Winspool.INSTANCE.hashCode(); // força o carregamento nativo
            return "disponível (Windows: winspool via JNA " + com.sun.jna.Native.VERSION + ")";
        } catch (RuntimeException | LinkageError e) {
            return "indisponível (" + e + ") — a impressão funciona; só não há aviso de cupom preso";
        }
    }

    private void vigiar() {
        long inicio = System.nanoTime();
        try {
            while (olhar()) {
                long vivo = System.nanoTime() - inicio;
                if (vivo > VIDA_MAXIMA.toNanos()) {
                    break;
                }
                Thread.sleep(vivo < FASE_RAPIDA.toNanos() ? CADENCIA_RAPIDA_MS : CADENCIA_LENTA_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            fechado.set(true);
            fecharFonte(); // só AQUI (na thread dona) o ClosePrinter acontece
        }
    }

    private void fecharFonte() {
        if (fonteFechada.compareAndSet(false, true)) {
            fonte.close();
        }
    }

    /** Uma foto da fila. @return {@code false} = não precisa (ou não pode) olhar mais */
    boolean olhar() {
        if (fechado.get()) {
            return false;
        }
        try {
            Optional<JobNaFila> meu = fonte.jobs().stream().filter(j -> ehMeu(j.documento())).findFirst();
            if (meu.isPresent()) {
                historico.visto(meu.get().id(), meu.get().status());
            } else {
                historico.ausente();
            }
        } catch (IOException | RuntimeException | LinkageError e) {
            historico.falhaDeConsulta(e.getMessage() == null ? e.toString() : e.getMessage());
            return true;
        }
        EstadoSpooler e = historico.estado(0);
        if (e.encerrado()) {
            close();
            return false;
        }
        try {
            statusImpressora = fonte.statusImpressora(); // só importa enquanto pendente
        } catch (RuntimeException | LinkageError ex) {
            statusImpressora = 0;
        }
        historico.statusDaImpressora(statusImpressora);
        return true;
    }

    private boolean ehMeu(String nomeNaFila) {
        if (nomeNaFila == null) {
            return false;
        }
        return nomeNaFila.equals(documento)
                || (nomeNaFila.length() >= PREFIXO_MINIMO && documento.startsWith(nomeNaFila));
    }

    @Override
    public EstadoSpooler consultar() {
        return historico.estado(statusImpressora);
    }

    @Override
    public void submetido() {
        historico.submetido();
    }

    /**
     * De QUALQUER thread: só sinaliza e acorda a dona — quem fecha o handle é ela (um ClosePrinter com EnumJobs em voo no mesmo handle,
     * vindo de outra thread, é uso concorrente que a API não permite). Sem thread dona (testes) fecha direto.
     */
    @Override
    public void close() {
        fechado.set(true);
        Thread t = dona;
        if (t == null || t == Thread.currentThread()) {
            fecharFonte();
        } else {
            t.interrupt();
        }
    }
}

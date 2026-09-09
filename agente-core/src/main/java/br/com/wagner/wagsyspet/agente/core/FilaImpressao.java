package br.com.wagner.wagsyspet.agente.core;

import br.com.wagner.wagsyspet.agente.impressao.ImpressoraJavaxPrint.Resultado;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fila de impressão do agente (plano F3 D9): <b>1 job por vez</b> numa thread própria, no máximo {@value #ESPERA} à espera,
 * e um <b>prazo interno</b> menor que os 15 s do PWA. A resposta é entregue por callback — nunca bloqueamos a thread da
 * conexão WebSocket (a lib fixa cada conexão num worker; um {@code print()} preso ali congelaria as outras abas).
 *
 * <p>Quando o prazo estoura respondemos {@code ERRO} ao PWA e o job segue rodando: se o spooler aceitar depois, o cupom
 * sai "tarde" (possível duplicata se o operador reimprimir) — por isso a mensagem pede para conferir a impressora antes
 * de reimprimir, e o resultado atrasado vai só para o log.</p>
 */
final class FilaImpressao implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(FilaImpressao.class);
    /** Jobs à espera além do que está rodando (o PWA serializa 1 por aba; 2 cobre duas abas/Origins). */
    static final int ESPERA = 2;

    /** Callbacks do protocolo — exatamente UM deles é chamado, uma vez. */
    interface Resposta {
        void concluido(Resultado r);

        void prazoEstourado();

        void filaCheia();
    }

    /** Job rodando há mais que {@code prazo × FATOR_MOTOR_PRESO} = motor de impressão travado (spooler/CUPS pendurado). */
    static final int FATOR_MOTOR_PRESO = 10;

    private final ThreadPoolExecutor executor;
    private final ScheduledExecutorService agenda;
    private final Duration prazo;
    /** Início (nanos) do job em execução, 0 quando ocioso — para {@link #motorPreso()}. */
    private volatile long executandoDesdeNanos;

    FilaImpressao(ScheduledExecutorService agenda, Duration prazo) {
        this.agenda = Objects.requireNonNull(agenda);
        this.prazo = Objects.requireNonNull(prazo);
        this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(ESPERA), r -> {
            Thread t = new Thread(r, "agente-impressao");
            t.setDaemon(true);
            return t;
        });
    }

    void submeter(String descricao, Callable<Resultado> job, Resposta resposta) {
        AtomicBoolean respondido = new AtomicBoolean(false);
        Runnable tarefa = () -> {
            Resultado r;
            executandoDesdeNanos = System.nanoTime();
            try {
                r = job.call();
            } catch (Exception | Error e) {
                log.error("Impressão {} lançou {}", descricao, e.toString());
                r = new Resultado(Resultado.Estado.ERRO, "", "exceção no motor: " + e);
            } finally {
                executandoDesdeNanos = 0;
            }
            if (respondido.compareAndSet(false, true)) {
                resposta.concluido(r);
            } else {
                log.warn("Impressão {} concluiu DEPOIS do prazo de {} ({} — {}); o PWA já recebeu ERRO — possível duplicata se reimprimir",
                        descricao, prazo, r.estado(), r.detalhe());
            }
        };
        try {
            executor.execute(tarefa);
        } catch (RejectedExecutionException e) {
            resposta.filaCheia();
            return;
        }
        agenda.schedule(() -> {
            if (respondido.compareAndSet(false, true)) {
                log.warn("Impressão {} não respondeu em {}", descricao, prazo);
                resposta.prazoEstourado();
            }
        }, prazo.toMillis(), TimeUnit.MILLISECONDS);
    }

    int emEspera() {
        return executor.getQueue().size();
    }

    /**
     * Motor travado: o job atual passou de {@code prazo × 10} (o {@code PrinterJob.print()} do Windows não é interrompível;
     * a única thread da fila fica presa e todo job seguinte cairia em "ocupado" para sempre). Quem chama avisa o operador com
     * a causa certa e o log registra — adversarial F3 C2.
     */
    boolean motorPreso() {
        long desde = executandoDesdeNanos;
        return desde != 0 && System.nanoTime() - desde > prazo.toNanos() * FATOR_MOTOR_PRESO;
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}

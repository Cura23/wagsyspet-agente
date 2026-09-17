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

        /**
         * O motor respondeu DEPOIS do prazo (o PWA já recebeu ERRO). F6-L4: deixa de ser só um log — se o spooler aceitou, o
         * observador acompanha e o PWA fica sabendo que o cupom saiu atrasado (não reimprimir).
         */
        default void concluidoTarde(Resultado r) {
        }
    }

    /**
     * Job em dois tempos (F6-L5): {@link #principal()} decide a resposta ao PWA; {@link #depois(Resultado)} roda logo em seguida na MESMA
     * thread (antes do próximo job — a ordem no spooler se mantém), mas DEPOIS de responder. É onde mora o corte do papel: ele não
     * pode atrasar o {@code imprimir_ok} de um cupom já aceito (gaveta + PDF + corte numa tarefa só estouravam o prazo — adversarial L5).
     */
    interface Job {
        Resultado principal() throws Exception;

        default void depois(Resultado principal) {
        }
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
        submeterEmDoisTempos(descricao, job::call, resposta);
    }

    /** Como {@link #submeter}, com o pós-resposta do {@link Job} (nome próprio: uma lambda serviria às duas sobrecargas). */
    void submeterEmDoisTempos(String descricao, Job job, Resposta resposta) {
        AtomicBoolean respondido = new AtomicBoolean(false);
        Runnable tarefa = () -> {
            executandoDesdeNanos = System.nanoTime();
            try {
                executar(descricao, job, resposta, respondido);
            } finally {
                executandoDesdeNanos = 0;
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

    private void executar(String descricao, Job job, Resposta resposta, AtomicBoolean respondido) {
        Resultado r;
        try {
            r = job.principal();
        } catch (Exception | Error e) {
            log.error("Impressão {} lançou {}", descricao, e.toString());
            r = new Resultado(Resultado.Estado.ERRO, "", "exceção no motor: " + e);
        }
        if (respondido.compareAndSet(false, true)) {
            resposta.concluido(r);
        } else {
            log.warn("Impressão {} concluiu DEPOIS do prazo de {} ({} — {}); o PWA já recebeu ERRO — possível duplicata se reimprimir",
                    descricao, prazo, r.estado(), r.detalhe());
            try {
                resposta.concluidoTarde(r);
            } catch (RuntimeException e) {
                log.warn("concluidoTarde de {} falhou: {}", descricao, e.toString());
            }
        }
        try {
            job.depois(r);
        } catch (RuntimeException | Error e) {
            log.warn("Pós-impressão de {} falhou: {}", descricao, e.toString());
        }
    }

    /** Há um job dentro do motor agora. */
    boolean executando() {
        return executandoDesdeNanos != 0;
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

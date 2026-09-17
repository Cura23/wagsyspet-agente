package br.com.wagner.wagsyspet.agente.core;

import br.com.wagner.wagsyspet.agente.impressao.spooler.AcompanhamentoSpooler;
import br.com.wagner.wagsyspet.agente.impressao.spooler.EstadoSpooler;
import br.com.wagner.wagsyspet.agente.impressao.spooler.EstadoSpooler.Estado;
import br.com.wagner.wagsyspet.agente.impressao.spooler.EstadoSpooler.Motivo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Observa no spooler do SO os jobs já aceitos (plano F6 D9) — FORA da {@link FilaImpressao} (1 thread: um {@code lpstat} lento faria a
 * próxima venda cair em "ocupado") e FORA da {@code agenda} do servidor (que serve todos os prazos). Executor próprio de
 * {@value #THREADS} threads; cada consulta é UMA tarefa reagendada (nunca um laço segurando thread), então uma consulta pendurada
 * não para as outras.
 * <ul>
 *   <li>janela de observação: ao fim dela, o estado corrente (tipicamente PENDENTE com o motivo) vira a resposta final ENCERRADA;</li>
 *   <li>UM aviso por id, quando encerra (o servidor empurra {@code impressao_estado} se a conexão ainda estiver aberta);</li>
 *   <li>memória por id (LRU {@value #MEMORIA_MAXIMA} / {@code VALIDADE}) para o {@code consultar_impressao} — o PWA fecha o socket
 *       ocioso em 20 s e pode perguntar depois por outra conexão. Não persiste: é por processo;</li>
 *   <li>teto de {@value #TETO_OBSERVACOES} observações simultâneas: a excedente encerra na hora como DESCONHECIDO — observar nunca
 *       bloqueia nem atrasa quem imprime.</li>
 * </ul>
 */
final class ObservadorImpressao implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ObservadorImpressao.class);
    static final int THREADS = 2;
    static final int TETO_OBSERVACOES = 4;
    static final int MEMORIA_MAXIMA = 200;
    static final Duration VALIDADE = Duration.ofMinutes(15);
    /** Depois dos primeiros segundos a cadência relaxa (o que importa cedo é o cupom rápido; depois, só "ainda está preso?"). */
    static final Duration FASE_RAPIDA = Duration.ofSeconds(5);
    static final int FATOR_CADENCIA_LENTA = 4;

    private record Guardado(EstadoSpooler estado, long quandoNanos) { }

    private final Duration janela;
    private final Duration cadencia;
    private final ScheduledThreadPoolExecutor executor;
    private final AtomicInteger ativas = new AtomicInteger();
    private final Set<AcompanhamentoSpooler> abertos = ConcurrentHashMap.newKeySet();
    private final Map<String, Guardado> memoria = new LinkedHashMap<>(64, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Guardado> e) {
            return size() > MEMORIA_MAXIMA;
        }
    };

    ObservadorImpressao(Duration janela, Duration cadencia) {
        this.janela = Objects.requireNonNull(janela);
        this.cadencia = Objects.requireNonNull(cadencia);
        AtomicInteger n = new AtomicInteger();
        this.executor = new ScheduledThreadPoolExecutor(THREADS, r -> {
            Thread t = new Thread(r, "agente-observador-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        this.executor.setRemoveOnCancelPolicy(true);
    }

    /** Começa a observar; nunca bloqueia. {@code aoEncerrar} é chamado UMA vez, numa thread do observador. */
    void observar(String id, AcompanhamentoSpooler acompanhamento, Consumer<EstadoSpooler> aoEncerrar) {
        if (ativas.incrementAndGet() > TETO_OBSERVACOES) {
            ativas.decrementAndGet();
            log.warn("Teto de {} observações simultâneas; {} fica sem acompanhamento", TETO_OBSERVACOES, id);
            fecharSilencioso(acompanhamento);
            encerrar(id, EstadoSpooler.desconhecido(Motivo.CONSULTA_INDISPONIVEL, "muitas impressões em observação ao mesmo tempo"), aoEncerrar);
            return;
        }
        abertos.add(acompanhamento);
        guardar(id, EstadoSpooler.pendente(null, "aceito pelo spooler; observando"));
        Observacao o = new Observacao(id, acompanhamento, aoEncerrar);
        try {
            executor.execute(o);
        } catch (RejectedExecutionException e) { // observador fechado
            o.terminar(EstadoSpooler.desconhecido(Motivo.CONSULTA_INDISPONIVEL, "agente encerrando"));
        }
    }

    /**
     * O servidor conhece o id desde a SUBMISSÃO (adversarial L4): antes de o motor responder o pull tem de dizer "em envio, não encerrado"
     * — responder "sem registro, encerrado" e depois mudar de ideia liberava a reimpressão de um cupom que ainda ia sair.
     */
    void registrar(String id, EstadoSpooler estado) {
        guardar(id, estado);
    }

    /** O motor aceitou o job mas não sabe acompanhar (ex.: JNA indisponível): o pull responde isso, sem aviso. */
    EstadoSpooler semAcompanhamento(String id, String detalhe) {
        EstadoSpooler e = EstadoSpooler.desconhecido(Motivo.SEM_SUPORTE, detalhe);
        guardar(id, e);
        return e;
    }

    /** Estado corrente do id; nunca observado/expirado → DESCONHECIDO{SEM_REGISTRO}. */
    EstadoSpooler consultar(String id) {
        synchronized (memoria) {
            Guardado g = memoria.get(id);
            if (g != null && System.nanoTime() - g.quandoNanos() <= VALIDADE.toNanos()) {
                return g.estado();
            }
            memoria.remove(id);
        }
        return EstadoSpooler.desconhecido(Motivo.SEM_REGISTRO, "sem registro desta impressão (agente reiniciou ou já faz mais de " + VALIDADE.toMinutes() + " min)");
    }

    private void guardar(String id, EstadoSpooler e) {
        synchronized (memoria) {
            memoria.remove(id); // reinserir = mais novo (a ordem é de inserção)
            memoria.put(id, new Guardado(e, System.nanoTime()));
        }
    }

    private void encerrar(String id, EstadoSpooler e, Consumer<EstadoSpooler> aoEncerrar) {
        EstadoSpooler fim = e.encerrar();
        guardar(id, fim);
        log.info("Impressão {} → estado do spooler: {}{} ({})", id, fim.estado(), fim.motivo() == null ? "" : "{" + fim.motivo() + "}", fim.detalhe());
        try {
            aoEncerrar.accept(fim);
        } catch (RuntimeException ex) {
            log.warn("Aviso do estado de {} falhou: {}", id, ex.toString());
        }
    }

    private static void fecharSilencioso(AcompanhamentoSpooler a) {
        try {
            a.close();
        } catch (RuntimeException e) {
            log.debug("close do acompanhamento: {}", e.toString());
        }
    }

    private final class Observacao implements Runnable {
        private final String id;
        private final AcompanhamentoSpooler acompanhamento;
        private final Consumer<EstadoSpooler> aoEncerrar;
        private final long inicio = System.nanoTime();
        private final AtomicBoolean terminada = new AtomicBoolean();
        private volatile EstadoSpooler ultimo = EstadoSpooler.pendente(null, "observando o spooler");

        Observacao(String id, AcompanhamentoSpooler acompanhamento, Consumer<EstadoSpooler> aoEncerrar) {
            this.id = id;
            this.acompanhamento = acompanhamento;
            this.aoEncerrar = aoEncerrar;
        }

        @Override
        public void run() {
            if (terminada.get()) {
                return;
            }
            EstadoSpooler e;
            try {
                e = acompanhamento.consultar();
            } catch (RuntimeException | LinkageError ex) {
                e = new EstadoSpooler(Estado.DESCONHECIDO, Motivo.CONSULTA_INDISPONIVEL, "consulta ao spooler falhou: " + ex, false);
            }
            ultimo = e;
            long vivo = System.nanoTime() - inicio;
            if (e.encerrado() || vivo >= janela.toNanos()) {
                terminar(e);
                return;
            }
            guardar(id, e);
            long espera = vivo < FASE_RAPIDA.toNanos() ? cadencia.toMillis() : cadencia.toMillis() * FATOR_CADENCIA_LENTA;
            try {
                executor.schedule(this, Math.max(1, Math.min(espera, (janela.toNanos() - vivo) / 1_000_000 + 1)), TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException ex) {
                terminar(e);
            }
        }

        void terminar(EstadoSpooler e) {
            if (!terminada.compareAndSet(false, true)) {
                return;
            }
            ativas.decrementAndGet();
            abertos.remove(acompanhamento);
            fecharSilencioso(acompanhamento);
            encerrar(id, e, aoEncerrar);
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
        for (AcompanhamentoSpooler a : abertos) {
            fecharSilencioso(a);
        }
        abertos.clear();
    }
}

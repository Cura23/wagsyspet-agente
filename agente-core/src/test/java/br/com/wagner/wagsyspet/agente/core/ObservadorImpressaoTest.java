package br.com.wagner.wagsyspet.agente.core;

import br.com.wagner.wagsyspet.agente.impressao.spooler.AcompanhamentoSpooler;
import br.com.wagner.wagsyspet.agente.impressao.spooler.EstadoSpooler;
import br.com.wagner.wagsyspet.agente.impressao.spooler.EstadoSpooler.Estado;
import br.com.wagner.wagsyspet.agente.impressao.spooler.EstadoSpooler.Motivo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/** Plano F6 D9 — quem pergunta ao spooler vive FORA da fila de impressão e da agenda do servidor: executor próprio, janela, teto e memória por id. */
@DisplayName("ObservadorImpressao — janela de observação, UM aviso por id, teto de observações e memória para o pull")
class ObservadorImpressaoTest {

    private ObservadorImpressao observador;

    @AfterEach
    void fechar() {
        if (observador != null) {
            observador.close();
        }
    }

    private static final class Roteiro implements AcompanhamentoSpooler {
        final Supplier<EstadoSpooler> proximo;
        final AtomicInteger consultas = new AtomicInteger();
        final AtomicBoolean fechado = new AtomicBoolean();

        Roteiro(Supplier<EstadoSpooler> proximo) { this.proximo = proximo; }
        @Override public EstadoSpooler consultar() { consultas.incrementAndGet(); return proximo.get(); }
        @Override public void close() { fechado.set(true); }
    }

    private static EstadoSpooler esperar(List<EstadoSpooler> avisos) throws InterruptedException {
        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (avisos.isEmpty() && System.nanoTime() < limite) { Thread.sleep(10); }
        assertThat(avisos).isNotEmpty();
        return avisos.get(0);
    }

    @Test
    @DisplayName("spooler conclui dentro da janela → UM aviso IMPRESSO encerrado, acompanhamento fechado, estado guardado para o pull")
    void concluiDentroDaJanela() throws Exception {
        observador = new ObservadorImpressao(Duration.ofSeconds(5), Duration.ofMillis(20));
        AtomicInteger n = new AtomicInteger();
        Roteiro r = new Roteiro(() -> n.incrementAndGet() < 3 ? EstadoSpooler.pendente(null, "na fila") : EstadoSpooler.impresso("ok"));
        List<EstadoSpooler> avisos = new CopyOnWriteArrayList<>();
        observador.observar("j-1", r, avisos::add);
        assertThat(esperar(avisos).estado()).isEqualTo(Estado.IMPRESSO);
        Thread.sleep(100);
        assertThat(avisos).as("no máximo UM aviso por id").hasSize(1);
        assertThat(r.fechado).isTrue();
        assertThat(observador.consultar("j-1").estado()).isEqualTo(Estado.IMPRESSO);
        assertThat(observador.consultar("j-1").encerrado()).isTrue();
    }

    @Test
    @DisplayName("fim da janela ainda na fila → o PENDENTE (com o motivo corrente) vira a resposta final ENCERRADA — é o aviso que vale ao operador; durante a janela o pull devolve encerrado:false")
    void janelaEncerraPendente() throws Exception {
        observador = new ObservadorImpressao(Duration.ofMillis(400), Duration.ofMillis(20));
        Roteiro r = new Roteiro(() -> EstadoSpooler.pendente(Motivo.IMPRESSORA_OFFLINE, "desligada"));
        List<EstadoSpooler> avisos = new CopyOnWriteArrayList<>();
        observador.observar("j-2", r, avisos::add);
        Thread.sleep(120);
        EstadoSpooler durante = observador.consultar("j-2");
        assertThat(durante.estado()).isEqualTo(Estado.PENDENTE);
        assertThat(durante.encerrado()).isFalse();
        EstadoSpooler fim = esperar(avisos);
        assertThat(fim.estado()).isEqualTo(Estado.PENDENTE);
        assertThat(fim.motivo()).isEqualTo(Motivo.IMPRESSORA_OFFLINE);
        assertThat(fim.encerrado()).isTrue();
        assertThat(r.fechado).isTrue();
    }

    @Test
    @DisplayName("consulta que LANÇA não mata a observação (vira CONSULTA_INDISPONIVEL e segue); id nunca observado → DESCONHECIDO{SEM_REGISTRO}; job sem acompanhamento → DESCONHECIDO{SEM_SUPORTE} sem aviso")
    void excecaoESemRegistro() throws Exception {
        observador = new ObservadorImpressao(Duration.ofSeconds(5), Duration.ofMillis(20));
        AtomicInteger n = new AtomicInteger();
        Roteiro r = new Roteiro(() -> { if (n.incrementAndGet() < 3) { throw new IllegalStateException("lpstat explodiu"); } return EstadoSpooler.impresso("ok"); });
        List<EstadoSpooler> avisos = new CopyOnWriteArrayList<>();
        observador.observar("j-3", r, avisos::add);
        assertThat(esperar(avisos).estado()).isEqualTo(Estado.IMPRESSO);

        EstadoSpooler nunca = observador.consultar("nunca-visto");
        assertThat(nunca.estado()).isEqualTo(Estado.DESCONHECIDO);
        assertThat(nunca.motivo()).isEqualTo(Motivo.SEM_REGISTRO);
        assertThat(nunca.encerrado()).isTrue();

        observador.semAcompanhamento("j-4", "motor sem suporte");
        assertThat(observador.consultar("j-4").motivo()).isEqualTo(Motivo.SEM_SUPORTE);
    }

    @Test
    @DisplayName("teto de observações simultâneas: a excedente encerra NA HORA como DESCONHECIDO{CONSULTA_INDISPONIVEL} (nunca bloqueia quem submete) e o acompanhamento dela é fechado")
    void teto() throws Exception {
        observador = new ObservadorImpressao(Duration.ofSeconds(30), Duration.ofMillis(50));
        for (int i = 0; i < ObservadorImpressao.TETO_OBSERVACOES; i++) {
            observador.observar("preso-" + i, new Roteiro(() -> EstadoSpooler.pendente(null, "na fila")), e -> { });
        }
        Roteiro excedente = new Roteiro(() -> EstadoSpooler.impresso("nunca consultado"));
        List<EstadoSpooler> avisos = new CopyOnWriteArrayList<>();
        long t0 = System.nanoTime();
        observador.observar("demais", excedente, avisos::add);
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofMillis(500));
        assertThat(esperar(avisos).motivo()).isEqualTo(Motivo.CONSULTA_INDISPONIVEL);
        assertThat(excedente.fechado).isTrue();
        assertThat(excedente.consultas.get()).isZero();
    }

    @Test
    @DisplayName("consulta LENTA (lpstat pendurado) não segura quem chama observar() nem a outra observação; close() cancela tudo e fecha os acompanhamentos")
    void lentaNaoBloqueiaEClose() throws Exception {
        observador = new ObservadorImpressao(Duration.ofSeconds(30), Duration.ofMillis(20));
        CountDownLatch solta = new CountDownLatch(1);
        Roteiro lenta = new Roteiro(() -> { try { solta.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } return EstadoSpooler.pendente(null, "x"); });
        long t0 = System.nanoTime();
        observador.observar("lenta", lenta, e -> { });
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofMillis(300));
        List<EstadoSpooler> avisos = new CopyOnWriteArrayList<>();
        observador.observar("rapida", new Roteiro(() -> EstadoSpooler.impresso("ok")), avisos::add);
        assertThat(esperar(avisos).estado()).isEqualTo(Estado.IMPRESSO);
        observador.close();
        solta.countDown();
        Thread.sleep(100);
        assertThat(lenta.fechado).isTrue();
    }

    @Test
    @DisplayName("memória por id é limitada (LRU): o mais antigo sai quando passa do teto")
    void lru() {
        observador = new ObservadorImpressao(Duration.ofSeconds(5), Duration.ofMillis(20));
        for (int i = 0; i <= ObservadorImpressao.MEMORIA_MAXIMA; i++) {
            observador.semAcompanhamento("id-" + i, "x");
        }
        assertThat(observador.consultar("id-0").motivo()).as("o mais antigo saiu").isEqualTo(Motivo.SEM_REGISTRO);
        assertThat(observador.consultar("id-" + ObservadorImpressao.MEMORIA_MAXIMA).motivo()).isEqualTo(Motivo.SEM_SUPORTE);
    }
}

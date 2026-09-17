package br.com.wagner.wagsyspet.agente.impressao.spooler;

import br.com.wagner.wagsyspet.agente.impressao.spooler.ClienteIppCups.Fila;
import br.com.wagner.wagsyspet.agente.impressao.spooler.ClienteIppCups.Job;
import br.com.wagner.wagsyspet.agente.impressao.spooler.EstadoSpooler.Estado;
import br.com.wagner.wagsyspet.agente.impressao.spooler.EstadoSpooler.Motivo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ClassificadorCups.porIpp — job-state do IPP (RFC 8011 §5.3.7) manda; os motivos só refinam")
class ClassificadorIppTest {

    private static final Optional<Fila> SEM_QUEIXA = Optional.of(new Fila(3, List.of("none"), ""));

    private static EstadoSpooler job(int estado, String... motivos) {
        return ClassificadorCups.porIpp("PDF-52", Optional.of(new Job(estado, List.of(motivos), "")), () -> SEM_QUEIXA);
    }

    @Test
    @DisplayName("9 completed → IMPRESSO com QUALQUER motivo — inclusive 'processing-to-stop-point', que é como o CUPS < 2.4.8 (Ubuntu 22.04/24.04, Debian 12, macOS) termina um job impresso com SUCESSO (Issue #832): pelo texto do lpstat isso viraria um falso 'falhou'")
    void completed() {
        assertThat(job(9, "job-completed-successfully").estado()).isEqualTo(Estado.IMPRESSO);
        EstadoSpooler antigo = job(9, "processing-to-stop-point");
        assertThat(antigo.estado()).isEqualTo(Estado.IMPRESSO);
        assertThat(antigo.encerrado()).isTrue();
        assertThat(antigo.detalhe()).contains("PDF-52").contains("completed");
    }

    @Test
    @DisplayName("7 canceled → FALHOU{CANCELADO} (mesmo com motivos 'none': cancelado antes de processar); 8 aborted → FALHOU{ABORTADO}; 6 stopped com erro de filtro/backend → FALHOU{ERRO_DRIVER} (fica na fila para sempre: NÃO vai sair sozinho); 6 sem erro / 4 held → PENDENTE{INTERVENCAO}")
    void terminaisEParados() {
        assertThat(job(7, "none").motivo()).isEqualTo(Motivo.CANCELADO);
        assertThat(job(7, "none").estado()).isEqualTo(Estado.FALHOU);
        assertThat(job(8, "aborted-by-system").motivo()).isEqualTo(Motivo.ABORTADO);
        EstadoSpooler filtro = job(6, "job-completed-with-errors");
        assertThat(filtro.estado()).isEqualTo(Estado.FALHOU);
        assertThat(filtro.motivo()).isEqualTo(Motivo.ERRO_DRIVER);
        assertThat(job(6, "cups-filter-crashed").motivo()).isEqualTo(Motivo.ERRO_DRIVER);
        assertThat(job(6, "job-stopped").estado()).isEqualTo(Estado.PENDENTE);
        assertThat(job(6, "job-stopped").motivo()).isEqualTo(Motivo.INTERVENCAO);
        assertThat(job(4, "job-hold-until-specified").motivo()).isEqualTo(Motivo.INTERVENCAO);
    }

    @Test
    @DisplayName("3 pending / 5 processing → PENDENTE não encerrado com o motivo da FILA: parada (estado 5 ou 'paused') → FILA_PARADA; media-empty → SEM_PAPEL; door/cover-open → TAMPA_ABERTA; offline/connecting-to-device ou a mensagem 'may not exist or is unavailable' (do job ou da fila) → IMPRESSORA_OFFLINE; nada → sem motivo; fila ilegível → sem motivo, sem lançar")
    void pendentes() {
        assertThat(pend(3, new Fila(5, List.of("paused"), "Paused"), "")).isEqualTo(Motivo.FILA_PARADA);
        assertThat(pend(3, new Fila(3, List.of("paused"), ""), "")).isEqualTo(Motivo.FILA_PARADA);
        assertThat(pend(5, new Fila(4, List.of("media-empty-warning"), ""), "")).isEqualTo(Motivo.SEM_PAPEL);
        assertThat(pend(5, new Fila(4, List.of("cover-open-report"), ""), "")).isEqualTo(Motivo.TAMPA_ABERTA);
        assertThat(pend(5, new Fila(4, List.of("connecting-to-device"), ""), "")).isEqualTo(Motivo.IMPRESSORA_OFFLINE);
        assertThat(pend(5, new Fila(4, List.of("offline-report"), ""), "")).isEqualTo(Motivo.IMPRESSORA_OFFLINE);
        assertThat(pend(5, new Fila(4, List.of("none"), "The printer may not exist or is unavailable at this time."), "")).isEqualTo(Motivo.IMPRESSORA_OFFLINE);
        assertThat(pend(5, new Fila(4, List.of("none"), ""), "The printer may not exist or is unavailable at this time.")).isEqualTo(Motivo.IMPRESSORA_OFFLINE);
        assertThat(pend(5, new Fila(4, List.of("none"), ""), "")).isNull();
        EstadoSpooler semFila = ClassificadorCups.porIpp("PDF-1", Optional.of(new Job(3, List.of("none"), "")), Optional::empty);
        assertThat(semFila.estado()).isEqualTo(Estado.PENDENTE);
        assertThat(semFila.motivo()).isNull();
    }

    private static Motivo pend(int estadoJob, Fila fila, String mensagemDoJob) {
        EstadoSpooler e = ClassificadorCups.porIpp("PDF-52", Optional.of(new Job(estadoJob, List.of("none"), mensagemDoJob)), () -> Optional.of(fila));
        assertThat(e.estado()).isEqualTo(Estado.PENDENTE);
        assertThat(e.encerrado()).isFalse();
        return e.motivo();
    }

    @Test
    @DisplayName("job que o cupsd não conhece (histórico desligado/rodado) → DESCONHECIDO{SUMIU_DA_FILA}; estado fora do RFC → DESCONHECIDO sem motivo")
    void sumiu() {
        EstadoSpooler e = ClassificadorCups.porIpp("PDF-9", Optional.empty(), () -> SEM_QUEIXA);
        assertThat(e.estado()).isEqualTo(Estado.DESCONHECIDO);
        assertThat(e.motivo()).isEqualTo(Motivo.SUMIU_DA_FILA);
        assertThat(job(42).estado()).isEqualTo(Estado.DESCONHECIDO);
    }
}

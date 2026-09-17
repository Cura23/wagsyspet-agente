package br.com.wagner.wagsyspet.agente.impressao.spooler;

import br.com.wagner.wagsyspet.agente.impressao.spooler.EstadoSpooler.Estado;
import br.com.wagner.wagsyspet.agente.impressao.spooler.EstadoSpooler.Motivo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AcompanhamentoCups — consulta o lpstat na ordem certa e nunca afirma 'impresso' sem ver o histórico")
class AcompanhamentoCupsTest {

    private static final String JOB = "PDF-12                  lojista           1024   Thu Sep 17 09:56:10 2026\n\tStatus: \n\tAlerts: %s\n\tqueued for PDF\n";

    /** lpstat falso: responde pelo argumento depois de -W (ou "-p"). */
    private static final class LpstatFalso implements Function<List<String>, ComandoSpooler.Saida> {
        final List<List<String>> chamadas = new ArrayList<>();
        String naoCompletos = "";
        String completos = "";
        String impressora = "printer PDF is idle.  enabled since Thu Sep 17 09:00:00 2026\n\tAlerts: none\n";
        ComandoSpooler.Saida falha;

        @Override
        public ComandoSpooler.Saida apply(List<String> cmd) {
            chamadas.add(List.copyOf(cmd));
            if (falha != null) {
                return falha;
            }
            if (cmd.contains("not-completed")) {
                return new ComandoSpooler.Saida(0, naoCompletos, false);
            }
            if (cmd.contains("completed")) {
                return new ComandoSpooler.Saida(0, completos, false);
            }
            return new ComandoSpooler.Saida(0, impressora, false);
        }
    }

    @Test
    @DisplayName("ordem das consultas: PRIMEIRO not-completed, depois completed (ao contrário, um job que termina entre as duas sumiria das duas listas); '-W' e '-l' ANTES de '-o <fila>' como o lpstat exige")
    void ordemEArgumentos() {
        LpstatFalso lpstat = new LpstatFalso();
        lpstat.completos = JOB.formatted("job-completed-successfully");
        AcompanhamentoCups a = new AcompanhamentoCups("PDF", "PDF-12", lpstat);
        EstadoSpooler e = a.consultar();
        assertThat(e.estado()).isEqualTo(Estado.IMPRESSO);
        assertThat(lpstat.chamadas.get(0)).containsExactly("/usr/bin/lpstat", "-W", "not-completed", "-l", "-o", "PDF");
        assertThat(lpstat.chamadas.get(1)).containsExactly("/usr/bin/lpstat", "-W", "completed", "-l", "-o", "PDF");
        assertThat(lpstat.chamadas).as("terminado: a fila não precisa ser consultada").hasSize(2);
    }

    @Test
    @DisplayName("na fila → consulta também 'lpstat -l -p <fila>' para dizer POR QUE está parado; estado final (encerrado) é memorizado — não roda lpstat de novo")
    void pendenteConsultaAFilaETerminalEhMemorizado() {
        LpstatFalso lpstat = new LpstatFalso();
        lpstat.naoCompletos = JOB.formatted("none");
        lpstat.impressora = "printer PDF disabled since Thu Sep 17 09:56:17 2026 -\n\tPaused\n\tAlerts: paused\n";
        AcompanhamentoCups a = new AcompanhamentoCups("PDF", "PDF-12", lpstat);
        EstadoSpooler e = a.consultar();
        assertThat(e.estado()).isEqualTo(Estado.PENDENTE);
        assertThat(e.motivo()).isEqualTo(Motivo.FILA_PARADA);
        assertThat(lpstat.chamadas.get(1)).containsExactly("/usr/bin/lpstat", "-l", "-p", "PDF");

        lpstat.naoCompletos = "";
        lpstat.completos = JOB.formatted("job-canceled-by-user");
        assertThat(a.consultar().motivo()).isEqualTo(Motivo.CANCELADO);
        int depoisDoTerminal = lpstat.chamadas.size();
        assertThat(a.consultar().motivo()).isEqualTo(Motivo.CANCELADO);
        assertThat(lpstat.chamadas).hasSize(depoisDoTerminal);
    }

    @Test
    @DisplayName("lpstat falhando (exit ≠ 0, 'Scheduler is not running') ou estourando o prazo → DESCONHECIDO{CONSULTA_INDISPONIVEL} NÃO encerrado (pode voltar); job id desconhecido ('?') → DESCONHECIDO{SEM_REGISTRO} na hora, sem rodar nada")
    void consultaIndisponivelESemId() {
        LpstatFalso lpstat = new LpstatFalso();
        lpstat.falha = new ComandoSpooler.Saida(1, "lpstat: Scheduler is not running.", false);
        EstadoSpooler fora = new AcompanhamentoCups("PDF", "PDF-12", lpstat).consultar();
        assertThat(fora.estado()).isEqualTo(Estado.DESCONHECIDO);
        assertThat(fora.motivo()).isEqualTo(Motivo.CONSULTA_INDISPONIVEL);
        assertThat(fora.encerrado()).isFalse();
        assertThat(fora.detalhe()).contains("Scheduler is not running");

        lpstat.falha = new ComandoSpooler.Saida(-1, "", true);
        assertThat(new AcompanhamentoCups("PDF", "PDF-12", lpstat).consultar().motivo()).isEqualTo(Motivo.CONSULTA_INDISPONIVEL);

        LpstatFalso intocado = new LpstatFalso();
        EstadoSpooler semId = new AcompanhamentoCups("PDF", "?", intocado).consultar();
        assertThat(semId.motivo()).isEqualTo(Motivo.SEM_REGISTRO);
        assertThat(semId.encerrado()).isTrue();
        assertThat(intocado.chamadas).isEmpty();
    }
}

package br.com.wagner.wagsyspet.agente.impressao.spooler;

import br.com.wagner.wagsyspet.agente.impressao.spooler.EstadoSpooler.Estado;
import br.com.wagner.wagsyspet.agente.impressao.spooler.EstadoSpooler.Motivo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static br.com.wagner.wagsyspet.agente.impressao.spooler.HistoricoJobWindows.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plano F6 D9 — o Windows APAGA o job da fila ao imprimir (KEEPPRINTEDJOBS desligado por padrão; ligar exige admin), então o estado
 * final sai do HISTÓRICO do que foi visto enquanto o job existiu. Bits de {@code JOB_INFO_1.Status} (MS Learn, JOB_INFO_1).
 */
@DisplayName("HistoricoJobWindows — bitmask do spooler + 'sumiu da fila' → IMPRESSO / FALHOU / PENDENTE / DESCONHECIDO")
class HistoricoJobWindowsTest {

    @Test
    @DisplayName("PRINTED ou COMPLETE visto → IMPRESSO (mesmo que o job ainda esteja na fila); visto spoolando/imprimindo e depois SUMIU sem sinal de cancelamento → IMPRESSO (é o caminho normal: o Windows apaga o job impresso)")
    void impresso() {
        HistoricoJobWindows h = new HistoricoJobWindows("AgroEase cupom x");
        h.visto(37, JOB_STATUS_SPOOLING);
        assertThat(h.estado(0).estado()).isEqualTo(Estado.PENDENTE);
        h.visto(37, JOB_STATUS_PRINTING);
        h.visto(37, JOB_STATUS_PRINTING | JOB_STATUS_PRINTED);
        EstadoSpooler e = h.estado(0);
        assertThat(e.estado()).isEqualTo(Estado.IMPRESSO);
        assertThat(e.encerrado()).isTrue();
        assertThat(e.detalhe()).contains("37").contains("PRINTED");

        HistoricoJobWindows sumiu = new HistoricoJobWindows("AgroEase cupom y");
        sumiu.visto(38, JOB_STATUS_SPOOLING);
        sumiu.visto(38, JOB_STATUS_PRINTING);
        sumiu.ausente();
        assertThat(sumiu.estado(0).estado()).isEqualTo(Estado.IMPRESSO);
        assertThat(sumiu.estado(0).detalhe()).containsIgnoringCase("saiu da fila");

        HistoricoJobWindows completo = new HistoricoJobWindows("z");
        completo.visto(39, JOB_STATUS_COMPLETE);
        assertThat(completo.estado(0).estado()).isEqualTo(Estado.IMPRESSO);
    }

    @Test
    @DisplayName("DELETING/DELETED sem PRINTED antes → FALHOU{CANCELADO}; DELETING DEPOIS de PRINTED é só a limpeza normal → IMPRESSO")
    void cancelado() {
        HistoricoJobWindows h = new HistoricoJobWindows("x");
        h.visto(40, JOB_STATUS_PRINTING);
        h.visto(40, JOB_STATUS_PRINTING | JOB_STATUS_DELETING);
        assertThat(h.estado(0).estado()).isEqualTo(Estado.FALHOU);
        assertThat(h.estado(0).motivo()).isEqualTo(Motivo.CANCELADO);
        h.ausente();
        assertThat(h.estado(0).motivo()).as("continua cancelado depois de sumir").isEqualTo(Motivo.CANCELADO);

        HistoricoJobWindows limpeza = new HistoricoJobWindows("y");
        limpeza.visto(41, JOB_STATUS_PRINTED);
        limpeza.visto(41, JOB_STATUS_PRINTED | JOB_STATUS_DELETING);
        assertThat(limpeza.estado(0).estado()).isEqualTo(Estado.IMPRESSO);
    }

    @Test
    @DisplayName("ainda na fila → PENDENTE com o motivo do JOB: OFFLINE, PAPEROUT, USER_INTERVENTION, PAUSED, ERROR/BLOCKED (erro é transitório no spooler: PENDENTE, não FALHOU); sem bit no job, vale o status da IMPRESSORA (pausada, offline, sem papel, tampa aberta)")
    void pendente() {
        assertThat(pendente(JOB_STATUS_OFFLINE, 0)).isEqualTo(Motivo.IMPRESSORA_OFFLINE);
        assertThat(pendente(JOB_STATUS_PAPEROUT, 0)).isEqualTo(Motivo.SEM_PAPEL);
        assertThat(pendente(JOB_STATUS_USER_INTERVENTION, 0)).isEqualTo(Motivo.INTERVENCAO);
        assertThat(pendente(JOB_STATUS_PAUSED, 0)).isEqualTo(Motivo.FILA_PARADA);
        assertThat(pendente(JOB_STATUS_ERROR | JOB_STATUS_PRINTING, 0)).isEqualTo(Motivo.ERRO_DRIVER);
        assertThat(pendente(JOB_STATUS_BLOCKED_DEVQ, 0)).isEqualTo(Motivo.ERRO_DRIVER);
        assertThat(pendente(0, PRINTER_STATUS_PAUSED)).as("Status 0 = fila pausada depois de spoolar (doc)").isEqualTo(Motivo.FILA_PARADA);
        assertThat(pendente(JOB_STATUS_SPOOLING, PRINTER_STATUS_OFFLINE)).isEqualTo(Motivo.IMPRESSORA_OFFLINE);
        assertThat(pendente(JOB_STATUS_SPOOLING, PRINTER_STATUS_PAPER_OUT)).isEqualTo(Motivo.SEM_PAPEL);
        assertThat(pendente(JOB_STATUS_SPOOLING, PRINTER_STATUS_DOOR_OPEN)).isEqualTo(Motivo.TAMPA_ABERTA);
        assertThat(pendente(JOB_STATUS_PRINTING, 0)).as("imprimindo, sem queixa").isNull();
    }

    private static Motivo pendente(int statusJob, int statusImpressora) {
        HistoricoJobWindows h = new HistoricoJobWindows("x");
        h.visto(50, statusJob);
        EstadoSpooler e = h.estado(statusImpressora);
        assertThat(e.estado()).isEqualTo(Estado.PENDENTE);
        assertThat(e.encerrado()).isFalse();
        return e.motivo();
    }

    @Test
    @DisplayName("nunca visto: enquanto ninguém olhou a fila → PENDENTE sem motivo (ainda pode aparecer); fila olhada e o job NUNCA esteve lá → DESCONHECIDO{SUMIU_DA_FILA} — jamais 'impresso' por palpite")
    void nuncaVisto() {
        HistoricoJobWindows h = new HistoricoJobWindows("x");
        assertThat(h.estado(0).estado()).isEqualTo(Estado.PENDENTE);
        h.ausente();
        assertThat(h.estado(0).estado()).as("1 olhada sem ver pode ser só cedo demais").isEqualTo(Estado.PENDENTE);
        for (int i = 0; i < HistoricoJobWindows.AUSENCIAS_ATE_DESISTIR; i++) {
            h.ausente();
        }
        assertThat(h.estado(0).estado()).isEqualTo(Estado.DESCONHECIDO);
        assertThat(h.estado(0).motivo()).isEqualTo(Motivo.SUMIU_DA_FILA);
    }

    @Test
    @DisplayName("consulta ao spooler falhou → DESCONHECIDO{CONSULTA_INDISPONIVEL} não encerrado, sem apagar o que já foi visto")
    void consultaFalhou() {
        HistoricoJobWindows h = new HistoricoJobWindows("x");
        h.falhaDeConsulta("OpenPrinter falhou (5)");
        assertThat(h.estado(0).motivo()).isEqualTo(Motivo.CONSULTA_INDISPONIVEL);
        assertThat(h.estado(0).encerrado()).isFalse();
        h.visto(60, JOB_STATUS_PRINTED);
        assertThat(h.estado(0).estado()).isEqualTo(Estado.IMPRESSO);
    }
}

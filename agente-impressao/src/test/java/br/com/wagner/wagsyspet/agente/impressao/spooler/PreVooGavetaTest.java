package br.com.wagner.wagsyspet.agente.impressao.spooler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static br.com.wagner.wagsyspet.agente.impressao.spooler.HistoricoJobWindows.JOB_STATUS_ERROR;
import static br.com.wagner.wagsyspet.agente.impressao.spooler.HistoricoJobWindows.JOB_STATUS_PRINTING;
import static br.com.wagner.wagsyspet.agente.impressao.spooler.HistoricoJobWindows.JOB_STATUS_SPOOLING;
import static br.com.wagner.wagsyspet.agente.impressao.spooler.HistoricoJobWindows.PRINTER_ATTRIBUTE_WORK_OFFLINE;
import static br.com.wagner.wagsyspet.agente.impressao.spooler.HistoricoJobWindows.PRINTER_STATUS_PAPER_OUT;
import static br.com.wagner.wagsyspet.agente.impressao.spooler.HistoricoJobWindows.PRINTER_STATUS_PAUSED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fecho F6 — antes de mandar o PULSO DA GAVETA o agente olha a fila. O spooler (Windows e CUPS) aceita job com a impressora
 * desligada e o guarda, inclusive entre reinícios: cada venda em dinheiro (e cada clique em "Abrir gaveta") empilharia um pulso, e
 * na manhã seguinte — ou quando alguém religasse o cabo — a gaveta dispararia sozinha, uma vez por job. Na dúvida (não deu para
 * consultar), segue como antes: o pré-voo só BLOQUEIA com evidência.
 */
@DisplayName("PreVooGaveta — o pulso da gaveta não vai para uma fila que não está imprimindo")
class PreVooGavetaTest {

    private static AcompanhamentoWindows.JobNaFila job(String documento, int status) {
        return new AcompanhamentoWindows.JobNaFila(7, documento, status);
    }

    @Test
    @DisplayName("Windows: impressora offline (inclusive pelo atributo 'Usar impressora offline'), pausada ou sem papel → impedimento; ociosa ou IMPRIMINDO sem queixa → livre")
    void windowsPeloStatus() {
        int offline = HistoricoJobWindows.statusEfetivo(0, PRINTER_ATTRIBUTE_WORK_OFFLINE);
        assertThat(PreVooGaveta.porWindows("EPSON", offline, List.of())).get().asString().contains("EPSON").containsIgnoringCase("offline");
        assertThat(PreVooGaveta.porWindows("EPSON", PRINTER_STATUS_PAUSED, List.of())).isPresent();
        assertThat(PreVooGaveta.porWindows("EPSON", PRINTER_STATUS_PAPER_OUT, List.of())).isPresent();
        assertThat(PreVooGaveta.porWindows("EPSON", 0, List.of())).isEmpty();
        assertThat(PreVooGaveta.porWindows("EPSON", 0, List.of(job("AgroEase cupom abc", JOB_STATUS_PRINTING)))).as("venda anterior ainda imprimindo: normal").isEmpty();
        assertThat(PreVooGaveta.porWindows("EPSON", 0, List.of(job("AgroEase corte abc", JOB_STATUS_SPOOLING)))).isEmpty();
    }

    @Test
    @DisplayName("Windows: status 0 mas já há um pulso de GAVETA parado na fila, ou um job com ERRO → impedimento (no máximo UM pulso fica preso, nunca N)")
    void windowsPelaFila() {
        assertThat(PreVooGaveta.porWindows("EPSON", 0, List.of(job("AgroEase gaveta venda-1", 0)))).get().asString().containsIgnoringCase("gaveta");
        assertThat(PreVooGaveta.porWindows("EPSON", 0, List.of(job("AgroEase cupom abc", JOB_STATUS_ERROR | JOB_STATUS_PRINTING)))).isPresent();
        assertThat(PreVooGaveta.porWindows("EPSON", 0, List.of(job(null, 0)))).as("documento nulo de outro programa não derruba").isEmpty();
    }

    @Test
    @DisplayName("CUPS (IPP): fila parada (printer-state 5) ou com motivo de impressora fora (offline, connecting-to-device, media-empty, cover-open, paused) → impedimento; ociosa/processando → livre; sem IPP → livre (sem evidência não bloqueia)")
    void cups() {
        assertThat(PreVooGaveta.porCups("EPSON", Optional.of(new ClienteIppCups.Fila(5, List.of("paused"), "")))).isPresent();
        assertThat(PreVooGaveta.porCups("EPSON", Optional.of(new ClienteIppCups.Fila(3, List.of("offline-report"), "")))).isPresent();
        assertThat(PreVooGaveta.porCups("EPSON", Optional.of(new ClienteIppCups.Fila(4, List.of("connecting-to-device"), "")))).isPresent();
        assertThat(PreVooGaveta.porCups("EPSON", Optional.of(new ClienteIppCups.Fila(3, List.of("media-empty-error"), "")))).isPresent();
        assertThat(PreVooGaveta.porCups("EPSON", Optional.of(new ClienteIppCups.Fila(3, List.of("cover-open"), "")))).isPresent();
        assertThat(PreVooGaveta.porCups("EPSON", Optional.of(new ClienteIppCups.Fila(3, List.of("none"), "")))).isEmpty();
        assertThat(PreVooGaveta.porCups("EPSON", Optional.of(new ClienteIppCups.Fila(4, List.of("cups-waiting-for-job-completed"), "")))).isEmpty();
        assertThat(PreVooGaveta.porCups("EPSON", Optional.empty())).isEmpty();
    }
}

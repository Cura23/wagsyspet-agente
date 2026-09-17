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

    @Test
    @DisplayName("Fecho F6 — o retrato REAL de 'térmica USB desligada' no Windows: a fila fica 'Usar impressora offline' = bit em PRINTER_INFO_2.ATTRIBUTES (WORK_OFFLINE 0x400), com Status=0 e o job parado com Status=0. Sem ler Attributes o motivo saía vazio, o pull cedo do PWA não avisava e o operador reimprimia. Attributes offline → IMPRESSORA_OFFLINE; e impressora offline VENCE o ERROR do job (porta que tenta escrever numa impressora desligada não é 'erro do driver')")
    void impressoraOfflinePelosAtributos() {
        assertThat(HistoricoJobWindows.statusEfetivo(0, HistoricoJobWindows.PRINTER_ATTRIBUTE_WORK_OFFLINE)).isEqualTo(PRINTER_STATUS_OFFLINE);
        assertThat(HistoricoJobWindows.statusEfetivo(PRINTER_STATUS_PAPER_OUT, 0x400 | 0x40)).isEqualTo(PRINTER_STATUS_PAPER_OUT | PRINTER_STATUS_OFFLINE);
        assertThat(HistoricoJobWindows.statusEfetivo(0, 0x40 /* LOCAL */)).as("atributos sem o bit offline não inventam queixa").isZero();

        int offline = HistoricoJobWindows.statusEfetivo(0, HistoricoJobWindows.PRINTER_ATTRIBUTE_WORK_OFFLINE);
        assertThat(pendente(0, offline)).isEqualTo(Motivo.IMPRESSORA_OFFLINE);
        assertThat(pendente(JOB_STATUS_ERROR | JOB_STATUS_PRINTING, offline)).as("impressora offline vence o ERROR do job").isEqualTo(Motivo.IMPRESSORA_OFFLINE);
        assertThat(pendente(JOB_STATUS_ERROR | JOB_STATUS_PRINTING, PRINTER_STATUS_PAPER_OUT)).isEqualTo(Motivo.SEM_PAPEL);
        assertThat(pendente(JOB_STATUS_PAPEROUT, offline)).as("queixa ESPECÍFICA do job continua na frente").isEqualTo(Motivo.SEM_PAPEL);
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
    @DisplayName("SUMIU sem imprimir não pode virar IMPRESSO (adversarial L4): se a ÚLTIMA foto mostrava o job parado/com problema (OFFLINE, PAPEROUT, PAUSED, ERROR, intervenção, Status 0 = fila pausada) ou a impressora estava pausada/offline, ele não estava sendo entregue — sumir = operador cancelou/limpou a fila (o DELETING de um job que não está imprimindo não dura uma foto) → DESCONHECIDO{SUMIU_DA_FILA}")
    void sumiuSemEstarImprimindo() {
        for (int ultimo : new int[]{JOB_STATUS_OFFLINE, JOB_STATUS_PAPEROUT, JOB_STATUS_PAUSED, JOB_STATUS_PRINTING | JOB_STATUS_ERROR, JOB_STATUS_USER_INTERVENTION, 0}) {
            HistoricoJobWindows h = new HistoricoJobWindows("x");
            h.visto(70, ultimo);
            h.ausente();
            EstadoSpooler e = h.estado(0);
            assertThat(e.estado()).as("último status 0x%x", ultimo).isEqualTo(Estado.DESCONHECIDO);
            assertThat(e.motivo()).isEqualTo(Motivo.SUMIU_DA_FILA);
            assertThat(e.encerrado()).isTrue();
        }
        HistoricoJobWindows filaPausada = new HistoricoJobWindows("x");
        filaPausada.visto(71, JOB_STATUS_SPOOLING);
        filaPausada.statusDaImpressora(PRINTER_STATUS_OFFLINE);
        filaPausada.ausente();
        assertThat(filaPausada.estado(0).estado()).as("impressora offline na última foto").isEqualTo(Estado.DESCONHECIDO);
    }

    @Test
    @DisplayName("'nunca apareceu na fila' só vale DEPOIS que o print() retornou (adversarial L4): o acompanhamento é armado ANTES do StartDoc e, em impressora de rede/1ª impressão a frio, o job leva mais de 1 s para existir — antes de submetido() ausência nenhuma conta; depois, 20+ fotos sem nunca ver → DESCONHECIDO{SUMIU_DA_FILA}")
    void nuncaVisto() {
        HistoricoJobWindows h = new HistoricoJobWindows("x");
        for (int i = 0; i < 500; i++) {
            h.ausente();
        }
        assertThat(h.estado(0).estado()).as("print() ainda não retornou: o job pode nem existir").isEqualTo(Estado.PENDENTE);
        assertThat(h.estado(0).encerrado()).isFalse();
        h.submetido();
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

package br.com.wagner.wagsyspet.agente.impressao.spooler;

import br.com.wagner.wagsyspet.agente.impressao.spooler.EstadoSpooler.Motivo;

import java.util.ArrayList;
import java.util.List;

/**
 * Histórico PURO (sem JNA) do que foi visto de UM job na fila do Windows (plano F6 D9). O Windows apaga o job ao imprimir
 * ({@code PRINTER_ATTRIBUTE_KEEPPRINTEDJOBS} desligado por padrão; ligar exige admin) e o JDK descarta o job id do {@code StartDoc} —
 * então o job é achado pelo NOME do documento e o estado final sai do que ele mostrou enquanto existiu:
 * <ul>
 *   <li>{@code PRINTED}/{@code COMPLETE} visto → IMPRESSO ("sent to the printer, but may not be printed yet" — dados entregues);</li>
 *   <li>{@code DELETING}/{@code DELETED} SEM {@code PRINTED} antes → FALHOU{CANCELADO};</li>
 *   <li>visto SPOOLANDO/IMPRIMINDO sem queixa e depois ausente → IMPRESSO (o caminho normal). Se a última foto mostrava o job parado ou
 *       com problema (ou a impressora pausada/offline), ele não estava sendo entregue: sumir = cancelado/fila limpa (o {@code DELETING}
 *       de um job que não está imprimindo não dura uma foto) → DESCONHECIDO{SUMIU_DA_FILA}, nunca "impresso" por inferência;</li>
 *   <li>na fila → PENDENTE com o motivo do job ou da impressora; {@code ERROR} é transitório no spooler (retry) → PENDENTE, não FALHOU;</li>
 *   <li>DEPOIS de {@link #submetido()} (o {@code print()} retornou), fila olhada {@value #AUSENCIAS_ATE_DESISTIR}+ vezes sem NUNCA ver o
 *       job → DESCONHECIDO{SUMIU_DA_FILA}. Antes disso nenhuma ausência conta: o acompanhamento é armado antes do {@code StartDoc} e,
 *       em impressora de rede ou na 1ª impressão a frio, o job demora mais de 1 s para existir.</li>
 * </ul>
 * Thread-safe por {@code synchronized}: quem olha a fila é uma thread, quem consulta é outra.
 */
public final class HistoricoJobWindows {

    // JOB_INFO_1.Status (learn.microsoft.com/windows/win32/printdocs/job-info-1)
    public static final int JOB_STATUS_PAUSED = 0x1;
    public static final int JOB_STATUS_ERROR = 0x2;
    public static final int JOB_STATUS_DELETING = 0x4;
    public static final int JOB_STATUS_SPOOLING = 0x8;
    public static final int JOB_STATUS_PRINTING = 0x10;
    public static final int JOB_STATUS_OFFLINE = 0x20;
    public static final int JOB_STATUS_PAPEROUT = 0x40;
    public static final int JOB_STATUS_PRINTED = 0x80;
    public static final int JOB_STATUS_DELETED = 0x100;
    public static final int JOB_STATUS_BLOCKED_DEVQ = 0x200;
    public static final int JOB_STATUS_USER_INTERVENTION = 0x400;
    public static final int JOB_STATUS_COMPLETE = 0x1000;
    // PRINTER_INFO_2.Status
    public static final int PRINTER_STATUS_PAUSED = 0x1;
    public static final int PRINTER_STATUS_ERROR = 0x2;
    public static final int PRINTER_STATUS_PAPER_JAM = 0x8;
    public static final int PRINTER_STATUS_PAPER_OUT = 0x10;
    public static final int PRINTER_STATUS_OFFLINE = 0x80;
    public static final int PRINTER_STATUS_NOT_AVAILABLE = 0x1000;
    public static final int PRINTER_STATUS_USER_INTERVENTION = 0x100000;
    public static final int PRINTER_STATUS_DOOR_OPEN = 0x400000;

    /** Olhadas seguidas na fila sem NUNCA ter visto o job antes de concluir que ele não passou por ali. */
    static final int AUSENCIAS_ATE_DESISTIR = 20;

    private final String documento;
    private int jobId = -1;
    private boolean visto;
    private boolean impresso;
    private boolean cancelado;
    private boolean presente;
    private int ultimoStatus;
    private int ausenciasSemVer;
    private boolean submetido;
    private int statusImpressoraNaUltimaFoto;
    private String falha;

    private static final int JOB_COM_PROBLEMA = JOB_STATUS_PAUSED | JOB_STATUS_ERROR | JOB_STATUS_OFFLINE | JOB_STATUS_PAPEROUT
            | JOB_STATUS_BLOCKED_DEVQ | JOB_STATUS_USER_INTERVENTION;
    private static final int IMPRESSORA_COM_PROBLEMA = PRINTER_STATUS_PAUSED | PRINTER_STATUS_ERROR | PRINTER_STATUS_PAPER_JAM
            | PRINTER_STATUS_PAPER_OUT | PRINTER_STATUS_OFFLINE | PRINTER_STATUS_NOT_AVAILABLE | PRINTER_STATUS_USER_INTERVENTION
            | PRINTER_STATUS_DOOR_OPEN;

    public HistoricoJobWindows(String documento) {
        this.documento = documento;
    }

    /** O job está na fila agora, com este {@code Status}. */
    public synchronized void visto(int id, int status) {
        jobId = id;
        visto = true;
        presente = true;
        ultimoStatus = status;
        falha = null;
        if ((status & (JOB_STATUS_PRINTED | JOB_STATUS_COMPLETE)) != 0) {
            impresso = true;
        }
        if ((status & (JOB_STATUS_DELETING | JOB_STATUS_DELETED)) != 0 && !impresso) {
            cancelado = true;
        }
    }

    /** A fila foi olhada e o job NÃO está lá. */
    public synchronized void ausente() {
        presente = false;
        falha = null;
        if (!visto && submetido) {
            ausenciasSemVer++;
        }
    }

    /** O {@code print()} retornou: o job existiu com certeza; só agora "nunca apareceu" pode ser conclusão. */
    public synchronized void submetido() {
        submetido = true;
    }

    /** {@code PRINTER_INFO_2.Status} lido junto da última foto em que o job estava na fila. */
    public synchronized void statusDaImpressora(int status) {
        statusImpressoraNaUltimaFoto = status;
    }

    public synchronized void falhaDeConsulta(String detalhe) {
        falha = detalhe;
    }

    /** @param statusImpressora {@code PRINTER_INFO_2.Status} corrente (0 = sem queixa / desconhecido) */
    public synchronized EstadoSpooler estado(int statusImpressora) {
        String quem = "Windows job " + (jobId < 0 ? "?" : jobId) + " '" + documento + "'";
        if (impresso) {
            return EstadoSpooler.impresso(quem + " " + bits(ultimoStatus) + " — dados entregues ao dispositivo");
        }
        if (cancelado) {
            return EstadoSpooler.falhou(Motivo.CANCELADO, quem + " " + bits(ultimoStatus) + " — apagado da fila antes de imprimir");
        }
        if (visto && !presente) {
            boolean entregando = (ultimoStatus & (JOB_STATUS_SPOOLING | JOB_STATUS_PRINTING)) != 0
                    && (ultimoStatus & JOB_COM_PROBLEMA) == 0
                    && (statusImpressoraNaUltimaFoto & IMPRESSORA_COM_PROBLEMA) == 0;
            if (entregando) {
                return EstadoSpooler.impresso(quem + " saiu da fila depois de " + bits(ultimoStatus) + " — dados entregues ao dispositivo");
            }
            return EstadoSpooler.desconhecido(Motivo.SUMIU_DA_FILA, quem + " saiu da fila SEM estar imprimindo (último estado " + bits(ultimoStatus)
                    + (statusImpressoraNaUltimaFoto == 0 ? "" : ", impressora=0x" + Integer.toHexString(statusImpressoraNaUltimaFoto)) + ") — cancelado ou fila limpa?");
        }
        if (falha != null && !presente) {
            return new EstadoSpooler(EstadoSpooler.Estado.DESCONHECIDO, Motivo.CONSULTA_INDISPONIVEL, quem + ": " + falha, false);
        }
        if (!visto) {
            if (ausenciasSemVer > AUSENCIAS_ATE_DESISTIR) {
                return EstadoSpooler.desconhecido(Motivo.SUMIU_DA_FILA, quem + " nunca apareceu na fila");
            }
            return EstadoSpooler.pendente(null, quem + " ainda não apareceu na fila");
        }
        return EstadoSpooler.pendente(motivoPendente(ultimoStatus, statusImpressora),
                quem + " na fila " + bits(ultimoStatus) + (statusImpressora == 0 ? "" : " impressora=0x" + Integer.toHexString(statusImpressora)));
    }

    private static Motivo motivoPendente(int job, int impressora) {
        if ((job & JOB_STATUS_OFFLINE) != 0) {
            return Motivo.IMPRESSORA_OFFLINE;
        }
        if ((job & JOB_STATUS_PAPEROUT) != 0) {
            return Motivo.SEM_PAPEL;
        }
        if ((job & JOB_STATUS_USER_INTERVENTION) != 0) {
            return Motivo.INTERVENCAO;
        }
        if ((job & JOB_STATUS_PAUSED) != 0) {
            return Motivo.FILA_PARADA;
        }
        if ((job & (JOB_STATUS_ERROR | JOB_STATUS_BLOCKED_DEVQ)) != 0) {
            return Motivo.ERRO_DRIVER;
        }
        if ((impressora & PRINTER_STATUS_PAUSED) != 0) {
            return Motivo.FILA_PARADA;
        }
        if ((impressora & (PRINTER_STATUS_OFFLINE | PRINTER_STATUS_NOT_AVAILABLE)) != 0) {
            return Motivo.IMPRESSORA_OFFLINE;
        }
        if ((impressora & (PRINTER_STATUS_PAPER_OUT | PRINTER_STATUS_PAPER_JAM)) != 0) {
            return Motivo.SEM_PAPEL;
        }
        if ((impressora & PRINTER_STATUS_DOOR_OPEN) != 0) {
            return Motivo.TAMPA_ABERTA;
        }
        if ((impressora & PRINTER_STATUS_USER_INTERVENTION) != 0) {
            return Motivo.INTERVENCAO;
        }
        if ((impressora & PRINTER_STATUS_ERROR) != 0) {
            return Motivo.ERRO_DRIVER;
        }
        return null;
    }

    static String bits(int status) {
        if (status == 0) {
            return "[0]";
        }
        List<String> nomes = new ArrayList<>();
        String[] rotulos = {"PAUSED", "ERROR", "DELETING", "SPOOLING", "PRINTING", "OFFLINE", "PAPEROUT", "PRINTED", "DELETED",
                "BLOCKED_DEVQ", "USER_INTERVENTION", "RESTART", "COMPLETE", "RETAINED"};
        for (int i = 0; i < rotulos.length; i++) {
            if ((status & (1 << i)) != 0) {
                nomes.add(rotulos[i]);
            }
        }
        return nomes.toString();
    }
}

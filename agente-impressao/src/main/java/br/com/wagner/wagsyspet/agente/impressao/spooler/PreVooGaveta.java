package br.com.wagner.wagsyspet.agente.impressao.spooler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Fecho F6 — olha a fila ANTES de mandar o pulso da GAVETA. O spooler (Windows e CUPS) aceita job com a impressora desligada e o
 * guarda, inclusive entre reinícios do computador: cada venda em dinheiro — e cada clique em "Abrir gaveta" — empilharia um pulso,
 * e quando alguém religasse a impressora a gaveta dispararia sozinha, uma vez por job, possivelmente sem ninguém no balcão. A
 * guarda "gaveta tardia" do L5 só media a fila do AGENTE; o gatilho comum é a impressora fora.
 *
 * <p>Só BLOQUEIA com evidência (impressora queixosa, fila parada, ou um pulso de gaveta já preso na fila). Se não der para
 * consultar, devolve vazio e o comportamento é o de sempre. O CORTE não passa por aqui: cortar depois de um cupom atrasado é o
 * esperado. Nunca lança.</p>
 */
public final class PreVooGaveta {

    private static final Logger log = LoggerFactory.getLogger(PreVooGaveta.class);

    /** Prefixo do nome do job do pulso da gaveta (= {@code ServidorAgente.NOME_JOB_GAVETA}; o teste de contrato cruza os dois). */
    public static final String PREFIXO_JOB_GAVETA = "AgroEase gaveta";

    private static final int IMPRESSORA_FORA = HistoricoJobWindows.PRINTER_STATUS_PAUSED | HistoricoJobWindows.PRINTER_STATUS_ERROR
            | HistoricoJobWindows.PRINTER_STATUS_PAPER_JAM | HistoricoJobWindows.PRINTER_STATUS_PAPER_OUT | HistoricoJobWindows.PRINTER_STATUS_OFFLINE
            | HistoricoJobWindows.PRINTER_STATUS_NOT_AVAILABLE | HistoricoJobWindows.PRINTER_STATUS_USER_INTERVENTION | HistoricoJobWindows.PRINTER_STATUS_DOOR_OPEN;
    private static final int JOB_PARADO = HistoricoJobWindows.JOB_STATUS_PAUSED | HistoricoJobWindows.JOB_STATUS_ERROR | HistoricoJobWindows.JOB_STATUS_OFFLINE
            | HistoricoJobWindows.JOB_STATUS_PAPEROUT | HistoricoJobWindows.JOB_STATUS_BLOCKED_DEVQ | HistoricoJobWindows.JOB_STATUS_USER_INTERVENTION;
    /** Trechos de {@code printer-state-reasons} que significam "a impressora não está recebendo" (com ou sem sufixo -error/-report). */
    private static final List<String> MOTIVOS_CUPS_FORA = List.of("offline", "connecting-to-device", "media-empty", "media-needed", "cover-open",
            "door-open", "paused", "shutdown", "stopped");
    private static final int IPP_PRINTER_STOPPED = 5;

    private PreVooGaveta() {
    }

    /** Por que o pulso da gaveta NÃO deve ser enviado agora a esta impressora; vazio = pode enviar (ou não deu para saber). */
    public static Optional<String> impedimento(boolean windows, String impressora) {
        try {
            if (windows) {
                try (FilaWindowsJna fila = new FilaWindowsJna(impressora)) {
                    return porWindows(impressora, fila.statusImpressora(), fila.jobs());
                }
            }
            return porCups(impressora, new ClienteIppCups(ClienteIppCups.CUPSD_LOCAL, Duration.ofSeconds(2)).fila(impressora));
        } catch (IOException | RuntimeException | LinkageError e) {
            log.debug("pré-voo da gaveta indisponível ({}); segue sem ele", e.toString());
            return Optional.empty();
        }
    }

    static Optional<String> porWindows(String impressora, int statusEfetivo, List<AcompanhamentoWindows.JobNaFila> jobs) {
        if ((statusEfetivo & IMPRESSORA_FORA) != 0) {
            boolean offline = (statusEfetivo & (HistoricoJobWindows.PRINTER_STATUS_OFFLINE | HistoricoJobWindows.PRINTER_STATUS_NOT_AVAILABLE)) != 0;
            return Optional.of("a impressora '" + impressora + "' " + (offline ? "está offline (desligada ou desconectada)" : "está parada (pausada, sem papel ou com erro)"));
        }
        for (AcompanhamentoWindows.JobNaFila j : jobs) {
            String doc = j.documento() == null ? "" : j.documento();
            if (doc.startsWith(PREFIXO_JOB_GAVETA)) {
                return Optional.of("já há um comando de gaveta parado na fila da impressora '" + impressora + "'");
            }
            if ((j.status() & JOB_PARADO) != 0) {
                return Optional.of("a fila da impressora '" + impressora + "' tem um trabalho parado");
            }
        }
        return Optional.empty();
    }

    static Optional<String> porCups(String impressora, Optional<ClienteIppCups.Fila> fila) {
        if (fila.isEmpty()) {
            return Optional.empty();
        }
        if (fila.get().estado() == IPP_PRINTER_STOPPED) {
            return Optional.of("a fila da impressora '" + impressora + "' está parada");
        }
        for (String motivo : fila.get().motivos()) {
            String m = motivo == null ? "" : motivo.toLowerCase(Locale.ROOT);
            if (MOTIVOS_CUPS_FORA.stream().anyMatch(m::contains)) {
                return Optional.of("a impressora '" + impressora + "' não está recebendo (" + m + ")");
            }
        }
        return Optional.empty();
    }
}

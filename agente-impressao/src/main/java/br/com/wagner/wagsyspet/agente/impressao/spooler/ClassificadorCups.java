package br.com.wagner.wagsyspet.agente.impressao.spooler;

import br.com.wagner.wagsyspet.agente.impressao.spooler.EstadoSpooler.Motivo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Classificação PURA da saída do {@code lpstat} com {@code LC_ALL=C} (plano F6 D9; testada com saídas reais do CUPS 2.4):
 * <pre>
 *   lpstat -W not-completed -l -o &lt;fila&gt;   → o job ainda está na fila?
 *   lpstat -W completed     -l -o &lt;fila&gt;   → terminou? "completed" do IPP inclui cancelado e abortado (RFC 8011 §4.2.6): quem
 *                                           distingue é a linha "Alerts:" (job-state-reasons)
 *   lpstat -l -p &lt;fila&gt;                     → por que está parado (fila desabilitada, impressora inalcançável, sem papel…)
 * </pre>
 * Formato do job: {@code "<fila>-<n> <usuário> <bytes> <data>"} seguido de linhas com TAB ({@code Status:}, {@code Alerts:},
 * {@code queued for}). O id é casado como PRIMEIRO campo da linha — {@code PDF-4} não casa com {@code PDF-48}.
 */
public final class ClassificadorCups {

    private ClassificadorCups() {
    }

    public static EstadoSpooler classificar(String jobId, String naoCompletos, String completos, String impressora) {
        Optional<Bloco> terminado = bloco(completos, jobId);
        if (terminado.isPresent()) {
            return classificarTerminado(jobId, terminado.get());
        }
        Optional<Bloco> naFila = bloco(naoCompletos, jobId);
        if (naFila.isPresent()) {
            return classificarPendente(jobId, naFila.get(), impressora == null ? "" : impressora);
        }
        return EstadoSpooler.desconhecido(Motivo.SUMIU_DA_FILA, "CUPS job " + jobId + " não está nem na fila nem no histórico (PreserveJobHistory desligado?)");
    }

    private static EstadoSpooler classificarTerminado(String jobId, Bloco b) {
        String alertas = b.valor("Alerts:").toLowerCase(Locale.ROOT);
        String detalhe = "CUPS job " + jobId + " (" + (alertas.isBlank() ? "sem job-state-reasons" : alertas) + ")";
        if (alertas.contains("job-canceled")) {
            return EstadoSpooler.falhou(Motivo.CANCELADO, detalhe);
        }
        if (alertas.contains("aborted")) {
            return EstadoSpooler.falhou(Motivo.ABORTADO, detalhe);
        }
        if (alertas.contains("job-completed-with-errors")) {
            return EstadoSpooler.falhou(Motivo.ERRO_DRIVER, detalhe);
        }
        if (alertas.contains("job-completed-successfully") || alertas.contains("job-completed-with-warnings")) {
            return EstadoSpooler.impresso(detalhe + " — dados entregues ao dispositivo");
        }
        // terminou sem dizer como (CUPS antigo sem job-state-reasons no lpstat): não afirmar "impresso"
        return EstadoSpooler.desconhecido(null, detalhe + " — terminou sem motivo informado");
    }

    private static EstadoSpooler classificarPendente(String jobId, Bloco job, String impressora) {
        String fila = impressora.toLowerCase(Locale.ROOT);
        String statusJob = job.valor("Status:");
        String detalhe = "CUPS job " + jobId + " ainda na fila"
                + (statusJob.isBlank() ? "" : " — " + statusJob)
                + primeiraLinha(impressora).map(l -> " [" + l + "]").orElse("");
        if (fila.contains(" disabled since") || alertasDaFila(impressora).contains("paused")) {
            return EstadoSpooler.pendente(Motivo.FILA_PARADA, detalhe);
        }
        String alertas = alertasDaFila(impressora);
        if (alertas.contains("media-empty") || alertas.contains("media-needed")) {
            return EstadoSpooler.pendente(Motivo.SEM_PAPEL, detalhe);
        }
        if (alertas.contains("door-open") || alertas.contains("cover-open")) {
            return EstadoSpooler.pendente(Motivo.TAMPA_ABERTA, detalhe);
        }
        if (alertas.contains("connecting-to-device") || alertas.contains("offline")
                || fila.contains("may not exist or is unavailable") || statusJob.toLowerCase(Locale.ROOT).contains("may not exist or is unavailable")) {
            return EstadoSpooler.pendente(Motivo.IMPRESSORA_OFFLINE, detalhe);
        }
        return EstadoSpooler.pendente(null, detalhe);
    }

    /** {@code Alerts:} do {@code lpstat -l -p} (printer-state-reasons), em minúsculas; "none" vira vazio. */
    private static String alertasDaFila(String impressora) {
        for (String linha : impressora.split("\\R")) {
            String t = linha.trim();
            if (t.startsWith("Alerts:")) {
                String v = t.substring("Alerts:".length()).trim().toLowerCase(Locale.ROOT);
                return v.equals("none") ? "" : v;
            }
        }
        return "";
    }

    private static Optional<String> primeiraLinha(String texto) {
        return texto.lines().map(String::trim).filter(l -> !l.isEmpty()).findFirst();
    }

    /** Linha do job + as linhas com TAB que a seguem. */
    private record Bloco(List<String> linhas) {
        String valor(String rotulo) {
            for (String l : linhas) {
                String t = l.trim();
                if (t.startsWith(rotulo)) {
                    return t.substring(rotulo.length()).trim();
                }
            }
            return "";
        }
    }

    private static Optional<Bloco> bloco(String saida, String jobId) {
        if (saida == null || saida.isBlank() || jobId == null || jobId.isBlank()) {
            return Optional.empty();
        }
        String[] linhas = saida.split("\\R");
        for (int i = 0; i < linhas.length; i++) {
            String l = linhas[i];
            if (l.isEmpty() || Character.isWhitespace(l.charAt(0))) {
                continue; // linha de detalhe (TAB), não de job
            }
            String primeiroCampo = l.split("\\s+", 2)[0];
            if (!primeiroCampo.equals(jobId)) {
                continue;
            }
            List<String> bloco = new ArrayList<>();
            for (int j = i + 1; j < linhas.length && !linhas[j].isEmpty() && Character.isWhitespace(linhas[j].charAt(0)); j++) {
                bloco.add(linhas[j]);
            }
            return Optional.of(new Bloco(bloco));
        }
        return Optional.empty();
    }
}

package br.com.wagner.wagsyspet.agente.impressao.spooler;

import br.com.wagner.wagsyspet.agente.impressao.spooler.EstadoSpooler.Estado;
import br.com.wagner.wagsyspet.agente.impressao.spooler.EstadoSpooler.Motivo;

import java.time.Duration;
import java.util.List;
import java.util.function.Function;

/**
 * Acompanhamento de um job do CUPS pelo {@code request id} que o {@code lp} devolveu (Linux/macOS — plano F6 D9). Cada
 * {@link #consultar()} roda o {@code lpstat} (prazo próprio, {@code LC_ALL=C}) e classifica com o {@link ClassificadorCups}.
 * <p>Ordem: PRIMEIRO {@code -W not-completed}, depois {@code -W completed} — ao contrário, um job que termina entre as duas
 * consultas sumiria das duas listas e viraria "sumiu da fila". {@code -W} e {@code -l} vêm ANTES de {@code -o} (o lpstat aplica as
 * opções na ordem em que aparecem). Não usar {@code -W successful}/{@code all}: só existem no CUPS master.
 */
public final class AcompanhamentoCups implements AcompanhamentoSpooler {

    static final String LPSTAT = "/usr/bin/lpstat";
    /** O {@code lp} aceitou mas a saída não trouxe um id reconhecível. */
    public static final String SEM_ID = "?";
    static final Duration PRAZO_LPSTAT = Duration.ofSeconds(5);

    private final String fila;
    private final String jobId;
    private final Function<List<String>, ComandoSpooler.Saida> lpstat;
    private volatile EstadoSpooler terminal;

    public AcompanhamentoCups(String fila, String jobId) {
        this(fila, jobId, ComandoSpooler.real(PRAZO_LPSTAT));
    }

    AcompanhamentoCups(String fila, String jobId, Function<List<String>, ComandoSpooler.Saida> lpstat) {
        this.fila = fila;
        this.jobId = jobId;
        this.lpstat = lpstat;
    }

    public static boolean lpstatDisponivel() {
        return java.nio.file.Files.isExecutable(java.nio.file.Path.of(LPSTAT));
    }

    public String jobId() {
        return jobId;
    }

    @Override
    public EstadoSpooler consultar() {
        EstadoSpooler t = terminal;
        if (t != null) {
            return t;
        }
        EstadoSpooler e = consultarAgora();
        if (e.encerrado()) {
            terminal = e;
        }
        return e;
    }

    private EstadoSpooler consultarAgora() {
        if (jobId == null || jobId.isBlank() || SEM_ID.equals(jobId)) {
            return EstadoSpooler.desconhecido(Motivo.SEM_REGISTRO, "o lp aceitou o job mas não devolveu um request id reconhecível");
        }
        ComandoSpooler.Saida naFila = lpstat.apply(List.of(LPSTAT, "-W", "not-completed", "-l", "-o", fila));
        if (!naFila.ok()) {
            return indisponivel(naFila);
        }
        EstadoSpooler porFila = ClassificadorCups.classificar(jobId, naFila.texto(), "", "");
        if (porFila.estado() == Estado.PENDENTE) {
            ComandoSpooler.Saida impressora = lpstat.apply(List.of(LPSTAT, "-l", "-p", fila));
            return ClassificadorCups.classificar(jobId, naFila.texto(), "", impressora.ok() ? impressora.texto() : "");
        }
        ComandoSpooler.Saida historico = lpstat.apply(List.of(LPSTAT, "-W", "completed", "-l", "-o", fila));
        if (!historico.ok()) {
            return indisponivel(historico);
        }
        return ClassificadorCups.classificar(jobId, "", historico.texto(), "");
    }

    /** Consulta falhou: NÃO encerra — o CUPS pode voltar dentro da janela de observação. */
    private static EstadoSpooler indisponivel(ComandoSpooler.Saida s) {
        String texto = s.texto() == null ? "" : s.texto().strip();
        return new EstadoSpooler(Estado.DESCONHECIDO, Motivo.CONSULTA_INDISPONIVEL,
                "lpstat " + (s.estourou() ? "sem resposta" : "saiu com " + s.exit()) + (texto.isEmpty() ? "" : ": " + texto), false);
    }
}

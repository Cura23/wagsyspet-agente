package br.com.wagner.wagsyspet.agente.impressao.spooler;

import br.com.wagner.wagsyspet.agente.impressao.spooler.EstadoSpooler.Estado;
import br.com.wagner.wagsyspet.agente.impressao.spooler.EstadoSpooler.Motivo;

import java.time.Duration;
import java.util.List;
import java.util.function.Function;

/**
 * Acompanhamento de um job do CUPS pelo {@code request id} que o {@code lp} devolveu (Linux/macOS — plano F6 D9). Cada
 * {@link #consultar()} pergunta o {@code job-state} por IPP ao cupsd local ({@link ClienteIppCups}); só se o IPP não responder
 * (cupsd só no socket de domínio, CUPS_SERVER remoto) cai na RESERVA: {@code lpstat} com prazo e saída forçada para inglês.
 * <p>Ordem do lpstat: PRIMEIRO {@code -W not-completed}, depois {@code -W completed} — ao contrário, um job que termina entre as duas
 * consultas sumiria das duas listas e viraria "sumiu da fila". {@code -W} e {@code -l} vêm ANTES de {@code -o} (o lpstat aplica as
 * opções na ordem em que aparecem). Não usar {@code -W successful}/{@code all}: só existem no CUPS master.
 */
public final class AcompanhamentoCups implements AcompanhamentoSpooler {

    static final String LPSTAT = "/usr/bin/lpstat";
    /** O {@code lp} aceitou mas a saída não trouxe um id reconhecível. */
    public static final String SEM_ID = "?";
    static final Duration PRAZO_LPSTAT = Duration.ofSeconds(5);

    static final Duration PRAZO_IPP = Duration.ofSeconds(3);

    /** O que o acompanhamento precisa do IPP (a real é o {@link ClienteIppCups}). */
    interface FonteIpp {
        java.util.Optional<ClienteIppCups.Job> job(int numero) throws java.io.IOException;

        java.util.Optional<ClienteIppCups.Fila> fila(String nome) throws java.io.IOException;
    }

    private final String fila;
    private final String jobId;
    private final FonteIpp ipp;
    private final Function<List<String>, ComandoSpooler.Saida> lpstat;
    private volatile EstadoSpooler terminal;

    public AcompanhamentoCups(String fila, String jobId) {
        this(fila, jobId, ippLocal(), ComandoSpooler.real(PRAZO_LPSTAT));
    }

    AcompanhamentoCups(String fila, String jobId, FonteIpp ipp, Function<List<String>, ComandoSpooler.Saida> lpstat) {
        this.fila = fila;
        this.jobId = jobId;
        this.ipp = ipp;
        this.lpstat = lpstat;
    }

    private static FonteIpp ippLocal() {
        ClienteIppCups cliente = new ClienteIppCups(ClienteIppCups.CUPSD_LOCAL, PRAZO_IPP);
        return new FonteIpp() {
            @Override
            public java.util.Optional<ClienteIppCups.Job> job(int numero) throws java.io.IOException {
                return cliente.job(numero);
            }

            @Override
            public java.util.Optional<ClienteIppCups.Fila> fila(String nome) throws java.io.IOException {
                return cliente.fila(nome);
            }
        };
    }

    /** {@code "EPSON-TM-20-7"} → 7; vazio se o sufixo não for número. */
    static java.util.OptionalInt numeroDoJob(String jobId) {
        int hifen = jobId == null ? -1 : jobId.lastIndexOf('-');
        if (hifen < 0 || hifen == jobId.length() - 1) {
            return java.util.OptionalInt.empty();
        }
        try {
            return java.util.OptionalInt.of(Integer.parseInt(jobId.substring(hifen + 1)));
        } catch (NumberFormatException e) {
            return java.util.OptionalInt.empty();
        }
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
            // o job FOI aceito; só não há como perguntar por ele (SEM_REGISTRO fica para "id que o agente nunca viu")
            return EstadoSpooler.desconhecido(Motivo.CONSULTA_INDISPONIVEL, "o lp aceitou o job mas não devolveu um request id reconhecível");
        }
        java.util.OptionalInt numero = numeroDoJob(jobId);
        if (numero.isPresent()) {
            try {
                java.util.Optional<ClienteIppCups.Job> job = ipp.job(numero.getAsInt());
                return ClassificadorCups.porIpp(jobId, job, () -> {
                    try {
                        return ipp.fila(fila);
                    } catch (java.io.IOException e) {
                        return java.util.Optional.empty();
                    }
                });
            } catch (java.io.IOException | RuntimeException e) {
                // sem IPP em localhost: segue pela reserva (lpstat)
            }
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

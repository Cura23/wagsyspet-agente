package br.com.wagner.wagsyspet.agente.impressao.spooler;

import java.util.Objects;

/**
 * O que o SPOOLER do SO diz de um job depois de aceito (plano F6 D9). Semântica honesta, que vale no fio e na tela:
 * <ul>
 *   <li>{@link Estado#IMPRESSO} = o spooler concluiu = <b>dados entregues ao dispositivo</b> — NÃO é "papel saiu" (CUPS:
 *       {@code CUPS_BACKEND_OK} = "successfully transmitted to the device"; Windows: {@code JOB_STATUS_COMPLETE} = "sent to the
 *       printer, but the job may not be printed yet"). Térmica USB sem status bidirecional "imprime" sem papel;</li>
 *   <li>{@link Estado#PENDENTE} = continua na fila (impressora desligada, fila parada, sem papel…) — o estado que mais vale ao
 *       operador: "confira antes de reimprimir";</li>
 *   <li>{@link Estado#FALHOU} = terminal sem imprimir (cancelado, abortado, erro do driver);</li>
 *   <li>{@link Estado#DESCONHECIDO} = não deu para saber — nunca vira "impresso" por inferência.</li>
 * </ul>
 *
 * @param motivo    whitelist; {@code null} quando não há o que dizer
 * @param detalhe   texto para o log/suporte (sempre presente)
 * @param encerrado {@code true} = não muda mais (pode parar de observar)
 */
public record EstadoSpooler(Estado estado, Motivo motivo, String detalhe, boolean encerrado) {

    public enum Estado { IMPRESSO, FALHOU, PENDENTE, DESCONHECIDO }

    public enum Motivo {
        CANCELADO, ABORTADO, ERRO_DRIVER, IMPRESSORA_OFFLINE, SEM_PAPEL, TAMPA_ABERTA, FILA_PARADA, INTERVENCAO,
        SUMIU_DA_FILA, CONSULTA_INDISPONIVEL, SEM_SUPORTE, SEM_REGISTRO
    }

    public EstadoSpooler {
        Objects.requireNonNull(estado);
        detalhe = detalhe == null ? "" : detalhe;
    }

    public static EstadoSpooler impresso(String detalhe) {
        return new EstadoSpooler(Estado.IMPRESSO, null, detalhe, true);
    }

    public static EstadoSpooler falhou(Motivo motivo, String detalhe) {
        return new EstadoSpooler(Estado.FALHOU, motivo, detalhe, true);
    }

    public static EstadoSpooler pendente(Motivo motivo, String detalhe) {
        return new EstadoSpooler(Estado.PENDENTE, motivo, detalhe, false);
    }

    public static EstadoSpooler desconhecido(Motivo motivo, String detalhe) {
        return new EstadoSpooler(Estado.DESCONHECIDO, motivo, detalhe, true);
    }

    /** O mesmo estado, fechado (fim da janela de observação: um PENDENTE vira a resposta final). */
    public EstadoSpooler encerrar() {
        return encerrado ? this : new EstadoSpooler(estado, motivo, detalhe, true);
    }
}

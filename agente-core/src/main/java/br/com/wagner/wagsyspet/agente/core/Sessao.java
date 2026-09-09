package br.com.wagner.wagsyspet.agente.core;

import java.util.concurrent.ScheduledFuture;

/**
 * Estado de UMA conexão WebSocket (guardado no {@code attachment} da conexão). Campos {@code volatile}: {@code onOpen},
 * {@code onMessage} e {@code onClose} podem rodar em threads diferentes (worker da conexão, selector, checador de
 * conexão perdida).
 */
final class Sessao {

    enum Fase { PRE_AUTH, AUTENTICADA }

    final String origin;
    final long abertaEmNanos;
    volatile Fase fase = Fase.PRE_AUTH;
    volatile boolean helloRecebido;
    volatile long ultimaAtividadeNanos;
    /** {@code jti} do ticket aceito — vai para o log de cada job (rastreabilidade sem guardar o ticket). */
    volatile String jti;
    volatile ScheduledFuture<?> prazoAuth;

    Sessao(String origin, long agoraNanos) {
        this.origin = origin == null ? "" : origin;
        this.abertaEmNanos = agoraNanos;
        this.ultimaAtividadeNanos = agoraNanos;
    }

    boolean autenticada() {
        return fase == Fase.AUTENTICADA;
    }

    void tocar(long agoraNanos) {
        ultimaAtividadeNanos = agoraNanos;
    }
}

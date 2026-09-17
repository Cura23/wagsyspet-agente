package br.com.wagner.wagsyspet.agente.impressao.spooler;

/**
 * Alça para perguntar ao spooler do SO o que aconteceu com UM job já aceito (plano F6 D9). Nasce junto com o
 * {@code ACEITO_SPOOLER}: no CUPS com o {@code request id} do {@code lp}; no Windows armada ANTES do {@code print()} (o job some da
 * fila ao imprimir). Quem observa é o {@code ObservadorImpressao} do core, em executor próprio — nunca a thread da fila de impressão.
 */
public interface AcompanhamentoSpooler extends AutoCloseable {

    /** Estado corrente. Tempo LIMITADO (cada consulta tem prazo) e nunca lança. Depois de um estado encerrado, devolve sempre o mesmo. */
    EstadoSpooler consultar();

    /** Solta o que houver de nativo (handles do Windows). Idempotente. */
    @Override
    default void close() {
    }
}

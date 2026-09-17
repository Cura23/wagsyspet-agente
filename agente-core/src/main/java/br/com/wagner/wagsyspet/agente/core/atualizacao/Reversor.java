package br.com.wagner.wagsyspet.agente.core.atualizacao;

/**
 * Volta à versão anterior quando a nova não confirmou saúde em {@value GerenteAtualizacao#BOOTS_ATE_REVERTER} boots (plano F6 D4).
 * A mecânica é por SO (lote L2: reinstalar o instalador anterior guardado / mover a pasta de volta); {@link #NENHUM} só registra.
 */
public interface Reversor {
    /** @return {@code true} se conseguiu reverter (o processo deve encerrar para o supervisor relançar a versão anterior) */
    boolean reverter(EstadoAtualizacao.EmAplicacao emAplicacao);

    /**
     * {@code true} = o {@link #reverter} só ENTREGOU a volta a outro processo (Windows: o atualizador de fora roda o MSI anterior depois
     * que o agente sair). A sentinela então NÃO marca "revertida": quem fecha o estado é quem instala, com o resultado real.
     */
    default boolean adiada() {
        return false;
    }

    Reversor NENHUM = ap -> false;
}

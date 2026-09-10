package br.com.wagner.wagsyspet.agente.core.atualizacao;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Lança o ATUALIZADOR (processo externo que aplica o plano depois que o agente sair) de forma que ele sobreviva à saída do agente —
 * a mecânica é por SO (plano F6 D2, lote L2); o agente só entrega o {@code plano.json}.
 */
public interface LancadorAtualizador {
    void lancar(Path plano) throws IOException;
}

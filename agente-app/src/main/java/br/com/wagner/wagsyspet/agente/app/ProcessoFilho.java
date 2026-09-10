package br.com.wagner.wagsyspet.agente.app;

import java.util.List;
import java.util.Map;

/**
 * {@link ProcessBuilder} para lançar OUTRO launcher do jpackage a partir de um processo que já roda dentro de um: o launcher deixa
 * {@code _JPACKAGE_LAUNCHER} (dados de lançamento serializados) no ambiente da JVM, e um launcher filho que HERDA essa variável
 * reaproveita os dados do pai em vez de ler os próprios argumentos — o argumento vira opção da JVM e ela aborta com
 * "Unrecognized option" (achado do e2e do F6-L1). Toda criação de processo do agente passa por aqui.
 */
final class ProcessoFilho {

    static final String PREFIXO_JPACKAGE = "_JPACKAGE";

    private ProcessoFilho() {
    }

    static ProcessBuilder novo(List<String> comando) {
        ProcessBuilder pb = new ProcessBuilder(comando);
        limparAmbienteJpackage(pb.environment());
        return pb;
    }

    static void limparAmbienteJpackage(Map<String, String> ambiente) {
        ambiente.keySet().removeIf(k -> k.startsWith(PREFIXO_JPACKAGE));
    }
}

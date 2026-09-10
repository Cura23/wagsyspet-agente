package br.com.wagner.wagsyspet.agente.app;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ProcessoFilho — um launcher do jpackage lançado de dentro de outro não herda _JPACKAGE_LAUNCHER (senão a JVM filha aborta)")
class ProcessoFilhoTest {

    @Test
    void limpaMarcadoresDoJpackage() {
        Map<String, String> env = new HashMap<>(Map.of("_JPACKAGE_LAUNCHER", "{\"dados\":1}", "_JPACKAGE_OUTRA", "x", "PATH", "/usr/bin", "JAVA_TOOL_OPTIONS", "-Dx=1"));
        ProcessoFilho.limparAmbienteJpackage(env);
        assertThat(env).containsOnlyKeys("PATH", "JAVA_TOOL_OPTIONS");
        ProcessBuilder pb = ProcessoFilho.novo(List.of("true"));
        assertThat(pb.environment().keySet()).noneMatch(k -> k.startsWith(ProcessoFilho.PREFIXO_JPACKAGE));
        assertThat(pb.command()).containsExactly("true");
    }
}

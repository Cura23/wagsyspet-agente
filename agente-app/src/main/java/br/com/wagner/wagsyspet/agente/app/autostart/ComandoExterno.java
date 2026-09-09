package br.com.wagner.wagsyspet.agente.app.autostart;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Executa {@code reg}/{@code launchctl}/{@code systemctl}; injetável para os testes não tocarem o SO. */
public interface ComandoExterno {

    record Saida(int exit, String texto) {
        public boolean ok() {
            return exit == 0;
        }
    }

    Saida executar(List<String> comando) throws IOException;

    /** Implementação real: stdout+stderr juntos, prazo de 20 s. */
    static ComandoExterno real() {
        return comando -> {
            Process p = new ProcessBuilder(comando).redirectErrorStream(true).start();
            try {
                if (!p.waitFor(Duration.ofSeconds(20).toMillis(), TimeUnit.MILLISECONDS)) {
                    p.destroyForcibly();
                    throw new IOException("comando não respondeu em 20 s: " + comando);
                }
                String texto = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                return new Saida(p.exitValue(), texto);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                p.destroyForcibly();
                throw new IOException("interrompido: " + comando, e);
            }
        };
    }
}

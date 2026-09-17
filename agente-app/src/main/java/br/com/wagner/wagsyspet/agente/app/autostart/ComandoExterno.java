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
        return real(Duration.ofSeconds(20));
    }

    /** Idem com prazo próprio (um upgrade MSI ou uma extração de 70 MB passam de 20 s). */
    static ComandoExterno real(Duration prazo) {
        return comando -> {
            Process p = new ProcessBuilder(comando).redirectErrorStream(true).start();
            // drenar ENQUANTO espera: o pipe do filho tem 4 KB no Windows (64 KB no Linux); ler só depois do waitFor deixava quem escreve
            // mais que isso (ex.: schtasks /query /xml) bloqueado até estourar o prazo (adversarial L3 r2)
            java.util.concurrent.CompletableFuture<byte[]> saida = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try (java.io.InputStream in = p.getInputStream()) {
                    return in.readAllBytes();
                } catch (IOException e) {
                    return new byte[0];
                }
            });
            try {
                if (!p.waitFor(prazo.toMillis(), TimeUnit.MILLISECONDS)) {
                    p.destroyForcibly();
                    throw new IOException("comando não respondeu em " + prazo.toSeconds() + " s: " + comando);
                }
                byte[] bytes;
                try {
                    bytes = saida.get(5, TimeUnit.SECONDS); // um neto pode segurar o pipe aberto depois de o filho sair
                } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
                    bytes = new byte[0];
                }
                return new Saida(p.exitValue(), new String(bytes, StandardCharsets.UTF_8).trim());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                p.destroyForcibly();
                throw new IOException("interrompido: " + comando, e);
            }
        };
    }
}

package br.com.wagner.wagsyspet.agente.impressao.spooler;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * Roda um comando de consulta do spooler ({@code lpstat}) com prazo REAL e {@code LC_ALL=C}: a saída é drenada em thread própria
 * enquanto o {@code waitFor} espera (um {@code lpstat} pendurado num CUPS remoto fora do ar não pode prender quem consulta).
 */
public final class ComandoSpooler {

    /** @param estourou o prazo venceu e o processo foi morto */
    public record Saida(int exit, String texto, boolean estourou) {
        public boolean ok() {
            return !estourou && exit == 0;
        }
    }

    private ComandoSpooler() {
    }

    /**
     * Força a saída do CUPS para inglês (o parse depende dos rótulos). Só {@code LC_ALL=C} NÃO basta: o {@code cupsLangGet} lê
     * {@code LC_MESSAGES} ANTES de {@code LC_ALL}, e no macOS ignora os dois salvo se {@code SOFTWARE} estiver definido (aí usa
     * {@code LANG}) — adversarial L4, reproduzido com {@code LC_ALL=C LC_MESSAGES=pt_BR.UTF-8 lpstat}.
     */
    public static void emIngles(java.util.Map<String, String> ambiente) {
        ambiente.put("LC_ALL", "C");
        ambiente.put("LC_MESSAGES", "C");
        ambiente.put("LANG", "C");
        ambiente.remove("LANGUAGE");
        ambiente.put("SOFTWARE", "AgroEase");
    }

    public static Function<List<String>, Saida> real(Duration prazo) {
        return cmd -> rodar(cmd, prazo);
    }

    static Saida rodar(List<String> cmd, Duration prazo) {
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        emIngles(pb.environment());
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            return new Saida(-1, "não consegui executar " + cmd.get(0) + ": " + e.getMessage(), false);
        }
        CompletableFuture<String> saida = new CompletableFuture<>();
        Thread leitor = new Thread(() -> {
            try (InputStream in = p.getInputStream()) {
                saida.complete(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                saida.complete("");
            }
        }, "spooler-stdout");
        leitor.setDaemon(true);
        leitor.start();
        try {
            if (!p.waitFor(prazo.toMillis(), TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                return new Saida(-1, cmd.get(0) + " não respondeu em " + prazo.toSeconds() + " s", true);
            }
            String texto;
            try {
                texto = saida.get(2, TimeUnit.SECONDS);
            } catch (TimeoutException | ExecutionException e) {
                texto = "";
            }
            return new Saida(p.exitValue(), texto, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            return new Saida(-1, "consulta interrompida", true);
        }
    }
}

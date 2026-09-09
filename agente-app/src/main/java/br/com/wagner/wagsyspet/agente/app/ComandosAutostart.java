package br.com.wagner.wagsyspet.agente.app;

import br.com.wagner.wagsyspet.agente.app.autostart.Autostart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Optional;

/**
 * "Iniciar com o sistema" pela linha de comando ({@code --instalar}/{@code --desinstalar}) e automaticamente após um
 * pareamento bem-sucedido (1º run interativo — plano F3 D22). Só registra o launcher GUI do binário instalado; rodando
 * pelo {@code java} cru (dev) não há o que registrar.
 */
final class ComandosAutostart {

    private static final Logger log = LoggerFactory.getLogger(ComandosAutostart.class);

    private final Autostart autostart;
    private final PrintStream out;
    private final PrintStream err;

    ComandosAutostart(Autostart autostart, PrintStream out, PrintStream err) {
        this.autostart = autostart;
        this.out = out;
        this.err = err;
    }

    static ComandosAutostart padrao(PrintStream out, PrintStream err) {
        return new ComandosAutostart(Autostart.paraEsteSo(), out, err);
    }

    int instalar() {
        Optional<Path> launcher = Autostart.launcherDesteProcesso();
        if (launcher.isEmpty()) {
            err.println("Este processo está rodando pelo Java direto (desenvolvimento), não pelo agente instalado — nada para registrar.");
            err.println("Use o binário instalado (ou -Dagente.launcher=<caminho do launcher>).");
            return Main.SAIDA_FALHA;
        }
        try {
            boolean agenteAberto = agenteEmExecucao();
            autostart.instalar(launcher.get(), !agenteAberto);
            out.println("Iniciar com o sistema: ATIVADO para " + launcher.get());
            out.println("  " + autostart.descricao());
            if (agenteAberto) {
                out.println("  (o agente já está aberto; a inicialização automática vale a partir do próximo login)");
            }
            return 0;
        } catch (IOException e) {
            err.println("Não foi possível ativar \"iniciar com o sistema\": " + e.getMessage());
            return Main.SAIDA_FALHA;
        }
    }

    int desinstalar() {
        try {
            boolean havia = autostart.instalado();
            autostart.desinstalar();
            out.println(havia ? "Iniciar com o sistema: DESATIVADO." : "Iniciar com o sistema já estava desativado.");
            return 0;
        } catch (IOException e) {
            err.println("Não foi possível desativar \"iniciar com o sistema\": " + e.getMessage());
            return Main.SAIDA_FALHA;
        }
    }

    /** Lock de instância tomado por OUTRO processo = o agente de desktop está aberto. */
    private static boolean agenteEmExecucao() {
        try {
            var dirs = br.com.wagner.wagsyspet.agente.core.pareamento.DiretoriosDoAgente.padrao();
            Optional<TravaDeInstancia> t = TravaDeInstancia.tentar(dirs.lock());
            if (t.isEmpty()) {
                return true;
            }
            t.get().close();
            return false;
        } catch (IOException e) {
            return false;
        }
    }

    /** Linha do {@code --status}/{@code --diagnostico}. */
    String linhaStatus() {
        try {
            return (autostart.instalado() ? "ativado" : "desativado") + " — " + autostart.descricao();
        } catch (IOException e) {
            return "desconhecido (" + e.getMessage() + ")";
        }
    }

    /**
     * Após parear com sucesso: ativa se der, nunca falha o pareamento. Devolve a frase para mostrar ao lojista, ou vazio
     * quando não se aplica (dev).
     */
    Optional<String> ativarAposPareamento() {
        Optional<Path> launcher = Autostart.launcherDesteProcesso();
        if (launcher.isEmpty()) {
            log.info("Pareado rodando pelo java (dev): autostart não registrado");
            return Optional.empty();
        }
        try {
            if (!autostart.instalado()) {
                autostart.instalar(launcher.get(), false); // o agente está rodando agora: não lançar outro
            }
            return Optional.of("O agente vai iniciar junto com o sistema neste computador.");
        } catch (IOException e) {
            log.warn("Autostart não ativado após o pareamento: {}", e.toString());
            return Optional.of("Não consegui ativar \"iniciar com o sistema\" (" + e.getMessage() + "); use --instalar depois.");
        }
    }
}

package br.com.wagner.wagsyspet.agente.app.atualizacao;

import br.com.wagner.wagsyspet.agente.app.AplicadorAtualizacao;
import br.com.wagner.wagsyspet.agente.app.autostart.Autostart;
import br.com.wagner.wagsyspet.agente.app.autostart.ComandoExterno;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Relançar o agente DEPOIS da troca pelo SUPERVISOR que já o gerencia (plano F6 D2): um processo lançado direto pelo atualizador
 * ficaria órfão do supervisor (e no Windows a tarefa keepalive do L3 tentaria subir uma 2ª instância por minuto). Linux com unit do
 * systemd → {@code systemctl --user start}; macOS com LaunchAgent → {@code launchctl kickstart -k}; sem supervisor → processo direto.
 */
public final class Relancadores {

    private static final Logger log = LoggerFactory.getLogger(Relancadores.class);
    static final String UNIT_SYSTEMD = "agroease-agente-impressao.service";

    private Relancadores() {
    }

    public static AplicadorAtualizacao.Relancador paraEsteSo(Consumer<Path> direto) {
        return paraSo(System.getProperty("os.name"), Path.of(System.getProperty("user.home", ".")), System.getenv(), ComandoExterno.real(), direto);
    }

    static AplicadorAtualizacao.Relancador paraSo(String osName, Path home, Map<String, String> env, ComandoExterno cmd, Consumer<Path> direto) {
        return launcher -> {
            if (!Plataforma.windows(osName) && !Plataforma.mac(osName)) {
                Path unit = home.resolve(".config").resolve("systemd").resolve("user").resolve(UNIT_SYSTEMD);
                if (Files.exists(unit)) {
                    executar(cmd, List.of("systemctl", "--user", "start", UNIT_SYSTEMD), "systemd");
                    return;
                }
                if (SystemdRun.sobSystemd(env)) { // sem unit, mas dentro de uma unidade: um filho direto morreria com ela
                    try {
                        executar(cmd, SystemdRun.comando("agroease-agente", env, List.of(launcher.toString())), "systemd-run");
                        return;
                    } catch (IOException e) {
                        log.warn("systemd-run indisponível ({}); relançando direto", e.getMessage());
                    }
                }
            } else if (Plataforma.mac(osName)) {
                Path plist = home.resolve("Library").resolve("LaunchAgents").resolve(Autostart.ID + ".plist");
                if (Files.exists(plist)) {
                    String uid = env.getOrDefault("UID", "");
                    if (uid.matches("\\d+")) {
                        executar(cmd, List.of("launchctl", "kickstart", "-k", "gui/" + uid + "/" + Autostart.ID), "launchd");
                        return;
                    }
                    log.warn("LaunchAgent presente mas UID desconhecido; relançando direto");
                }
            }
            direto.accept(launcher);
        };
    }

    private static void executar(ComandoExterno cmd, List<String> comando, String supervisor) throws IOException {
        ComandoExterno.Saida s = cmd.executar(comando);
        if (s.exit() != 0) {
            throw new IOException("relançamento pelo " + supervisor + " falhou (" + s.exit() + "): " + s.texto());
        }
        log.info("Agente relançado pelo {}", supervisor);
    }
}

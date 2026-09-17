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
 * ficaria órfão do supervisor (e no Windows a tarefa keepalive tentaria subir uma 2ª instância por minuto). Linux com unit do
 * systemd → {@code systemctl --user start}; macOS com LaunchAgent → {@code launchctl kickstart -k}; Windows com a tarefa do Agendador
 * → {@code schtasks /run} (reabilitando-a se o lojista tinha clicado "Sair"); sem supervisor → processo direto.
 */
public final class Relancadores {

    private static final Logger log = LoggerFactory.getLogger(Relancadores.class);
    static final String UNIT_SYSTEMD = "agroease-agente-impressao.service";
    /** = {@code AutostartWindows.TAREFA} (pacote diferente; o teste de contrato cruza os dois). */
    static final String TAREFA_WINDOWS = "AgroEase-Agente-Impressao";

    private Relancadores() {
    }

    public static AplicadorAtualizacao.Relancador paraEsteSo(Consumer<Path> direto) {
        return paraSo(System.getProperty("os.name"), Path.of(System.getProperty("user.home", ".")), System.getenv(), ComandoExterno.real(), direto);
    }

    static AplicadorAtualizacao.Relancador paraSo(String osName, Path home, Map<String, String> env, ComandoExterno cmd, Consumer<Path> direto) {
        return launcher -> {
            try {
                if (peloSupervisor(osName, home, env, cmd, launcher)) {
                    return;
                }
            } catch (IOException | RuntimeException e) {
                // Fecho F6: o supervisor existe mas não relançou (schtasks /run negado, barramento do systemd fora, kickstart ≠ 0).
                // Subir a exceção deixava a loja SEM agente: no Windows a tarefa está PAUSADA (o update a pausou) e só o Run do
                // próximo logon traria o agente de volta. O agente que sobe direto reabilita o keepalive sozinho (retomar).
                log.warn("Supervisor não relançou o agente ({}); relançando direto", e.getMessage());
            }
            direto.accept(launcher);
        };
    }

    /** {@code true} se o supervisor deste SO relançou o agente; {@code false} se não há supervisor instalado (→ direto). */
    private static boolean peloSupervisor(String osName, Path home, Map<String, String> env, ComandoExterno cmd, Path launcher) throws IOException {
        if (Plataforma.windows(osName)) {
            ComandoExterno.Saida q = cmd.executar(List.of("schtasks", "/query", "/tn", TAREFA_WINDOWS));
            if (!q.ok()) {
                return false;
            }
            // pode estar pausada por um "Sair" (o texto do Status é localizado, então não se lê: /enable é idempotente)
            executar(cmd, List.of("schtasks", "/change", "/tn", TAREFA_WINDOWS, "/enable"), "Agendador (reabilitar)");
            executar(cmd, List.of("schtasks", "/run", "/tn", TAREFA_WINDOWS), "Agendador");
            return true;
        }
        if (Plataforma.mac(osName)) {
            Path plist = home.resolve("Library").resolve("LaunchAgents").resolve(Autostart.ID + ".plist");
            if (!Files.exists(plist)) {
                return false;
            }
            String uid = uidDoMac(env, cmd);
            if (uid == null) {
                log.warn("LaunchAgent presente mas UID desconhecido; relançando direto");
                return false;
            }
            executar(cmd, List.of("launchctl", "kickstart", "-k", "gui/" + uid + "/" + Autostart.ID), "launchd");
            return true;
        }
        Path unit = home.resolve(".config").resolve("systemd").resolve("user").resolve(UNIT_SYSTEMD);
        if (Files.exists(unit)) {
            executar(cmd, List.of("systemctl", "--user", "start", UNIT_SYSTEMD), "systemd");
            return true;
        }
        if (SystemdRun.sobSystemd(env)) { // sem unit, mas dentro de uma unidade: um filho direto morreria com ela
            executar(cmd, SystemdRun.comando("agroease-agente", env, List.of(launcher.toString())), "systemd-run");
            return true;
        }
        return false;
    }

    /**
     * {@code UID} é variável INTERNA do bash/zsh, não exportada: o atualizador nasce de {@code open -n -a} (LaunchServices) e o
     * ambiente dele não a tem. Mesmo plano B do {@code AutostartMac}: {@code id -u}. Sem isso o {@code kickstart} era código morto
     * em produção e o agente novo rodava fora do launchd (sem 2º boot para a sentinela reverter).
     */
    private static String uidDoMac(Map<String, String> env, ComandoExterno cmd) {
        String u = env.get("UID");
        if (u != null && u.matches("\\d+")) {
            return u;
        }
        try {
            ComandoExterno.Saida s = cmd.executar(List.of("id", "-u"));
            String texto = s.texto() == null ? "" : s.texto().trim();
            return s.ok() && texto.matches("\\d+") ? texto : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static void executar(ComandoExterno cmd, List<String> comando, String supervisor) throws IOException {
        ComandoExterno.Saida s = cmd.executar(comando);
        if (s.exit() != 0) {
            throw new IOException("relançamento pelo " + supervisor + " falhou (" + s.exit() + "): " + s.texto());
        }
        log.info("Agente relançado pelo {}", supervisor);
    }
}

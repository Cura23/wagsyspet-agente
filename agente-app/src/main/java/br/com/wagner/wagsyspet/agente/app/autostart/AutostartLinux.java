package br.com.wagner.wagsyspet.agente.app.autostart;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Linux: unit {@code systemd --user} ({@code Restart=on-failure}, {@code RestartSec=10}, {@code StartLimitBurst=5} em 300 s)
 * <b>sem {@code enable}</b> — o {@code default.target} do usuário sobe antes da sessão gráfica e a unit nasceria sem
 * {@code DISPLAY}; quem dispara é um {@code .desktop} XDG em {@code ~/.config/autostart} que importa o ambiente gráfico e faz
 * {@code systemctl --user start}. Sem systemd de usuário (WSL, sessão mínima), o {@code .desktop} chama o binário direto.
 * O {@code .deb} instala como root: por isso o autostart é per-user, gravado no 1º run/{@code --instalar}, não no postinst.
 */
final class AutostartLinux implements Autostart {

    private static final Logger log = LoggerFactory.getLogger(AutostartLinux.class);
    static final String UNIT = "agroease-agente-impressao.service";
    static final String DESKTOP = "agroease-agente-impressao.desktop";

    private final Path unit;
    private final Path desktop;
    private final ComandoExterno cmd;

    AutostartLinux(Path home, Map<String, String> env, ComandoExterno cmd) {
        String xdg = env.get("XDG_CONFIG_HOME");
        Path config = xdg != null && !xdg.isBlank() ? Path.of(xdg) : home.resolve(".config");
        this.unit = config.resolve("systemd").resolve("user").resolve(UNIT);
        this.desktop = config.resolve("autostart").resolve(DESKTOP);
        this.cmd = cmd;
    }

    Path unit() {
        return unit;
    }

    Path desktop() {
        return desktop;
    }

    @Override
    public boolean instalado() {
        return Files.exists(desktop);
    }

    @Override
    public void instalar(Path launcher, boolean ativarAgora) throws IOException { // a unit não é iniciada aqui (o .desktop faz no login)
        Path abs = launcher.toAbsolutePath();
        boolean systemd = temSystemdDeUsuario();
        Files.createDirectories(desktop.getParent());
        if (systemd) {
            Files.createDirectories(unit.getParent());
            Files.writeString(unit, conteudoUnit(abs), StandardCharsets.UTF_8);
            cmd.executar(List.of("systemctl", "--user", "daemon-reload"));
            log.info("Unit systemd --user gravada em {}", unit);
        } else {
            Files.deleteIfExists(unit);
            log.info("systemd --user indisponível; o .desktop chama o binário direto (sem reinício automático)");
        }
        Files.writeString(desktop, conteudoDesktop(abs, systemd), StandardCharsets.UTF_8);
        log.info("Autostart XDG gravado em {}", desktop);
    }

    @Override
    public void desinstalar() throws IOException {
        if (Files.exists(unit)) {
            try {
                cmd.executar(List.of("systemctl", "--user", "stop", UNIT));
                cmd.executar(List.of("systemctl", "--user", "reset-failed", UNIT));
            } catch (IOException e) {
                log.debug("systemctl indisponível ao desinstalar: {}", e.toString());
            }
            Files.deleteIfExists(unit);
            try {
                cmd.executar(List.of("systemctl", "--user", "daemon-reload"));
            } catch (IOException ignored) {
                // sem systemd de usuário
            }
        }
        if (Files.deleteIfExists(desktop)) {
            log.info("Autostart removido: {}", desktop);
        }
    }

    @Override
    public String descricao() {
        return "Linux: " + desktop + (Files.exists(unit) ? " → systemd --user " + UNIT + " (reinicia se sair com erro)" : " (chama o binário direto)");
    }

    private boolean temSystemdDeUsuario() {
        try {
            ComandoExterno.Saida s = cmd.executar(List.of("systemctl", "--user", "show-environment"));
            return s.ok();
        } catch (IOException e) {
            return false;
        }
    }

    static String conteudoUnit(Path launcher) {
        return """
                [Unit]
                Description=%s
                PartOf=graphical-session.target
                After=graphical-session.target
                StartLimitIntervalSec=300
                StartLimitBurst=5

                [Service]
                Type=simple
                ExecStart=%s
                Restart=on-failure
                RestartSec=10
                # exit 0 = "não reinicie" (não pareado, 2ª instância); 3/4 = reinicia com backoff
                SuccessExitStatus=0
                """.formatted(NOME_EXIBIDO, aspasUnit(launcher.toString()));
    }

    /** O .desktop importa o ambiente gráfico para a unit (senão o agente nasce headless) e dispara o start. */
    static String conteudoDesktop(Path launcher, boolean systemd) {
        String exec = systemd
                ? "sh -c 'systemctl --user import-environment DISPLAY WAYLAND_DISPLAY XAUTHORITY XDG_RUNTIME_DIR DBUS_SESSION_BUS_ADDRESS 2>/dev/null; systemctl --user start " + UNIT + "'"
                : aspasDesktop(launcher.toString());
        return """
                [Desktop Entry]
                Type=Application
                Name=%s
                Comment=Imprime o cupom do PDV AgroEase direto na impressora deste computador
                Exec=%s
                Terminal=false
                NoDisplay=true
                X-GNOME-Autostart-enabled=true
                """.formatted(NOME_EXIBIDO, exec);
    }

    /** systemd: caminho com espaço entre aspas duplas; aspas internas escapadas. */
    private static String aspasUnit(String caminho) {
        return "\"" + caminho.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** Spec .desktop: aspas duplas em volta; %, \\ e " escapados. */
    private static String aspasDesktop(String caminho) {
        return "\"" + caminho.replace("\\", "\\\\").replace("\"", "\\\"").replace("%", "%%") + "\"";
    }
}

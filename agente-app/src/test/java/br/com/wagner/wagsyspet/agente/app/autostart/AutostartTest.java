package br.com.wagner.wagsyspet.agente.app.autostart;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import br.com.wagner.wagsyspet.agente.app.autostart.ComandoExterno.Saida;

import static org.assertj.core.api.Assertions.assertThat;

/** Plano F3 D22 — autostart por SO com comandos externos FALSOS (nada toca o registro/launchd/systemd da máquina de teste). */
@DisplayName("Autostart — Windows (Run), macOS (LaunchAgent), Linux (systemd --user + .desktop)")
class AutostartTest {

    /** Grava os comandos e responde conforme a função. */
    static final class CmdFake implements ComandoExterno {
        final List<List<String>> chamadas = new ArrayList<>();
        Function<List<String>, Saida> resposta = c -> new Saida(0, "");

        @Override
        public Saida executar(List<String> comando) {
            chamadas.add(List.copyOf(comando));
            return resposta.apply(comando);
        }

        List<String> ultima() {
            return chamadas.get(chamadas.size() - 1);
        }
    }

    private static final Path LAUNCHER = Path.of("/opt/agroease-agente-impressao/bin/AgroEase-Agente-Impressao");

    @Nested
    @DisplayName("fábrica e launcher")
    class Fabrica {
        @Test
        @DisplayName("SO → implementação; launcher -cli vira o GUI ao lado; java cru → vazio")
        void fabricaELauncher(@TempDir Path home) {
            CmdFake cmd = new CmdFake();
            assertThat(Autostart.paraSo("Windows 11", home, Map.of(), cmd)).isInstanceOf(AutostartWindows.class);
            assertThat(Autostart.paraSo("Mac OS X", home, Map.of(), cmd)).isInstanceOf(AutostartMac.class);
            assertThat(Autostart.paraSo("Linux", home, Map.of(), cmd)).isInstanceOf(AutostartLinux.class);

            assertThat(Autostart.launcherGui(Path.of("/opt/x/bin/AgroEase-Agente-Impressao-cli"))).contains(Path.of("/opt/x/bin/AgroEase-Agente-Impressao"));
            assertThat(Autostart.launcherGui(Path.of("C:\\Program Files\\x\\AgroEase-Agente-Impressao-cli.exe")).map(Path::toString).orElse(""))
                    .endsWith("AgroEase-Agente-Impressao.exe");
            assertThat(Autostart.launcherGui(Path.of("/opt/x/bin/AgroEase-Agente-Impressao"))).contains(Path.of("/opt/x/bin/AgroEase-Agente-Impressao"));
            assertThat(Autostart.launcherGui(Path.of("/usr/lib/jvm/bin/java"))).isEmpty();
            assertThat(Autostart.launcherGui(Path.of("C:\\jdk\\bin\\java.exe"))).isEmpty();
        }
    }

    @Nested
    @DisplayName("Windows")
    class Windows {
        @Test
        @DisplayName("instalar → reg add HKCU Run REG_SZ com o caminho entre aspas; instalado() lê o reg query; desinstalar → reg delete; idempotente")
        void fluxo() throws IOException {
            CmdFake cmd = new CmdFake();
            AutostartWindows a = new AutostartWindows(cmd);
            cmd.resposta = c -> c.get(1).equals("query") ? new Saida(1, "ERROR: The system was unable to find the specified registry key or value.") : new Saida(0, "");
            assertThat(a.instalado()).isFalse();

            Path exe = Path.of("C:\\Program Files\\AgroEase\\AgroEase-Agente-Impressao.exe");
            a.instalar(exe);
            List<String> add = cmd.ultima();
            assertThat(add).startsWith("reg", "add", AutostartWindows.CHAVE, "/v", AutostartWindows.VALOR, "/t", "REG_SZ", "/d");
            assertThat(add.get(8)).startsWith("\"").endsWith("AgroEase-Agente-Impressao.exe\"");
            assertThat(add).endsWith("/f");

            cmd.resposta = c -> c.get(1).equals("query") ? new Saida(0, "    AgroEaseAgenteImpressao    REG_SZ    \"C:\\...\"") : new Saida(0, "");
            assertThat(a.instalado()).isTrue();
            a.desinstalar();
            assertThat(cmd.ultima()).containsExactly("reg", "delete", AutostartWindows.CHAVE, "/v", AutostartWindows.VALOR, "/f");

            cmd.resposta = c -> c.get(1).equals("query") ? new Saida(1, "") : new Saida(0, "");
            int antes = cmd.chamadas.size();
            a.desinstalar(); // não instalado: só o query, nenhum delete
            assertThat(cmd.chamadas.size()).isEqualTo(antes + 1);
        }

        @Test
        @DisplayName("reg add falhando → IOException com a saída")
        void falha() {
            CmdFake cmd = new CmdFake();
            cmd.resposta = c -> new Saida(1, "ERROR: Access is denied.");
            AutostartWindows a = new AutostartWindows(cmd);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> a.instalar(Path.of("C:\\x.exe")))
                    .isInstanceOf(IOException.class).hasMessageContaining("Access is denied");
        }
    }

    @Nested
    @DisplayName("macOS")
    class Mac {
        @Test
        @DisplayName("instalar grava o plist (KeepAlive SuccessfulExit=false, RunAtLoad) e faz bootout+bootstrap gui/<uid>; desinstalar faz bootout e apaga")
        void fluxo(@TempDir Path home) throws IOException {
            CmdFake cmd = new CmdFake();
            cmd.resposta = c -> c.get(0).equals("id") ? new Saida(0, "501") : new Saida(0, "");
            AutostartMac a = new AutostartMac(home, Map.of(), cmd);
            assertThat(a.instalado()).isFalse();

            a.instalar(Path.of("/Applications/AgroEase-Agente-Impressao.app/Contents/MacOS/AgroEase-Agente-Impressao"));
            assertThat(a.instalado()).isTrue();
            String plist = Files.readString(a.plist());
            assertThat(a.plist()).isEqualTo(home.resolve("Library/LaunchAgents/" + Autostart.ID + ".plist"));
            assertThat(plist).contains("<string>" + Autostart.ID + "</string>")
                    .contains("<string>/Applications/AgroEase-Agente-Impressao.app/Contents/MacOS/AgroEase-Agente-Impressao</string>")
                    .contains("<key>RunAtLoad</key>\n    <true/>")
                    .contains("<key>SuccessfulExit</key>\n        <false/>")
                    .contains("<key>ThrottleInterval</key>\n    <integer>10</integer>");
            assertThat(cmd.chamadas).anySatisfy(c -> assertThat(c).containsExactly("launchctl", "bootout", "gui/501/" + Autostart.ID));
            assertThat(cmd.chamadas).anySatisfy(c -> assertThat(c).containsExactly("launchctl", "bootstrap", "gui/501", a.plist().toString()));

            a.desinstalar();
            assertThat(a.instalado()).isFalse();
            assertThat(cmd.ultima()).containsExactly("launchctl", "bootout", "gui/501/" + Autostart.ID);
            a.desinstalar(); // idempotente
        }

        @Test
        @DisplayName("ativarAgora=false (agente já rodando): grava o plist e NÃO chama launchctl — senão o launchd abriria uma 2ª instância (adversarial L4-A5)")
        void semAtivarAgora(@TempDir Path home) throws IOException {
            CmdFake cmd = new CmdFake();
            AutostartMac a = new AutostartMac(home, Map.of(), cmd);
            a.instalar(Path.of("/Applications/X.app/Contents/MacOS/X"), false);
            assertThat(a.instalado()).isTrue();
            assertThat(cmd.chamadas).noneMatch(c -> c.get(0).equals("launchctl"));
        }

        @Test
        @DisplayName("bootstrap falhando (sem sessão gráfica, ex.: CI) NÃO desfaz a instalação — vale no próximo login")
        void bootstrapFalha(@TempDir Path home) throws IOException {
            CmdFake cmd = new CmdFake();
            cmd.resposta = c -> c.get(0).equals("id") ? new Saida(0, "501") : new Saida(5, "Bootstrap failed: 5: Input/output error");
            AutostartMac a = new AutostartMac(home, Map.of(), cmd);
            a.instalar(Path.of("/Applications/X.app/Contents/MacOS/X"));
            assertThat(a.instalado()).isTrue();
        }
    }

    @Nested
    @DisplayName("Linux")
    class Linux {
        @Test
        @DisplayName("com systemd --user: unit Restart=on-failure + .desktop que importa DISPLAY e dá start; desinstalar para, apaga e recarrega")
        void comSystemd(@TempDir Path home) throws IOException {
            CmdFake cmd = new CmdFake();
            AutostartLinux a = new AutostartLinux(home, Map.of(), cmd);
            assertThat(a.instalado()).isFalse();
            a.instalar(LAUNCHER);

            assertThat(a.unit()).isEqualTo(home.resolve(".config/systemd/user/" + AutostartLinux.UNIT));
            assertThat(a.desktop()).isEqualTo(home.resolve(".config/autostart/" + AutostartLinux.DESKTOP));
            String unit = Files.readString(a.unit());
            assertThat(unit).contains("ExecStart=\"" + LAUNCHER + "\"").contains("Restart=on-failure").contains("RestartSec=10")
                    .contains("StartLimitBurst=5").contains("PartOf=graphical-session.target").contains("SuccessExitStatus=0");
            String desktop = Files.readString(a.desktop());
            assertThat(desktop).contains("[Desktop Entry]").contains("Name=" + Autostart.NOME_EXIBIDO)
                    .contains("import-environment DISPLAY WAYLAND_DISPLAY").contains("systemctl --user start " + AutostartLinux.UNIT)
                    .contains("X-GNOME-Autostart-enabled=true").doesNotContainIgnoringCase("wagsyspet");
            assertThat(cmd.chamadas).anySatisfy(c -> assertThat(c).containsExactly("systemctl", "--user", "daemon-reload"));
            assertThat(a.instalado()).isTrue();

            a.instalar(LAUNCHER); // idempotente: sobrescreve
            a.desinstalar();
            assertThat(Files.exists(a.unit())).isFalse();
            assertThat(Files.exists(a.desktop())).isFalse();
            assertThat(cmd.chamadas).anySatisfy(c -> assertThat(c).containsExactly("systemctl", "--user", "stop", AutostartLinux.UNIT));
            a.desinstalar();
        }

        @Test
        @DisplayName("sem systemd --user (show-environment falha): só o .desktop, chamando o binário direto; XDG_CONFIG_HOME respeitado; espaço no caminho escapado")
        void semSystemd(@TempDir Path home) throws IOException {
            CmdFake cmd = new CmdFake();
            cmd.resposta = c -> new Saida(1, "Failed to connect to bus");
            AutostartLinux a = new AutostartLinux(home, Map.of("XDG_CONFIG_HOME", home.resolve("cfg").toString()), cmd);
            Path launcher = Path.of("/opt/Agro Ease/bin/AgroEase-Agente-Impressao");
            a.instalar(launcher);
            assertThat(a.desktop()).isEqualTo(home.resolve("cfg/autostart/" + AutostartLinux.DESKTOP));
            assertThat(Files.exists(a.unit())).isFalse();
            assertThat(Files.readString(a.desktop())).contains("Exec=\"/opt/Agro Ease/bin/AgroEase-Agente-Impressao\"").doesNotContain("systemctl");
            assertThat(a.descricao()).contains("chama o binário direto");
        }
    }
}

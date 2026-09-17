package br.com.wagner.wagsyspet.agente.app.autostart;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * "Iniciar com o sistema" por usuário (plano F3 D22) — o jpackage não oferece autostart e {@code --launcher-as-service}
 * seria serviço de SISTEMA (sessão 0 no Windows, sem impressoras do usuário). Cada SO tem seu mecanismo oficial:
 * <ul>
 *   <li>Windows: tarefa do Agendador por XML (logon + repetição de 1 min com IgnoreNew = keepalive — plano F6 D5); sem
 *       {@code schtasks}, {@code HKCU\…\CurrentVersion\Run} (só no logon);</li>
 *   <li>macOS: LaunchAgent em {@code ~/Library/LaunchAgents} com {@code KeepAlive{SuccessfulExit:false}} (reinicia se sair ≠ 0);</li>
 *   <li>Linux: unit {@code systemd --user} com {@code Restart=on-failure} disparada por um {@code .desktop} XDG que importa
 *       {@code DISPLAY/WAYLAND_DISPLAY} (o {@code default.target} do usuário sobe antes da sessão gráfica); sem systemd, o
 *       {@code .desktop} chama o binário direto.</li>
 * </ul>
 * Idempotente: instalar duas vezes = uma; desinstalar sem instalação = nada.
 */
public interface Autostart {

    /** Identificador estável nos 3 SOs (chave do Run, label do LaunchAgent, nome da unit/.desktop). */
    String ID = "br.com.agroease.agente.impressao";
    String NOME_EXIBIDO = "Agente de Impressão AgroEase";

    boolean instalado() throws IOException;

    /**
     * @param launcher   caminho do launcher GUI do binário instalado (nunca o {@code java} cru)
     * @param ativarAgora além de registrar, carregar já (launchd bootstrap). {@code false} quando o agente JÁ está rodando —
     *                    senão o SO abre uma 2ª instância que só diz "já está em execução" (adversarial L4-A5)
     */
    void instalar(Path launcher, boolean ativarAgora) throws IOException;

    default void instalar(Path launcher) throws IOException {
        instalar(launcher, true);
    }

    void desinstalar() throws IOException;

    /**
     * O supervisor não deve relançar sozinho: "Sair", erro fatal já mostrado, e saída/instalação de uma ATUALIZAÇÃO (quem relança é o
     * atualizador). Só o Windows precisa (a tarefa keepalive dispara a cada 1 min): desabilita a tarefa e deixa o Run só-no-logon
     * como rede de segurança. launchd só relança saída ≠ 0 e o systemd tem {@code Restart=on-failure} — ambos já respeitam a saída 0.
     *
     * @param launcher launcher GUI instalado (para o Run); vazio em dev — então só desabilita
     */
    default void pausar(Optional<Path> launcher) throws IOException {
    }

    /**
     * Subida do agente / pareamento: desfaz {@link #pausar(Optional)} (reabilita a tarefa e apaga o Run) e, no Windows, MIGRA quem
     * ainda está só com o Run da v1.0.0 para a tarefa keepalive. Não instala nada para quem desativou ({@code --desinstalar}).
     */
    default void retomar(Optional<Path> launcher) throws IOException {
    }

    /** Texto para o {@code --status}/{@code --diagnostico}: mecanismo e onde fica. */
    String descricao();

    /** Fábrica por SO real. */
    static Autostart paraEsteSo(Path dados) {
        return paraSo(System.getProperty("os.name", ""), Path.of(System.getProperty("user.home")), System.getenv(), ComandoExterno.real(), dados);
    }

    static Autostart paraSo(String osName, Path home, Map<String, String> env, ComandoExterno cmd) {
        String local = env.get("LOCALAPPDATA");
        Path base = local != null && !local.isBlank() ? Path.of(local) : home.resolve("AppData").resolve("Local");
        return paraSo(osName, home, env, cmd, base.resolve("AgroEase").resolve("agente-impressao"));
    }

    /** @param dados pasta de dados do agente (o Windows guarda ali o XML da tarefa, em {@code autostart/}) */
    static Autostart paraSo(String osName, Path home, Map<String, String> env, ComandoExterno cmd, Path dados) {
        String os = osName.toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return new AutostartWindows(cmd, dados.resolve("autostart"));
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return new AutostartMac(home, env, cmd);
        }
        return new AutostartLinux(home, env, cmd);
    }

    /**
     * Launcher GUI do binário em execução: o comando do processo (jpackage: o próprio launcher nativo). Se for o
     * {@code -cli}, troca pelo GUI ao lado. Vazio quando o processo é o {@code java} cru (dev/`java -jar`) — não há o que
     * registrar. Override: {@code -Dagente.launcher}.
     */
    static Optional<Path> launcherDesteProcesso() {
        String override = System.getProperty("agente.launcher");
        if (override != null && !override.isBlank()) {
            return Optional.of(Path.of(override));
        }
        return ProcessHandle.current().info().command().map(Path::of).flatMap(Autostart::launcherGui);
    }

    static Optional<Path> launcherGui(Path comando) {
        // último segmento tolerando os DOIS separadores (um caminho Windows lido num teste Linux não tem '\\' como separador)
        String texto = comando.toString();
        int corte = Math.max(texto.lastIndexOf('/'), texto.lastIndexOf('\\'));
        String nome = corte < 0 ? texto : texto.substring(corte + 1);
        String pasta = corte < 0 ? "" : texto.substring(0, corte + 1);
        String base = nome.toLowerCase(Locale.ROOT);
        if (base.equals("java") || base.equals("java.exe") || base.equals("javaw.exe")) {
            return Optional.empty();
        }
        if (base.endsWith("-cli.exe")) {
            return Optional.of(Path.of(pasta + nome.substring(0, nome.length() - "-cli.exe".length()) + ".exe"));
        }
        if (base.endsWith("-cli")) {
            return Optional.of(Path.of(pasta + nome.substring(0, nome.length() - "-cli".length())));
        }
        return Optional.of(comando);
    }
}

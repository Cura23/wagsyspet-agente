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
 * macOS: LaunchAgent {@code ~/Library/LaunchAgents/<ID>.plist} com {@code RunAtLoad} e {@code KeepAlive{SuccessfulExit:false}}
 * (o launchd reinicia só quando o agente sai ≠ 0 — exit 0 = "não reinicie", exit 3/4 = reinicia com {@code ThrottleInterval} 10 s).
 * Ativação imediata via {@code launchctl bootstrap gui/<uid>}; sem sessão gráfica (CI) o bootstrap falha e fica só o plist —
 * que vale no próximo login. macOS 13+ mostra a notificação "Itens de segundo plano adicionados" (documentar na página /agente).
 */
final class AutostartMac implements Autostart {

    private static final Logger log = LoggerFactory.getLogger(AutostartMac.class);

    private final Path plist;
    private final Map<String, String> env;
    private final ComandoExterno cmd;

    AutostartMac(Path home, Map<String, String> env, ComandoExterno cmd) {
        this.plist = home.resolve("Library").resolve("LaunchAgents").resolve(ID + ".plist");
        this.env = env;
        this.cmd = cmd;
    }

    Path plist() {
        return plist;
    }

    @Override
    public boolean instalado() {
        return Files.exists(plist);
    }

    @Override
    public void instalar(Path launcher, boolean ativarAgora) throws IOException {
        Files.createDirectories(plist.getParent());
        Files.writeString(plist, conteudo(launcher.toAbsolutePath()), StandardCharsets.UTF_8);
        log.info("LaunchAgent gravado em {}{}", plist, ativarAgora ? "" : " (vale no próximo login; o agente já está rodando)");
        String uid = ativarAgora ? uid() : null;
        if (uid != null) {
            // recarrega se já existia; falha aqui não desfaz a instalação (vale no próximo login)
            cmd.executar(List.of("launchctl", "bootout", "gui/" + uid + "/" + ID));
            ComandoExterno.Saida s = cmd.executar(List.of("launchctl", "bootstrap", "gui/" + uid, plist.toString()));
            if (!s.ok()) {
                log.warn("launchctl bootstrap não ativou agora ({}): {} — o LaunchAgent vale no próximo login", s.exit(), s.texto());
            }
        }
    }

    @Override
    public void desinstalar() throws IOException {
        String uid = uid();
        if (uid != null) {
            cmd.executar(List.of("launchctl", "bootout", "gui/" + uid + "/" + ID)); // erro se não carregado: ignorar
        }
        if (Files.deleteIfExists(plist)) {
            log.info("LaunchAgent removido: {}", plist);
        }
    }

    @Override
    public String descricao() {
        return "macOS: LaunchAgent " + plist + " (reinicia se o agente sair com erro)";
    }

    private String uid() throws IOException {
        String u = env.get("UID");
        if (u != null && u.matches("\\d+")) {
            return u;
        }
        try {
            ComandoExterno.Saida s = cmd.executar(List.of("id", "-u"));
            return s.ok() && s.texto().matches("\\d+") ? s.texto() : null;
        } catch (IOException e) {
            return null;
        }
    }

    static String conteudo(Path launcher) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                <plist version="1.0">
                <dict>
                    <key>Label</key>
                    <string>%s</string>
                    <key>ProgramArguments</key>
                    <array>
                        <string>%s</string>
                    </array>
                    <key>RunAtLoad</key>
                    <true/>
                    <key>KeepAlive</key>
                    <dict>
                        <key>SuccessfulExit</key>
                        <false/>
                    </dict>
                    <key>ThrottleInterval</key>
                    <integer>10</integer>
                    <key>ProcessType</key>
                    <string>Interactive</string>
                </dict>
                </plist>
                """.formatted(ID, xml(launcher.toString()));
    }

    private static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}

package br.com.wagner.wagsyspet.agente.app.autostart;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Windows: valor {@code REG_SZ} em {@code HKCU\Software\Microsoft\Windows\CurrentVersion\Run} (MS Learn: roda a cada logon
 * do usuário, sem admin, caminho ≤ 260 chars). Sem supervisor: se o agente morrer, volta só no próximo logon (F6 traz o
 * Task Scheduler com RestartOnFailure).
 */
final class AutostartWindows implements Autostart {

    private static final Logger log = LoggerFactory.getLogger(AutostartWindows.class);
    static final String CHAVE = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    static final String VALOR = "AgroEaseAgenteImpressao";

    private final ComandoExterno cmd;

    AutostartWindows(ComandoExterno cmd) {
        this.cmd = cmd;
    }

    @Override
    public boolean instalado() throws IOException {
        ComandoExterno.Saida s = cmd.executar(List.of("reg", "query", CHAVE, "/v", VALOR));
        return s.ok() && s.texto().contains(VALOR);
    }

    @Override
    public void instalar(Path launcher, boolean ativarAgora) throws IOException { // Run só age no logon: ativarAgora não se aplica
        String caminho = "\"" + launcher.toAbsolutePath() + "\"";
        if (caminho.length() > 260) {
            throw new IOException("caminho do agente longo demais para o Run do Windows (" + caminho.length() + " > 260)");
        }
        ComandoExterno.Saida s = cmd.executar(List.of("reg", "add", CHAVE, "/v", VALOR, "/t", "REG_SZ", "/d", caminho, "/f"));
        if (!s.ok()) {
            throw new IOException("reg add falhou (" + s.exit() + "): " + s.texto());
        }
        log.info("Autostart Windows gravado em {}\\{} → {}", CHAVE, VALOR, caminho);
    }

    @Override
    public void desinstalar() throws IOException {
        if (!instalado()) {
            return;
        }
        ComandoExterno.Saida s = cmd.executar(List.of("reg", "delete", CHAVE, "/v", VALOR, "/f"));
        if (!s.ok()) {
            throw new IOException("reg delete falhou (" + s.exit() + "): " + s.texto());
        }
        log.info("Autostart Windows removido");
    }

    @Override
    public String descricao() {
        return "Windows: " + CHAVE + "\\" + VALOR + " (roda no logon; sem reinício automático nesta versão)";
    }
}

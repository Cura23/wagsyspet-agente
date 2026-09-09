package br.com.wagner.wagsyspet.agente.core.pareamento;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Plano F3 D15 — pasta de dados por SO com marca AgroEase, override por env, 0700 onde há POSIX. */
@DisplayName("DiretoriosDoAgente — onde o agente guarda pareamento/config/log em cada SO")
class DiretoriosDoAgenteTest {

    private static Properties props(String osName, String userHome) {
        Properties p = new Properties();
        p.setProperty("os.name", osName);
        p.setProperty("user.home", userHome);
        return p;
    }

    @Test
    @DisplayName("Windows → %LOCALAPPDATA%\\AgroEase\\agente-impressao (cai em user.home se a env faltar)")
    void windows() {
        DiretoriosDoAgente d = DiretoriosDoAgente.resolver(Map.of("LOCALAPPDATA", "C:\\Users\\caixa\\AppData\\Local"), props("Windows 11", "C:\\Users\\caixa"));
        assertThat(d.raiz().toString()).isEqualTo(Path.of("C:\\Users\\caixa\\AppData\\Local", "AgroEase", "agente-impressao").toString());
        DiretoriosDoAgente semEnv = DiretoriosDoAgente.resolver(Map.of(), props("Windows 10", "C:\\Users\\caixa"));
        assertThat(semEnv.raiz().toString()).contains("AgroEase").contains("agente-impressao");
    }

    @Test
    @DisplayName("Linux → $XDG_CONFIG_HOME/agroease/agente-impressao, default ~/.config")
    void linux() {
        DiretoriosDoAgente d = DiretoriosDoAgente.resolver(Map.of(), props("Linux", "/home/caixa"));
        assertThat(d.raiz()).isEqualTo(Path.of("/home/caixa/.config/agroease/agente-impressao"));
        DiretoriosDoAgente xdg = DiretoriosDoAgente.resolver(Map.of("XDG_CONFIG_HOME", "/tmp/xdg"), props("Linux", "/home/caixa"));
        assertThat(xdg.raiz()).isEqualTo(Path.of("/tmp/xdg/agroease/agente-impressao"));
    }

    @Test
    @DisplayName("macOS → ~/Library/Application Support/AgroEase/agente-impressao")
    void macos() {
        DiretoriosDoAgente d = DiretoriosDoAgente.resolver(Map.of(), props("Mac OS X", "/Users/caixa"));
        assertThat(d.raiz()).isEqualTo(Path.of("/Users/caixa/Library/Application Support/AgroEase/agente-impressao"));
    }

    @Test
    @DisplayName("AGROEASE_AGENTE_DIR (env) manda em qualquer SO — suporte/teste apontam para outra pasta")
    void override() {
        // caminho absoluto NO SO que roda o teste: um POSIX "/srv/x" no Windows ganharia a unidade atual (D:\srv\x) — CI F3
        Path alvo = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().resolve("agente-x");
        DiretoriosDoAgente d = DiretoriosDoAgente.resolver(Map.of("AGROEASE_AGENTE_DIR", alvo.toString()), props("Windows 11", "C:\\x"));
        assertThat(d.raiz()).isEqualTo(alvo.normalize());
        // e um relativo vira absoluto (o agente grava sempre num lugar previsível)
        assertThat(DiretoriosDoAgente.resolver(Map.of("AGROEASE_AGENTE_DIR", "rel-x"), props("Linux", "/h")).raiz().isAbsolute()).isTrue();
    }

    @Test
    @DisplayName("nomes dos arquivos fixos; nada com 'wagsyspet' visível ao lojista")
    void arquivos() {
        DiretoriosDoAgente d = new DiretoriosDoAgente(Path.of("/x"));
        assertThat(d.pareamento()).isEqualTo(Path.of("/x/pareamento.enc"));
        assertThat(d.chave()).isEqualTo(Path.of("/x/chave.bin"));
        assertThat(d.config()).isEqualTo(Path.of("/x/config.json"));
        assertThat(d.lock()).isEqualTo(Path.of("/x/agente.lock"));
        assertThat(d.logs()).isEqualTo(Path.of("/x/logs"));
        assertThat(d.raiz().toString()).doesNotContainIgnoringCase("wagsyspet");
    }

    @Test
    @DisplayName("garantir() cria a árvore e, em POSIX, deixa a raiz 0700 (só o usuário do caixa lê a credencial)")
    void garantir(@TempDir Path tmp) throws Exception {
        DiretoriosDoAgente d = new DiretoriosDoAgente(tmp.resolve("a/b/agente"));
        d.garantir();
        assertThat(Files.isDirectory(d.raiz())).isTrue();
        assertThat(Files.isDirectory(d.logs())).isTrue();
        if (Files.getFileStore(tmp).supportsFileAttributeView("posix")) {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(d.raiz());
            assertThat(perms).containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
        }
    }
}

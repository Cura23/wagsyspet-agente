package br.com.wagner.wagsyspet.agente.app.atualizacao;

import br.com.wagner.wagsyspet.agente.app.autostart.ComandoExterno;
import br.com.wagner.wagsyspet.agente.core.pareamento.DiretoriosDoAgente;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Raiz do app-image por SO, relançamento pelo supervisor e lançamento "de fora" do atualizador (cópia do app-image) — plano F6 D2. */
@DisplayName("Plataforma — raiz do app-image, Relancadores por SO e LancadorAtualizadorDeFora (cópia + systemd-run/open/direto)")
class PlataformaAtualizacaoTest {

    static final String NOME = "AgroEase-Agente-Impressao";

    static final class Cmd implements ComandoExterno {
        final List<List<String>> chamadas = new ArrayList<>();
        @Override public Saida executar(List<String> comando) { chamadas.add(List.copyOf(comando)); return new Saida(0, ""); }
    }

    @Test
    @DisplayName("raiz do app-image a partir do launcher: Linux <raiz>/bin/X; macOS <X.app>/Contents/MacOS/X; Windows <raiz>/X.exe")
    void raizDoAppImage() {
        assertThat(Plataforma.raizDoAppImage(Path.of("/opt/agroease-agente-impressao/bin/" + NOME), "Linux")).contains(Path.of("/opt/agroease-agente-impressao"));
        assertThat(Plataforma.raizDoAppImage(Path.of("/Applications/" + NOME + ".app/Contents/MacOS/" + NOME), "Mac OS X")).contains(Path.of("/Applications/" + NOME + ".app"));
        // Windows: <raiz>/X.exe (caminho em forma POSIX porque Path.of não parseia "C:\\…" fora do Windows; a regra é só o pai)
        assertThat(Plataforma.raizDoAppImage(Path.of("/Users/l/AppData/Local/" + NOME + "/" + NOME + ".exe"), "Windows 11")).contains(Path.of("/Users/l/AppData/Local/" + NOME));
        assertThat(Plataforma.raizDoAppImage(Path.of("/x/" + NOME), "Linux")).as("sem bin/ acima → vazio").isEmpty();
    }

    @Test
    @DisplayName("Relancadores: Linux com unit do systemd → systemctl --user start; sem unit → processo direto; macOS com plist → launchctl kickstart -k; Windows → direto")
    void relancadores(@TempDir Path tmp) throws Exception {
        Cmd cmd = new Cmd();
        List<Path> diretos = new ArrayList<>();
        Path launcher = tmp.resolve("bin").resolve(NOME);
        // Linux com unit
        Path unit = tmp.resolve(".config/systemd/user/agroease-agente-impressao.service"); Files.createDirectories(unit.getParent()); Files.writeString(unit, "[Unit]");
        Relancadores.paraSo("Linux", tmp, Map.of(), cmd, diretos::add).relancar(launcher);
        assertThat(cmd.chamadas.get(cmd.chamadas.size() - 1)).containsExactly("systemctl", "--user", "start", "agroease-agente-impressao.service");
        assertThat(diretos).isEmpty();
        // Linux sem unit, fora do systemd → direto
        Files.delete(unit);
        Relancadores.paraSo("Linux", tmp, Map.of(), cmd, diretos::add).relancar(launcher);
        assertThat(diretos).containsExactly(launcher);
        // Linux sem unit, mas DENTRO de uma unidade (INVOCATION_ID): filho direto morreria com a unidade → systemd-run próprio, com o dir de dados repassado
        Relancadores.paraSo("Linux", tmp, Map.of("INVOCATION_ID", "x", "AGROEASE_AGENTE_DIR", "/d"), cmd, diretos::add).relancar(launcher);
        List<String> sr = cmd.chamadas.get(cmd.chamadas.size() - 1);
        assertThat(sr).startsWith("systemd-run", "--user", "--collect");
        assertThat(sr).contains("--setenv=AGROEASE_AGENTE_DIR=/d", launcher.toString());
        assertThat(sr.stream().anyMatch(x -> x.startsWith("--unit=agroease-agente-"))).isTrue();
        assertThat(diretos).hasSize(1);
        // macOS com plist
        Path plist = tmp.resolve("Library/LaunchAgents/br.com.agroease.agente.impressao.plist"); Files.createDirectories(plist.getParent()); Files.writeString(plist, "<plist/>");
        Relancadores.paraSo("Mac OS X", tmp, Map.of("UID", "501"), cmd, diretos::add).relancar(launcher);
        assertThat(cmd.chamadas.get(cmd.chamadas.size() - 1)).containsExactly("launchctl", "kickstart", "-k", "gui/501/br.com.agroease.agente.impressao");
        // Windows
        Relancadores.paraSo("Windows 11", tmp, Map.of(), cmd, diretos::add).relancar(launcher);
        assertThat(diretos).hasSize(2);
    }

    @Test
    @DisplayName("LancadorAtualizadorDeFora: copia o app-image para <dados>/atualizacao/atualizador/ (1× por versão) e lança a CÓPIA: Linux sob systemd → systemd-run --user; senão direto; macOS → open -n -a … --args")
    void lancadorDeFora(@TempDir Path tmp) throws Exception {
        Path raiz = InstaladorTrocaDeDiretorioTest.appImage(tmp.resolve("opt"), "1.0.0");
        Path launcher = raiz.resolve("bin").resolve(NOME);
        DiretoriosDoAgente dirs = new DiretoriosDoAgente(tmp.resolve("dados"));
        Path plano = dirs.atualizacao().resolve("plano.json"); Files.createDirectories(plano.getParent()); Files.writeString(plano, "{}");
        Cmd cmd = new Cmd();
        List<List<String>> diretos = new ArrayList<>();

        // Linux dentro de um serviço systemd (INVOCATION_ID) → systemd-run com a cópia
        LancadorAtualizadorDeFora l = new LancadorAtualizadorDeFora(dirs, "Linux", Map.of("INVOCATION_ID", "abc", "JAVA_TOOL_OPTIONS", "-Dx=1"), () -> Optional.of(launcher), "1.0.0", cmd, diretos::add);
        l.lancar(plano);
        Path copia = dirs.atualizacao().resolve("atualizador").resolve(NOME);
        assertThat(copia.resolve("bin").resolve(NOME)).exists();
        assertThat(Files.readString(copia.resolve("lib/app/versao.txt"))).isEqualTo("1.0.0");
        assertThat(Files.isExecutable(copia.resolve("bin").resolve(NOME))).isTrue();
        List<String> c = cmd.chamadas.get(0);
        assertThat(c).startsWith("systemd-run", "--user", "--collect");
        assertThat(c).contains(copia.resolve("bin").resolve(NOME).toString(), "--aplicar-atualizacao", plano.toString());
        assertThat(c.stream().anyMatch(x -> x.startsWith("--unit=agroease-atualizador"))).isTrue();
        assertThat(c).as("systemd-run não herda o ambiente: repassar o que o agente lê").contains("--setenv=JAVA_TOOL_OPTIONS=-Dx=1");

        // 2ª vez na mesma versão: não copia de novo (marca de versão na cópia)
        Files.writeString(copia.resolve("marca.txt"), "x");
        l.lancar(plano);
        assertThat(copia.resolve("marca.txt")).exists();

        // Linux fora do systemd → direto (cópia)
        new LancadorAtualizadorDeFora(dirs, "Linux", Map.of(), () -> Optional.of(launcher), "1.0.0", cmd, diretos::add).lancar(plano);
        assertThat(diretos).hasSize(1);
        assertThat(diretos.get(0)).containsExactly(copia.resolve("bin").resolve(NOME).toString(), "--aplicar-atualizacao", plano.toString());

        // macOS → open -n -a <cópia.app> --args …
        Path app = tmp.resolve("Applications").resolve(NOME + ".app"); Files.createDirectories(app.resolve("Contents/MacOS")); Files.writeString(app.resolve("Contents/MacOS/" + NOME), "x");
        new LancadorAtualizadorDeFora(dirs, "Mac OS X", Map.of(), () -> Optional.of(app.resolve("Contents/MacOS/" + NOME)), "1.0.0", cmd, diretos::add).lancar(plano);
        List<String> mac = cmd.chamadas.get(cmd.chamadas.size() - 1);
        assertThat(mac).startsWith("open", "-n", "-a");
        assertThat(mac.get(3)).endsWith(NOME + ".app");
        assertThat(mac).contains("--args", "--aplicar-atualizacao", plano.toString());

        // sem launcher (java cru) → IOException
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new LancadorAtualizadorDeFora(dirs, "Linux", Map.of(), Optional::empty, "1.0.0", cmd, diretos::add).lancar(plano))
                .isInstanceOf(java.io.IOException.class);
    }
}

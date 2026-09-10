package br.com.wagner.wagsyspet.agente.app.atualizacao;

import br.com.wagner.wagsyspet.agente.app.AplicadorAtualizacao;
import br.com.wagner.wagsyspet.agente.app.autostart.ComandoExterno;
import br.com.wagner.wagsyspet.agente.core.atualizacao.EstadoAtualizacao;
import br.com.wagner.wagsyspet.agente.core.atualizacao.PlanoAtualizacao;
import br.com.wagner.wagsyspet.agente.protocolo.release.ManifestoRelease;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Linux tar.gz por usuário e macOS .app: extrair/montar → validar → mover atual→anterior, novo→atual (rename-swap) — plano F6 D1. */
@DisplayName("InstaladorTrocaDeDiretorio — tar.gz real extraído e trocado por rename; reverter volta o anterior; .dmg via hdiutil/ditto/codesign")
class InstaladorTrocaDeDiretorioTest {

    static final String NOME = "AgroEase-Agente-Impressao";

    static final class Cmd implements ComandoExterno {
        final List<List<String>> chamadas = new ArrayList<>();
        java.util.function.Function<List<String>, Saida> resposta = c -> new Saida(0, "");
        @Override public Saida executar(List<String> comando) { chamadas.add(List.copyOf(comando)); return resposta.apply(comando); }
    }

    /** Cria um app-image falso {@code <raiz>/<NOME>/bin/<NOME>} com o texto da versão e devolve a raiz do app-image. */
    static Path appImage(Path onde, String versao) throws Exception {
        Path raiz = onde.resolve(NOME);
        Files.createDirectories(raiz.resolve("bin"));
        Files.createDirectories(raiz.resolve("lib").resolve("app"));
        Files.writeString(raiz.resolve("bin").resolve(NOME), "#!/bin/sh\necho " + versao + "\n");
        try { raiz.resolve("bin").resolve(NOME).toFile().setExecutable(true, false); } catch (RuntimeException ignorada) { }
        Files.writeString(raiz.resolve("lib").resolve("app").resolve("versao.txt"), versao);
        return raiz;
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    @DisplayName("Linux tar.gz: extrai com tar, valida o launcher dentro, troca as pastas; launcher novo = mesmo caminho; anterior guardado; reverter() volta")
    void tarGz(@TempDir Path tmp) throws Exception {
        Path instalado = appImage(tmp.resolve("local"), "1.0.0");            // ~/.local/.../AgroEase-Agente-Impressao (versão antiga)
        Path novo = appImage(tmp.resolve("build"), "9.9.9");
        Path tar = tmp.resolve("AgroEase-Agente-Impressao-9.9.9-linux-x64.tar.gz");
        assertThat(new ProcessBuilder("tar", "-C", tmp.resolve("build").toString(), "-czf", tar.toString(), NOME).inheritIO().start().waitFor()).isZero();
        PlanoAtualizacao plano = new PlanoAtualizacao("9.9.9", "1.0.0", tar.toString(), "ab".repeat(32), tar.getFileName().toString(), ManifestoRelease.FormatoInstalado.APP_IMAGE,
                Optional.of(instalado.resolve("bin").resolve(NOME).toString()), tmp.toString(), Instant.now(), PlanoAtualizacao.Gatilho.AUTO);
        Path anterior = tmp.resolve("dados").resolve("anterior");
        InstaladorTrocaDeDiretorio i = new InstaladorTrocaDeDiretorio(ComandoExterno.real(), instalado, anterior, tmp.resolve("dados").resolve("staging"));

        AplicadorAtualizacao.Instalador.Resultado r = i.aplicar(plano);
        assertThat(r.ok()).as(r.detalhe()).isTrue();
        assertThat(r.launcherNovo()).contains(instalado.resolve("bin").resolve(NOME));
        assertThat(Files.readString(instalado.resolve("lib/app/versao.txt"))).isEqualTo("9.9.9");
        assertThat(Files.readString(anterior.resolve(NOME).resolve("lib/app/versao.txt"))).as("anterior guardado").isEqualTo("1.0.0");
        assertThat(Files.isExecutable(instalado.resolve("bin").resolve(NOME))).isTrue();

        assertThat(i.reverter(new EstadoAtualizacao.EmAplicacao("9.9.9", "1.0.0", null, Instant.now()))).isTrue();
        assertThat(Files.readString(instalado.resolve("lib/app/versao.txt"))).isEqualTo("1.0.0");
        assertThat(Files.exists(anterior.resolve(NOME))).isFalse();
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    @DisplayName("tar.gz sem o launcher dentro → FALHOU e nada é trocado")
    void tarSemLauncher(@TempDir Path tmp) throws Exception {
        Path instalado = appImage(tmp.resolve("local"), "1.0.0");
        Files.createDirectories(tmp.resolve("build").resolve("Outro"));
        Files.writeString(tmp.resolve("build").resolve("Outro").resolve("x.txt"), "x");
        Path tar = tmp.resolve("ruim.tar.gz");
        assertThat(new ProcessBuilder("tar", "-C", tmp.resolve("build").toString(), "-czf", tar.toString(), "Outro").inheritIO().start().waitFor()).isZero();
        PlanoAtualizacao plano = new PlanoAtualizacao("9.9.9", "1.0.0", tar.toString(), "ab".repeat(32), "ruim.tar.gz", ManifestoRelease.FormatoInstalado.APP_IMAGE,
                Optional.of(instalado.resolve("bin").resolve(NOME).toString()), tmp.toString(), Instant.now(), PlanoAtualizacao.Gatilho.AUTO);
        InstaladorTrocaDeDiretorio i = new InstaladorTrocaDeDiretorio(ComandoExterno.real(), instalado, tmp.resolve("anterior"), tmp.resolve("staging"));
        AplicadorAtualizacao.Instalador.Resultado r = i.aplicar(plano);
        assertThat(r.ok()).isFalse();
        assertThat(Files.readString(instalado.resolve("lib/app/versao.txt"))).isEqualTo("1.0.0");
    }

    @Test
    @DisplayName("macOS .dmg: hdiutil attach → ditto do .app → detach → xattr quarantine → codesign --verify → troca; comandos na ordem certa")
    void dmg(@TempDir Path tmp) throws Exception {
        Path instalado = tmp.resolve("Applications").resolve(NOME + ".app");
        Files.createDirectories(instalado.resolve("Contents").resolve("MacOS"));
        Files.writeString(instalado.resolve("Contents/MacOS/" + NOME), "1.0.0");
        Path dmg = tmp.resolve("AgroEase-Agente-Impressao-9.9.9-macos-arm64.dmg"); Files.writeString(dmg, "dmg");
        Path staging = tmp.resolve("staging");
        Cmd cmd = new Cmd();
        cmd.resposta = c -> {
            try {
                if (c.get(0).equals("ditto")) { // simula a cópia do .app montado para o staging
                    Path destino = Path.of(c.get(c.size() - 1));
                    Files.createDirectories(destino.resolve("Contents").resolve("MacOS"));
                    Files.writeString(destino.resolve("Contents/MacOS/" + NOME), "9.9.9");
                }
            } catch (Exception e) { throw new IllegalStateException(e); }
            return new ComandoExterno.Saida(0, "");
        };
        PlanoAtualizacao plano = new PlanoAtualizacao("9.9.9", "1.0.0", dmg.toString(), "ab".repeat(32), dmg.getFileName().toString(), ManifestoRelease.FormatoInstalado.INSTALADOR,
                Optional.of(instalado.resolve("Contents/MacOS/" + NOME).toString()), tmp.toString(), Instant.now(), PlanoAtualizacao.Gatilho.AUTO);
        InstaladorTrocaDeDiretorio i = new InstaladorTrocaDeDiretorio(cmd, instalado, tmp.resolve("anterior"), staging);
        AplicadorAtualizacao.Instalador.Resultado r = i.aplicar(plano);
        assertThat(r.ok()).as(r.detalhe()).isTrue();
        assertThat(Files.readString(instalado.resolve("Contents/MacOS/" + NOME))).isEqualTo("9.9.9");
        assertThat(Files.readString(tmp.resolve("anterior").resolve(NOME + ".app").resolve("Contents/MacOS/" + NOME))).isEqualTo("1.0.0");
        List<String> programas = cmd.chamadas.stream().map(c -> c.get(0) + (c.get(0).equals("hdiutil") ? " " + c.get(1) : "")).toList();
        assertThat(programas).containsExactly("hdiutil attach", "ditto", "hdiutil detach", "xattr", "codesign");
        assertThat(cmd.chamadas.get(0)).contains("-nobrowse", "-readonly", dmg.toString());
        assertThat(cmd.chamadas.get(4)).contains("--verify", "--strict", "--deep");
    }

    @Test
    @DisplayName("codesign falha → FALHOU, staging apagado, instalado intocado")
    void codesignFalha(@TempDir Path tmp) throws Exception {
        Path instalado = tmp.resolve("Applications").resolve(NOME + ".app");
        Files.createDirectories(instalado.resolve("Contents").resolve("MacOS"));
        Files.writeString(instalado.resolve("Contents/MacOS/" + NOME), "1.0.0");
        Path dmg = tmp.resolve("x.dmg"); Files.writeString(dmg, "dmg");
        Cmd cmd = new Cmd();
        cmd.resposta = c -> {
            try { if (c.get(0).equals("ditto")) { Path d = Path.of(c.get(c.size() - 1)); Files.createDirectories(d.resolve("Contents/MacOS")); Files.writeString(d.resolve("Contents/MacOS/" + NOME), "9.9.9"); } } catch (Exception e) { throw new IllegalStateException(e); }
            return new ComandoExterno.Saida(c.get(0).equals("codesign") ? 1 : 0, c.get(0).equals("codesign") ? "invalid signature" : "");
        };
        PlanoAtualizacao plano = new PlanoAtualizacao("9.9.9", "1.0.0", dmg.toString(), "ab".repeat(32), "x.dmg", ManifestoRelease.FormatoInstalado.INSTALADOR,
                Optional.of(instalado.resolve("Contents/MacOS/" + NOME).toString()), tmp.toString(), Instant.now(), PlanoAtualizacao.Gatilho.AUTO);
        InstaladorTrocaDeDiretorio i = new InstaladorTrocaDeDiretorio(cmd, instalado, tmp.resolve("anterior"), tmp.resolve("staging"));
        AplicadorAtualizacao.Instalador.Resultado r = i.aplicar(plano);
        assertThat(r.ok()).isFalse();
        assertThat(r.detalhe()).contains("codesign");
        assertThat(Files.readString(instalado.resolve("Contents/MacOS/" + NOME))).isEqualTo("1.0.0");
        assertThat(Files.exists(tmp.resolve("staging"))).isFalse();
    }
}

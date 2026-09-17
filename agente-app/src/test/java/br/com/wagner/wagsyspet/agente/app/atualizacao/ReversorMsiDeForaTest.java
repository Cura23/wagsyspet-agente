package br.com.wagner.wagsyspet.agente.app.atualizacao;

import br.com.wagner.wagsyspet.agente.core.atualizacao.EstadoAtualizacao;
import br.com.wagner.wagsyspet.agente.core.atualizacao.GerenteAtualizacao;
import br.com.wagner.wagsyspet.agente.core.atualizacao.PlanoAtualizacao;
import br.com.wagner.wagsyspet.agente.core.pareamento.DiretoriosDoAgente;
import br.com.wagner.wagsyspet.agente.protocolo.release.ManifestoRelease;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rollback no Windows (adversarial L3): o MSI anterior NÃO pode rodar de dentro do agente instalado (o Windows Installer em modo
 * silencioso derruba/adia os arquivos que o próprio processo segura). A sentinela só PREPARA a volta — plano de reversão + atualizador
 * "de fora", como no update — e sai; quem instala e relança é o atualizador.
 */
@DisplayName("ReversorMsiDeFora — reverter = plano de reversão (anterior conferido) entregue ao atualizador de fora, com o keepalive pausado")
class ReversorMsiDeForaTest {

    private final EstadoAtualizacao.EmAplicacao ap = new EstadoAtualizacao.EmAplicacao("9.9.9", "1.0.0", null, Instant.parse("2026-09-17T12:00:00Z"));

    @Test
    @DisplayName("anterior guardado e conferido → pausa o supervisor, grava o plano INVERTIDO (nova=1.0.0, anterior=9.9.9, artefato=o .exe guardado, INSTALADOR, AUTO) e lança o atualizador; devolve true")
    void preparaAVolta(@TempDir Path tmp) throws Exception {
        DiretoriosDoAgente dirs = new DiretoriosDoAgente(tmp);
        Path exe = InstaladorMsiTest.guardar(dirs.atualizacao().resolve("anterior"), "1.0.0", "old");
        List<String> ordem = new ArrayList<>();
        List<Path> lancados = new ArrayList<>();
        Path launcher = tmp.resolve("AgroEase-Agente-Impressao.exe");
        ReversorMsiDeFora r = new ReversorMsiDeFora(dirs, plano -> { ordem.add("lancar"); lancados.add(plano); }, () -> Optional.of(launcher), () -> ordem.add("pausar"), () -> ordem.add("retomar"));
        assertThat(r.reverter(ap)).isTrue();
        assertThat(ordem).containsExactly("pausar", "lancar");
        assertThat(lancados).containsExactly(dirs.atualizacao().resolve(GerenteAtualizacao.ARQUIVO_PLANO));
        PlanoAtualizacao p = PlanoAtualizacao.ler(lancados.get(0));
        assertThat(p.versaoNova()).isEqualTo("1.0.0");
        assertThat(p.versaoAnterior()).isEqualTo("9.9.9");
        assertThat(Path.of(p.artefato())).isEqualTo(exe);
        assertThat(p.formato()).isEqualTo(ManifestoRelease.FormatoInstalado.INSTALADOR);
        assertThat(p.gatilho()).isEqualTo(PlanoAtualizacao.Gatilho.AUTO);
        assertThat(p.launcherAtual().map(Path::of)).contains(launcher);
        assertThat(Path.of(p.dirDados())).isEqualTo(dirs.raiz());
    }

    @Test
    @DisplayName("sem instalador da versão anterior conferido (nenhum, de outra versão ou adulterado) → false, nada pausado nem lançado; lançador falhando → false E o supervisor é retomado")
    void semAnteriorOuSemLancador(@TempDir Path tmp) throws Exception {
        DiretoriosDoAgente dirs = new DiretoriosDoAgente(tmp);
        List<String> ordem = new ArrayList<>();
        ReversorMsiDeFora r = new ReversorMsiDeFora(dirs, plano -> ordem.add("lancar"), Optional::empty, () -> ordem.add("pausar"), () -> ordem.add("retomar"));
        assertThat(r.reverter(ap)).isFalse();
        InstaladorMsiTest.guardar(dirs.atualizacao().resolve("anterior"), "0.9.0", "muito-old");
        assertThat(r.reverter(ap)).isFalse();
        assertThat(ordem).isEmpty();

        Files.delete(dirs.atualizacao().resolve("anterior").resolve("versao.txt"));
        InstaladorMsiTest.guardar(dirs.atualizacao().resolve("anterior"), "1.0.0", "old");
        ReversorMsiDeFora semLancador = new ReversorMsiDeFora(dirs, plano -> { throw new IOException("sem cópia do atualizador"); }, Optional::empty, () -> ordem.add("pausar"), () -> ordem.add("retomar"));
        assertThat(semLancador.reverter(ap)).isFalse();
        assertThat(ordem).containsExactly("pausar", "retomar");
        assertThat(dirs.atualizacao().resolve(GerenteAtualizacao.ARQUIVO_PLANO)).as("plano órfão não fica para trás").doesNotExist();
    }
}

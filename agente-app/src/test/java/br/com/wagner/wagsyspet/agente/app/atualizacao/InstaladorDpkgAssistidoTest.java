package br.com.wagner.wagsyspet.agente.app.atualizacao;

import br.com.wagner.wagsyspet.agente.app.AplicadorAtualizacao;
import br.com.wagner.wagsyspet.agente.app.autostart.ComandoExterno;
import br.com.wagner.wagsyspet.agente.core.atualizacao.EstadoAtualizacao;
import br.com.wagner.wagsyspet.agente.core.atualizacao.PlanoAtualizacao;
import br.com.wagner.wagsyspet.agente.protocolo.release.ManifestoRelease;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Linux .deb em /opt exige root: nunca pedir senha sozinho (madrugada = falha perpétua); só por clique (gatilho MANUAL) via pkexec. */
@DisplayName("InstaladorDpkgAssistido — AUTO recusa sem tocar no sistema; MANUAL = pkexec dpkg -i; 126/127 = cancelado")
class InstaladorDpkgAssistidoTest {

    static final class Cmd implements ComandoExterno {
        final List<List<String>> chamadas = new ArrayList<>();
        java.util.function.Function<List<String>, Saida> resposta = c -> new Saida(0, "");
        @Override public Saida executar(List<String> comando) { chamadas.add(List.copyOf(comando)); return resposta.apply(comando); }
    }

    private static PlanoAtualizacao plano(Path tmp, PlanoAtualizacao.Gatilho g) {
        return new PlanoAtualizacao("9.9.9", "1.0.0", tmp.resolve("n.deb").toString(), "ab".repeat(32), "n.deb", ManifestoRelease.FormatoInstalado.INSTALADOR,
                Optional.of("/opt/agroease-agente-impressao/bin/AgroEase-Agente-Impressao"), tmp.toString(), Instant.now(), g);
    }

    @Test
    void autoNaoPedeSenha(@TempDir Path tmp) {
        Cmd cmd = new Cmd();
        AplicadorAtualizacao.Instalador.Resultado r = new InstaladorDpkgAssistido(cmd).aplicar(plano(tmp, PlanoAtualizacao.Gatilho.AUTO));
        assertThat(r.ok()).isFalse();
        assertThat(r.detalhe()).containsIgnoringCase("senha").contains("Atualizar");
        assertThat(cmd.chamadas).isEmpty();
    }

    @Test
    void manualUsaPkexec(@TempDir Path tmp) {
        Cmd cmd = new Cmd();
        AplicadorAtualizacao.Instalador.Resultado r = new InstaladorDpkgAssistido(cmd).aplicar(plano(tmp, PlanoAtualizacao.Gatilho.MANUAL));
        assertThat(r.ok()).isTrue();
        assertThat(cmd.chamadas).hasSize(1);
        assertThat(cmd.chamadas.get(0)).containsExactly("pkexec", "--disable-internal-agent", "dpkg", "-i", tmp.resolve("n.deb").toString());
        assertThat(r.launcherNovo()).map(Path::toString).contains("/opt/agroease-agente-impressao/bin/AgroEase-Agente-Impressao");
    }

    @Test
    void canceladoEOutrosErros(@TempDir Path tmp) {
        for (int codigo : new int[] {126, 127}) {
            Cmd cmd = new Cmd(); cmd.resposta = c -> new ComandoExterno.Saida(codigo, "");
            AplicadorAtualizacao.Instalador.Resultado r = new InstaladorDpkgAssistido(cmd).aplicar(plano(tmp, PlanoAtualizacao.Gatilho.MANUAL));
            assertThat(r.ok()).isFalse();
            assertThat(r.detalhe()).containsIgnoringCase("cancelad");
        }
        Cmd cmd = new Cmd(); cmd.resposta = c -> new ComandoExterno.Saida(1, "dpkg: error processing");
        AplicadorAtualizacao.Instalador.Resultado r = new InstaladorDpkgAssistido(cmd).aplicar(plano(tmp, PlanoAtualizacao.Gatilho.MANUAL));
        assertThat(r.ok()).isFalse();
        assertThat(r.detalhe()).contains("dpkg: error processing");
        assertThat(new InstaladorDpkgAssistido(cmd).reverter(new EstadoAtualizacao.EmAplicacao("9.9.9", "1.0.0", null, Instant.now()))).isFalse();
    }
}

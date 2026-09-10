package br.com.wagner.wagsyspet.agente.app;

import br.com.wagner.wagsyspet.agente.core.atualizacao.EstadoAtualizacao;
import br.com.wagner.wagsyspet.agente.core.atualizacao.PlanoAtualizacao;
import br.com.wagner.wagsyspet.agente.core.pareamento.DiretoriosDoAgente;
import br.com.wagner.wagsyspet.agente.protocolo.release.ManifestoRelease;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** O ATUALIZADOR ({@code --aplicar-atualizacao plano.json}): espera o agente sair, aplica pelo instalador do SO, relança; falhou → recusa + relança o antigo. */
@DisplayName("AplicadorAtualizacao — espera o lock, aplica, relança; falha do instalador → recusada por 24 h e relança o antigo")
class AplicadorAtualizacaoTest {

    private static PlanoAtualizacao plano(Path tmp) throws Exception {
        Path art = tmp.resolve("atualizacao").resolve("baixado").resolve("novo.bin");
        Files.createDirectories(art.getParent());
        Files.write(art, "novo".getBytes(StandardCharsets.UTF_8));
        return new PlanoAtualizacao("9.9.9", "1.0.0", art.toString(), "ab".repeat(32), "novo.bin", ManifestoRelease.FormatoInstalado.INSTALADOR,
                Optional.of(tmp.resolve("bin/AgroEase-Agente-Impressao").toString()), tmp.toString(), Instant.now(), PlanoAtualizacao.Gatilho.AUTO);
    }

    @Test
    @DisplayName("instalador OK → relança o launcher NOVO devolvido pelo instalador, plano.json apagado, saída 0; o estado (emAplicacao) fica para o novo agente confirmar")
    void sucesso(@TempDir Path tmp) throws Exception {
        DiretoriosDoAgente dirs = new DiretoriosDoAgente(tmp);
        EstadoAtualizacao estado = new EstadoAtualizacao(dirs.atualizacao().resolve("estado.json"));
        estado.gravar(EstadoAtualizacao.Estado.VAZIO.comEmAplicacao(new EstadoAtualizacao.EmAplicacao("9.9.9", "1.0.0", null, Instant.now()), 0));
        PlanoAtualizacao p = plano(tmp);
        Path arquivoPlano = dirs.atualizacao().resolve("plano.json");
        p.gravar(arquivoPlano);
        List<Path> relancados = new ArrayList<>();
        AplicadorAtualizacao a = new AplicadorAtualizacao(new PrintStream(new ByteArrayOutputStream()),
                plano -> new AplicadorAtualizacao.Instalador.Resultado(true, "instalado", Optional.of(Path.of("/novo/launcher"))),
                relancados::add, Duration.ofSeconds(2));
        assertThat(a.aplicar(arquivoPlano)).isZero();
        assertThat(relancados).containsExactly(Path.of("/novo/launcher")); // como Path: no Windows a string vira \\novo\\launcher
        assertThat(Files.exists(arquivoPlano)).as("plano consumido").isFalse();
        assertThat(estado.ler().emAplicacao()).as("quem confirma é o agente novo, pela saúde").isPresent();
    }

    @Test
    @DisplayName("instalador FALHOU → estado recusada (24 h) sem emAplicacao, relança o launcher ANTERIOR, saída 2")
    void falha(@TempDir Path tmp) throws Exception {
        DiretoriosDoAgente dirs = new DiretoriosDoAgente(tmp);
        EstadoAtualizacao estado = new EstadoAtualizacao(dirs.atualizacao().resolve("estado.json"));
        estado.gravar(EstadoAtualizacao.Estado.VAZIO.comEmAplicacao(new EstadoAtualizacao.EmAplicacao("9.9.9", "1.0.0", null, Instant.now()), 0));
        PlanoAtualizacao p = plano(tmp);
        Path arquivoPlano = dirs.atualizacao().resolve("plano.json");
        p.gravar(arquivoPlano);
        List<Path> relancados = new ArrayList<>();
        ByteArrayOutputStream saida = new ByteArrayOutputStream();
        AplicadorAtualizacao a = new AplicadorAtualizacao(new PrintStream(saida, true, StandardCharsets.UTF_8),
                plano -> new AplicadorAtualizacao.Instalador.Resultado(false, "msiexec 1603", Optional.empty()),
                relancados::add, Duration.ofSeconds(2));
        assertThat(a.aplicar(arquivoPlano)).isEqualTo(Main.SAIDA_FALHA);
        assertThat(relancados).containsExactly(tmp.resolve("bin/AgroEase-Agente-Impressao"));
        EstadoAtualizacao.Estado e = estado.ler();
        assertThat(e.emAplicacao()).isEmpty();
        assertThat(e.recusada()).map(EstadoAtualizacao.Recusada::versao).contains("9.9.9");
        assertThat(saida.toString(StandardCharsets.UTF_8)).contains("msiexec 1603");
    }

    @Test
    @DisplayName("agente ainda vivo (lock ocupado) além do prazo → não aplica, não relança, saída 2 e emAplicacao limpo (o agente vivo segue na versão atual)")
    void agenteNaoSaiu(@TempDir Path tmp) throws Exception {
        DiretoriosDoAgente dirs = new DiretoriosDoAgente(tmp);
        dirs.garantir();
        EstadoAtualizacao estado = new EstadoAtualizacao(dirs.atualizacao().resolve("estado.json"));
        estado.gravar(EstadoAtualizacao.Estado.VAZIO.comEmAplicacao(new EstadoAtualizacao.EmAplicacao("9.9.9", "1.0.0", null, Instant.now()), 0));
        Path arquivoPlano = dirs.atualizacao().resolve("plano.json");
        plano(tmp).gravar(arquivoPlano);
        try (TravaDeInstancia ocupada = TravaDeInstancia.tentar(dirs.lock()).orElseThrow()) {
            List<Path> relancados = new ArrayList<>();
            AplicadorAtualizacao a = new AplicadorAtualizacao(new PrintStream(new ByteArrayOutputStream()),
                    plano -> { throw new AssertionError("não devia aplicar com o agente vivo"); },
                    relancados::add, Duration.ofMillis(400));
            assertThat(a.aplicar(arquivoPlano)).isEqualTo(Main.SAIDA_FALHA);
            assertThat(relancados).isEmpty();
        }
        assertThat(estado.ler().emAplicacao()).isEmpty();
    }
}

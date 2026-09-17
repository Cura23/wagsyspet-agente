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
        // sha256 REAL do conteúdo: o atualizador reconfere o arquivo antes de executar (Fecho F6)
        String sha = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest("novo".getBytes(StandardCharsets.UTF_8)));
        return new PlanoAtualizacao("9.9.9", "1.0.0", art.toString(), sha, "novo.bin", ManifestoRelease.FormatoInstalado.INSTALADOR,
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
    @DisplayName("antes de instalar o atualizador PAUSA o supervisor (Windows: tarefa keepalive) — cobre o --atualizar do CLI, onde não há agente para pausar; a pausa falhando não impede a instalação")
    void pausaSupervisorAntesDeInstalar(@TempDir Path tmp) throws Exception {
        DiretoriosDoAgente dirs = new DiretoriosDoAgente(tmp);
        PlanoAtualizacao p = plano(tmp);
        Path arquivoPlano = dirs.atualizacao().resolve("plano.json");
        p.gravar(arquivoPlano);
        List<String> ordem = new ArrayList<>();
        AplicadorAtualizacao a = new AplicadorAtualizacao(new PrintStream(new ByteArrayOutputStream()),
                plano -> { ordem.add("instalar"); return new AplicadorAtualizacao.Instalador.Resultado(true, "ok", Optional.empty()); },
                launcher -> ordem.add("relancar"), Duration.ofSeconds(2),
                plano -> { ordem.add("pausar:" + plano.versaoNova()); throw new IllegalStateException("schtasks negado"); });
        assertThat(a.aplicar(arquivoPlano)).isZero();
        assertThat(ordem).containsExactly("pausar:9.9.9", "instalar", "relancar");
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

    @Test
    @DisplayName("atualizador DESISTE sem instalar (agente não soltou a trava; plano ilegível) → DESFAZ a pausa do supervisor que o agente fez ao sair (senão a loja fica sem keepalive até o próximo login — adversarial L3 r2) e NÃO relança (com o agente vivo, relançar mataria a instância no macOS)")
    void cancelamentosDesfazemAPausa(@TempDir Path tmp) throws Exception {
        DiretoriosDoAgente dirs = new DiretoriosDoAgente(tmp);
        dirs.garantir();
        PlanoAtualizacao p = plano(tmp);
        Path arquivoPlano = dirs.atualizacao().resolve("plano.json");
        p.gravar(arquivoPlano);
        List<String> ordem = new ArrayList<>();
        AplicadorAtualizacao a = new AplicadorAtualizacao(new PrintStream(new ByteArrayOutputStream()),
                plano -> { ordem.add("instalar"); return new AplicadorAtualizacao.Instalador.Resultado(true, "ok", Optional.empty()); },
                launcher -> ordem.add("relancar:" + launcher.getFileName()), Duration.ofMillis(300), plano -> ordem.add("pausar"),
                plano -> ordem.add("desfazer-pausa:" + plano.map(PlanoAtualizacao::versaoNova).orElse("sem-plano")));
        try (TravaDeInstancia ocupada = TravaDeInstancia.tentar(dirs.lock()).orElseThrow()) {
            assertThat(a.aplicar(arquivoPlano)).isEqualTo(Main.SAIDA_FALHA);
        }
        assertThat(ordem).containsExactly("desfazer-pausa:9.9.9");

        ordem.clear();
        Path lixo = dirs.atualizacao().resolve("lixo.json");
        Files.writeString(lixo, "{ isto não é um plano");
        assertThat(a.aplicar(lixo)).isEqualTo(Main.SAIDA_FALHA);
        assertThat(ordem).containsExactly("desfazer-pausa:sem-plano");
    }

    @Test
    @DisplayName("plano INVERTIDO (reversão entregue pela sentinela): MSI anterior OK → é o ATUALIZADOR que registra 'versão nova recusada 24 h' e limpa o emAplicacao; MSI anterior FALHOU → esquece a troca SEM recusar nada (a versão nova segue rodando: dizer 'revertida' seria mentira — adversarial L3 r2)")
    void planoInvertidoFechaOEstadoComOResultadoReal(@TempDir Path tmp) throws Exception {
        DiretoriosDoAgente dirs = new DiretoriosDoAgente(tmp);
        EstadoAtualizacao estado = new EstadoAtualizacao(dirs.atualizacao().resolve("estado.json"));
        Path exeAnterior = tmp.resolve("anterior.exe"); Files.writeString(exeAnterior, "old");
        PlanoAtualizacao invertido = new PlanoAtualizacao("1.0.0", "9.9.9", exeAnterior.toString(), "", "anterior.exe", ManifestoRelease.FormatoInstalado.INSTALADOR,
                Optional.of(tmp.resolve("bin/AgroEase-Agente-Impressao").toString()), tmp.toString(), Instant.now(), PlanoAtualizacao.Gatilho.AUTO);
        Path arquivoPlano = dirs.atualizacao().resolve("plano.json");

        estado.gravar(EstadoAtualizacao.Estado.VAZIO.comEmAplicacao(new EstadoAtualizacao.EmAplicacao("9.9.9", "1.0.0", null, Instant.now()), 2));
        invertido.gravar(arquivoPlano);
        AplicadorAtualizacao ok = new AplicadorAtualizacao(new PrintStream(new ByteArrayOutputStream()),
                plano -> new AplicadorAtualizacao.Instalador.Resultado(true, "msiexec 0", Optional.empty()), launcher -> { }, Duration.ofSeconds(2));
        assertThat(ok.aplicar(arquivoPlano)).isZero();
        assertThat(estado.ler().emAplicacao()).isEmpty();
        assertThat(estado.ler().recusada()).map(EstadoAtualizacao.Recusada::versao).contains("9.9.9");

        estado.gravar(EstadoAtualizacao.Estado.VAZIO.comEmAplicacao(new EstadoAtualizacao.EmAplicacao("9.9.9", "1.0.0", null, Instant.now()), 2));
        invertido.gravar(arquivoPlano);
        AplicadorAtualizacao falhou = new AplicadorAtualizacao(new PrintStream(new ByteArrayOutputStream()),
                plano -> new AplicadorAtualizacao.Instalador.Resultado(false, "msiexec 1603", Optional.empty()), launcher -> { }, Duration.ofSeconds(2));
        assertThat(falhou.aplicar(arquivoPlano)).isEqualTo(Main.SAIDA_FALHA);
        assertThat(estado.ler().emAplicacao()).isEmpty();
        assertThat(estado.ler().recusada()).as("a 9.9.9 continua instalada e rodando: nada de 'recusada'").isEmpty();
    }

    // ---------- Fecho F6 ----------

    @Test
    @DisplayName("TOCTOU: o instalador foi TROCADO na pasta de dados (gravável pelo usuário) depois de o agente conferir e sair → o atualizador reconfere o sha256 do plano e NÃO executa (no .deb isso atravessaria para root via pkexec); recusa, relança o anterior, saída 2")
    void arquivoTrocadoDepoisDoPlano(@TempDir Path tmp) throws Exception {
        DiretoriosDoAgente dirs = new DiretoriosDoAgente(tmp);
        EstadoAtualizacao estado = new EstadoAtualizacao(dirs.atualizacao().resolve("estado.json"));
        estado.gravar(EstadoAtualizacao.Estado.VAZIO.comEmAplicacao(new EstadoAtualizacao.EmAplicacao("9.9.9", "1.0.0", null, Instant.now()), 0));
        PlanoAtualizacao p = plano(tmp);
        Path arquivoPlano = dirs.atualizacao().resolve("plano.json");
        p.gravar(arquivoPlano);
        Files.write(Path.of(p.artefato()), "TROCADO por outro processo do mesmo usuário".getBytes(StandardCharsets.UTF_8));
        List<Path> relancados = new ArrayList<>();
        ByteArrayOutputStream saida = new ByteArrayOutputStream();
        AplicadorAtualizacao a = new AplicadorAtualizacao(new PrintStream(saida, true, StandardCharsets.UTF_8),
                plano -> { throw new AssertionError("arquivo que não confere NUNCA pode ser executado"); },
                relancados::add, Duration.ofSeconds(2));
        assertThat(a.aplicar(arquivoPlano)).isEqualTo(Main.SAIDA_FALHA);
        assertThat(saida.toString(StandardCharsets.UTF_8)).contains("sha256");
        assertThat(relancados).containsExactly(tmp.resolve("bin/AgroEase-Agente-Impressao"));
        assertThat(estado.ler().recusada()).map(EstadoAtualizacao.Recusada::versao).contains("9.9.9");
    }

    @Test
    @DisplayName("o atualizador SEGURA a trava de instância enquanto instala e só a solta para relançar: um agente aberto no meio (atalho, keepalive, Run) cai na 2ª instância e sai sem tocar em nada — antes ele subia o binário VELHO, reabilitava o keepalive e prendia os arquivos que o instalador ia trocar")
    void seguraATravaDuranteAInstalacao(@TempDir Path tmp) throws Exception {
        DiretoriosDoAgente dirs = new DiretoriosDoAgente(tmp);
        dirs.garantir();
        PlanoAtualizacao p = plano(tmp);
        Path arquivoPlano = dirs.atualizacao().resolve("plano.json");
        p.gravar(arquivoPlano);
        List<String> visto = new ArrayList<>();
        AplicadorAtualizacao a = new AplicadorAtualizacao(new PrintStream(new ByteArrayOutputStream()),
                plano -> {
                    try (var t = TravaDeInstancia.tentar(dirs.lock()).orElse(null)) {
                        visto.add("instalando:trava " + (t == null ? "TOMADA" : "livre"));
                    } catch (java.io.IOException e) {
                        visto.add("instalando:erro " + e);
                    }
                    return new AplicadorAtualizacao.Instalador.Resultado(true, "ok", Optional.empty());
                },
                launcher -> {
                    try (var t = TravaDeInstancia.tentar(dirs.lock()).orElse(null)) {
                        visto.add("relancando:trava " + (t == null ? "TOMADA" : "livre"));
                    }
                }, Duration.ofSeconds(2), plano -> visto.add("pausar"));
        assertThat(a.aplicar(arquivoPlano)).isZero();
        assertThat(visto).containsExactly("pausar", "instalando:trava TOMADA", "relancando:trava livre");
    }
}

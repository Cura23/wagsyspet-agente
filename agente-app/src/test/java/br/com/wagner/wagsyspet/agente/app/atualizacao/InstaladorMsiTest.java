package br.com.wagner.wagsyspet.agente.app.atualizacao;

import br.com.wagner.wagsyspet.agente.app.AplicadorAtualizacao;
import br.com.wagner.wagsyspet.agente.app.autostart.ComandoExterno;
import br.com.wagner.wagsyspet.agente.core.atualizacao.EstadoAtualizacao;
import br.com.wagner.wagsyspet.agente.core.atualizacao.PlanoAtualizacao;
import br.com.wagner.wagsyspet.agente.protocolo.release.ManifestoRelease;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Windows: o primitivo é o upgrade MSI silencioso do próprio .exe do jpackage (wrapper repassa os args ao msiexec) — plano F6 D1. */
@DisplayName("InstaladorMsi — <novo>.exe /qn /norestart /L*v; 0/3010/1641 = ok; 1618 = retry; outro = reinstala o anterior guardado")
class InstaladorMsiTest {

    static final class Cmd implements ComandoExterno {
        final List<List<String>> chamadas = new ArrayList<>();
        java.util.function.Function<List<String>, Saida> resposta = c -> new Saida(0, "");
        @Override public Saida executar(List<String> comando) { chamadas.add(List.copyOf(comando)); return resposta.apply(comando); }
    }

    private static PlanoAtualizacao plano(Path tmp, Path exe) {
        return new PlanoAtualizacao("9.9.9", "1.0.0", exe.toString(), "ab".repeat(32), exe.getFileName().toString(), ManifestoRelease.FormatoInstalado.INSTALADOR,
                Optional.of("C:\\Users\\loja\\AppData\\Local\\AgroEase-Agente-Impressao\\AgroEase-Agente-Impressao.exe"), tmp.toString(), Instant.now(), PlanoAtualizacao.Gatilho.AUTO);
    }

    @Test
    @DisplayName("sucesso (0, 3010 e 1641 contam): comando exato, log ao lado do plano, launcher novo = o mesmo caminho (upgrade in-place)")
    void sucesso(@TempDir Path tmp) throws Exception {
        Path exe = tmp.resolve("novo.exe"); Files.writeString(exe, "x");
        for (int codigo : new int[] {0, 3010, 1641}) {
            Cmd cmd = new Cmd(); cmd.resposta = c -> new ComandoExterno.Saida(codigo, "");
            InstaladorMsi i = new InstaladorMsi(cmd, tmp.resolve("anterior"), tmp.resolve("logs"), ms -> { });
            AplicadorAtualizacao.Instalador.Resultado r = i.aplicar(plano(tmp, exe));
            assertThat(r.ok()).as("código " + codigo).isTrue();
            assertThat(r.launcherNovo()).map(Path::toString).contains("C:\\Users\\loja\\AppData\\Local\\AgroEase-Agente-Impressao\\AgroEase-Agente-Impressao.exe");
            assertThat(cmd.chamadas).hasSize(1);
            assertThat(cmd.chamadas.get(0)).startsWith(exe.toString(), "/qn", "/norestart", "/L*v");
            assertThat(cmd.chamadas.get(0).get(4)).endsWith("msi-9.9.9.log");
        }
    }

    @Test
    @DisplayName("1618 (outra instalação em curso) → espera e tenta de novo até 5 vezes; desiste com FALHOU")
    void retry1618(@TempDir Path tmp) throws Exception {
        Path exe = tmp.resolve("novo.exe"); Files.writeString(exe, "x");
        AtomicInteger n = new AtomicInteger();
        Cmd cmd = new Cmd(); cmd.resposta = c -> new ComandoExterno.Saida(n.incrementAndGet() < 3 ? 1618 : 0, "");
        List<Long> esperas = new ArrayList<>();
        InstaladorMsi i = new InstaladorMsi(cmd, tmp.resolve("anterior"), tmp.resolve("logs"), esperas::add);
        assertThat(i.aplicar(plano(tmp, exe)).ok()).isTrue();
        assertThat(cmd.chamadas).hasSize(3);
        assertThat(esperas).containsExactly(InstaladorMsi.ESPERA_1618_MS, InstaladorMsi.ESPERA_1618_MS);

        Cmd sempre = new Cmd(); sempre.resposta = c -> new ComandoExterno.Saida(1618, "");
        InstaladorMsi j = new InstaladorMsi(sempre, tmp.resolve("anterior"), tmp.resolve("logs"), ms -> { });
        AplicadorAtualizacao.Instalador.Resultado r = j.aplicar(plano(tmp, exe));
        assertThat(r.ok()).isFalse();
        assertThat(sempre.chamadas).hasSize(InstaladorMsi.TENTATIVAS_1618);
        assertThat(r.detalhe()).contains("1618");
    }

    /** Guarda como o GuardaAnterior guarda: .exe + versao.txt (versão, arquivo, sha256). */
    static Path guardar(Path anterior, String versao, String conteudo) throws Exception {
        Files.createDirectories(anterior);
        Path exe = anterior.resolve("AgroEase-Agente-Impressao-" + versao + "-windows-x64.exe");
        Files.writeString(exe, conteudo);
        String sha = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(conteudo.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        Files.writeString(anterior.resolve("versao.txt"), versao + "\n" + exe.getFileName() + "\n" + sha + "\n");
        return exe;
    }

    @Test
    @DisplayName("falha (1603) com o instalador da versão ANTERIOR guardado E CONFERIDO (versao.txt = versão anterior do plano, sha bate) → reinstala; .exe de OUTRA versão, sem marcador ou adulterado → NÃO usa (rollback para a versão errada é pior que nenhum — adversarial L3)")
    void falhaReinstalaAnterior(@TempDir Path tmp) throws Exception {
        Path exe = tmp.resolve("novo.exe"); Files.writeString(exe, "x");
        Path anterior = tmp.resolve("anterior");
        Path exeAnterior = guardar(anterior, "1.0.0", "old");
        Cmd cmd = new Cmd(); cmd.resposta = c -> new ComandoExterno.Saida(c.get(0).equals(exe.toString()) ? 1603 : 0, "");
        InstaladorMsi i = new InstaladorMsi(cmd, anterior, tmp.resolve("logs"), ms -> { });
        AplicadorAtualizacao.Instalador.Resultado r = i.aplicar(plano(tmp, exe));
        assertThat(r.ok()).isFalse();
        assertThat(r.detalhe()).contains("1603").containsIgnoringCase("anterior");
        assertThat(cmd.chamadas).hasSize(2);
        assertThat(cmd.chamadas.get(1)).startsWith(exeAnterior.toString(), "/qn", "/norestart");

        Cmd semAnterior = new Cmd(); semAnterior.resposta = c -> new ComandoExterno.Saida(1603, "");
        assertThat(new InstaladorMsi(semAnterior, tmp.resolve("vazio"), tmp.resolve("logs"), ms -> { }).aplicar(plano(tmp, exe)).ok()).isFalse();
        assertThat(semAnterior.chamadas).hasSize(1);

        Cmd outraVersao = new Cmd(); outraVersao.resposta = c -> new ComandoExterno.Saida(1603, "");
        guardar(tmp.resolve("velho"), "0.9.0", "muito-old"); // sobrou de um update antigo; o plano diz que a anterior é 1.0.0
        assertThat(new InstaladorMsi(outraVersao, tmp.resolve("velho"), tmp.resolve("logs"), ms -> { }).aplicar(plano(tmp, exe)).detalhe()).containsIgnoringCase("sem instalador anterior");
        assertThat(outraVersao.chamadas).hasSize(1);

        Cmd semMarcador = new Cmd(); semMarcador.resposta = c -> new ComandoExterno.Saida(1603, "");
        Files.createDirectories(tmp.resolve("solto")); Files.writeString(tmp.resolve("solto/AgroEase-Agente-Impressao-1.0.0-windows-x64.exe"), "old");
        new InstaladorMsi(semMarcador, tmp.resolve("solto"), tmp.resolve("logs"), ms -> { }).aplicar(plano(tmp, exe));
        assertThat(semMarcador.chamadas).hasSize(1);

        Cmd adulterado = new Cmd(); adulterado.resposta = c -> new ComandoExterno.Saida(1603, "");
        Path trocado = guardar(tmp.resolve("trocado"), "1.0.0", "old"); Files.writeString(trocado, "outro conteúdo");
        new InstaladorMsi(adulterado, tmp.resolve("trocado"), tmp.resolve("logs"), ms -> { }).aplicar(plano(tmp, exe));
        assertThat(adulterado.chamadas).hasSize(1);
    }
}

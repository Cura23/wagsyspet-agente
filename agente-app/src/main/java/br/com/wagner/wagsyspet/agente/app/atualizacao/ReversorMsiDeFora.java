package br.com.wagner.wagsyspet.agente.app.atualizacao;

import br.com.wagner.wagsyspet.agente.core.atualizacao.EstadoAtualizacao;
import br.com.wagner.wagsyspet.agente.core.atualizacao.GerenteAtualizacao;
import br.com.wagner.wagsyspet.agente.core.atualizacao.GuardaAnterior;
import br.com.wagner.wagsyspet.agente.core.atualizacao.LancadorAtualizador;
import br.com.wagner.wagsyspet.agente.core.atualizacao.PlanoAtualizacao;
import br.com.wagner.wagsyspet.agente.core.atualizacao.Reversor;
import br.com.wagner.wagsyspet.agente.core.pareamento.DiretoriosDoAgente;
import br.com.wagner.wagsyspet.agente.protocolo.release.ManifestoRelease;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Reversão no Windows (adversarial L3): o MSI da versão anterior NÃO pode rodar de dentro do agente instalado — o Windows Installer
 * em modo silencioso usa o Restart Manager e derruba (ou adia para o reboot, devolvendo 3010) os arquivos que o próprio processo
 * segura. A sentinela de boot só PREPARA a volta: confere o instalador guardado ({@link GuardaAnterior#guardado}), grava um plano
 * INVERTIDO (nova = a anterior), pausa o keepalive e entrega ao mesmo atualizador "de fora" do update; o agente então sai (código 5)
 * e o atualizador instala e relança pela tarefa.
 */
public final class ReversorMsiDeFora implements Reversor {

    private static final Logger log = LoggerFactory.getLogger(ReversorMsiDeFora.class);

    private final DiretoriosDoAgente dirs;
    private final LancadorAtualizador lancador;
    private final Supplier<Optional<Path>> launcher;
    private final Runnable pausarSupervisor;
    private final Runnable retomarSupervisor;

    public ReversorMsiDeFora(DiretoriosDoAgente dirs, LancadorAtualizador lancador, Supplier<Optional<Path>> launcher, Runnable pausarSupervisor, Runnable retomarSupervisor) {
        this.dirs = dirs;
        this.lancador = lancador;
        this.launcher = launcher;
        this.pausarSupervisor = pausarSupervisor;
        this.retomarSupervisor = retomarSupervisor;
    }

    @Override
    public boolean adiada() {
        return true;
    }

    @Override
    public boolean reverter(EstadoAtualizacao.EmAplicacao ap) {
        Optional<Path> anterior = GuardaAnterior.guardado(dirs.atualizacao().resolve("anterior"), ap.versaoAnterior());
        if (anterior.isEmpty()) {
            log.warn("Sem instalador da versão {} guardado e conferido; não há como reverter {}", ap.versaoAnterior(), ap.versaoNova());
            return false;
        }
        Path exe = anterior.get();
        Path arquivoPlano = dirs.atualizacao().resolve(GerenteAtualizacao.ARQUIVO_PLANO);
        try {
            // com o sha do guardado: o atualizador de fora reconfere o arquivo antes de executar
            String sha = GuardaAnterior.shaGuardado(dirs.atualizacao().resolve("anterior"), ap.versaoAnterior()).orElse("");
            new PlanoAtualizacao(ap.versaoAnterior(), ap.versaoNova(), exe.toString(), sha, exe.getFileName().toString(),
                    ManifestoRelease.FormatoInstalado.INSTALADOR, launcher.get().map(Path::toString), dirs.raiz().toString(), Instant.now(),
                    PlanoAtualizacao.Gatilho.AUTO).gravar(arquivoPlano);
        } catch (IOException e) {
            log.error("Não consegui gravar o plano de reversão: {}", e.toString());
            return false;
        }
        pausarSupervisor.run(); // o keepalive não pode reabrir a versão ruim enquanto o atualizador instala a anterior
        try {
            lancador.lancar(arquivoPlano);
        } catch (IOException | RuntimeException e) {
            log.error("Não consegui lançar o atualizador para reverter: {}", e.toString());
            retomarSupervisor.run();
            try {
                Files.deleteIfExists(arquivoPlano);
            } catch (IOException ignorada) {
                // fica só o log acima
            }
            return false;
        }
        log.warn("Reversão {} → {} entregue ao atualizador ({})", ap.versaoNova(), ap.versaoAnterior(), exe.getFileName());
        return true;
    }
}

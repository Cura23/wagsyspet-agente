package br.com.wagner.wagsyspet.agente.app.atualizacao;

import br.com.wagner.wagsyspet.agente.app.AplicadorAtualizacao;
import br.com.wagner.wagsyspet.agente.app.autostart.Autostart;
import br.com.wagner.wagsyspet.agente.app.autostart.ComandoExterno;
import br.com.wagner.wagsyspet.agente.core.atualizacao.PlanoAtualizacao;
import br.com.wagner.wagsyspet.agente.core.atualizacao.Reversor;
import br.com.wagner.wagsyspet.agente.core.pareamento.DiretoriosDoAgente;
import br.com.wagner.wagsyspet.agente.protocolo.release.ManifestoRelease;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/** Escolhe a estratégia de instalação/reversão pelo SO e pelo formato instalado (plano F6 D1). */
public final class Instaladores {

    static final Duration PRAZO_INSTALADOR = Duration.ofMinutes(10);

    private Instaladores() {
    }

    /** Instalador do atualizador, a partir do plano (SO corrente). */
    public static AplicadorAtualizacao.Instalador paraEsteSo(PlanoAtualizacao plano) {
        DiretoriosDoAgente dirs = new DiretoriosDoAgente(Path.of(plano.dirDados()));
        Optional<Path> launcher = plano.launcherAtual().map(Path::of);
        return estrategia(System.getProperty("os.name"), plano.formato(), launcher, dirs).orElse(AplicadorAtualizacao.Instalador.indisponivel());
    }

    /** Reversor do agente (sentinela de boot): mesma estratégia, precisa saber onde está instalado. */
    public static Reversor reversorDesteSo(DiretoriosDoAgente dirs, ManifestoRelease.FormatoInstalado formato) {
        return estrategia(System.getProperty("os.name"), formato, Autostart.launcherDesteProcesso(), dirs)
                .map(i -> (Reversor) i).orElse(Reversor.NENHUM);
    }

    static Optional<AplicadorAtualizacao.Instalador> estrategia(String osName, ManifestoRelease.FormatoInstalado formato, Optional<Path> launcher, DiretoriosDoAgente dirs) {
        Path anterior = dirs.atualizacao().resolve("anterior");
        Path staging = dirs.atualizacao().resolve("staging");
        ComandoExterno cmd = ComandoExterno.real(PRAZO_INSTALADOR);
        if (Plataforma.windows(osName)) {
            return Optional.of(new InstaladorMsi(cmd, anterior, dirs.logs(), ms -> {
                try {
                    Thread.sleep(ms);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        Optional<Path> raiz = launcher.flatMap(l -> Plataforma.raizDoAppImage(l, osName));
        if (Plataforma.mac(osName)) {
            return raiz.map(r -> new InstaladorTrocaDeDiretorio(cmd, r, anterior, staging));
        }
        if (formato == ManifestoRelease.FormatoInstalado.APP_IMAGE) {
            return raiz.map(r -> new InstaladorTrocaDeDiretorio(cmd, r, anterior, staging));
        }
        return Optional.of(new InstaladorDpkgAssistido(cmd));
    }
}

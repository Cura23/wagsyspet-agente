package br.com.wagner.wagsyspet.agente.app.atualizacao;

import br.com.wagner.wagsyspet.agente.app.AplicadorAtualizacao;
import br.com.wagner.wagsyspet.agente.app.autostart.ComandoExterno;
import br.com.wagner.wagsyspet.agente.core.atualizacao.EstadoAtualizacao;
import br.com.wagner.wagsyspet.agente.core.atualizacao.PlanoAtualizacao;
import br.com.wagner.wagsyspet.agente.core.atualizacao.Reversor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.LongConsumer;
import java.util.stream.Stream;

/**
 * Windows (plano F6 D1): o {@code .exe} do jpackage é um MSI embrulhado cujo wrapper repassa os argumentos ao {@code msiexec}; com o
 * {@code --win-upgrade-uuid} fixo e versão maior é um major upgrade in-place, per-user, sem UAC. Sucesso = 0, 3010 (reboot pendente,
 * ignorado com {@code /norestart}) ou 1641. 1618 = outra instalação em curso → espera e tenta de novo. Outro código → reinstala o
 * instalador ANTERIOR guardado (downgrade permitido pelo jpackage), porque o {@code RemoveExistingProducts} fica fora da transação e
 * uma falha no meio deixa a loja sem agente.
 */
public final class InstaladorMsi implements AplicadorAtualizacao.Instalador, Reversor {

    private static final Logger log = LoggerFactory.getLogger(InstaladorMsi.class);
    static final int TENTATIVAS_1618 = 5;
    static final long ESPERA_1618_MS = 30_000;
    private static final List<Integer> SUCESSO = List.of(0, 3010, 1641);

    private final ComandoExterno cmd;
    private final Path pastaAnterior;
    private final Path pastaLogs;
    private final LongConsumer espera;

    public InstaladorMsi(ComandoExterno cmd, Path pastaAnterior, Path pastaLogs, LongConsumer espera) {
        this.cmd = cmd;
        this.pastaAnterior = pastaAnterior;
        this.pastaLogs = pastaLogs;
        this.espera = espera;
    }

    @Override
    public Resultado aplicar(PlanoAtualizacao plano) {
        Path exe = Path.of(plano.artefato());
        int codigo = instalar(exe, "msi-" + plano.versaoNova() + ".log");
        if (SUCESSO.contains(codigo)) {
            return new Resultado(true, "msiexec " + codigo + " (upgrade in-place)", plano.launcherAtual().map(Path::of));
        }
        Optional<Path> anterior = instaladorAnterior();
        if (anterior.isPresent()) {
            int volta = instalar(anterior.get(), "msi-reinstalar-" + plano.versaoAnterior() + ".log");
            return new Resultado(false, "msiexec " + codigo + " ao instalar " + plano.versaoNova() + "; anterior reinstalado (msiexec " + volta + ")", Optional.empty());
        }
        return new Resultado(false, "msiexec " + codigo + " ao instalar " + plano.versaoNova() + " (sem instalador anterior guardado)", Optional.empty());
    }

    @Override
    public boolean reverter(EstadoAtualizacao.EmAplicacao ap) {
        Optional<Path> anterior = instaladorAnterior();
        if (anterior.isEmpty()) {
            log.warn("Sem instalador anterior guardado em {}; não há como reverter {}", pastaAnterior, ap.versaoNova());
            return false;
        }
        int codigo = instalar(anterior.get(), "msi-reverter-" + ap.versaoAnterior() + ".log");
        return SUCESSO.contains(codigo);
    }

    private int instalar(Path exe, String nomeLog) {
        Path logMsi = pastaLogs.resolve(nomeLog);
        try {
            Files.createDirectories(pastaLogs);
        } catch (IOException e) {
            log.debug("pasta de logs: {}", e.toString());
        }
        List<String> comando = List.of(exe.toString(), "/qn", "/norestart", "/L*v", logMsi.toString());
        for (int tentativa = 1; tentativa <= TENTATIVAS_1618; tentativa++) {
            int codigo;
            try {
                codigo = cmd.executar(comando).exit();
            } catch (IOException e) {
                log.error("Falha ao executar o instalador {}: {}", exe, e.toString());
                return -1;
            }
            log.info("{} /qn → {} (tentativa {})", exe.getFileName(), codigo, tentativa);
            if (codigo != 1618 || tentativa == TENTATIVAS_1618) {
                return codigo;
            }
            espera.accept(ESPERA_1618_MS);
        }
        return 1618;
    }

    private Optional<Path> instaladorAnterior() {
        if (!Files.isDirectory(pastaAnterior)) {
            return Optional.empty();
        }
        try (Stream<Path> s = Files.list(pastaAnterior)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".exe")).findFirst();
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}

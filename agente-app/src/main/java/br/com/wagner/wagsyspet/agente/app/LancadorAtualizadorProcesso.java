package br.com.wagner.wagsyspet.agente.app;

import br.com.wagner.wagsyspet.agente.app.autostart.Autostart;
import br.com.wagner.wagsyspet.agente.core.atualizacao.LancadorAtualizador;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Lança o atualizador como processo separado ({@code <launcher> --aplicar-atualizacao <plano.json>}), saída em arquivo na pasta de
 * atualização. F6-L1: usa o launcher instalado; o L2 troca por uma cópia do app-image lançada "de fora" (systemd-run/open) para
 * sobreviver ao supervisor que mata o grupo do processo. Sem launcher (java cru, dev) não há atualização automática.
 */
final class LancadorAtualizadorProcesso implements LancadorAtualizador {

    private static final Logger log = LoggerFactory.getLogger(LancadorAtualizadorProcesso.class);

    private final Supplier<Optional<Path>> launcher;

    LancadorAtualizadorProcesso(Supplier<Optional<Path>> launcher) {
        this.launcher = launcher;
    }

    static LancadorAtualizadorProcesso padrao() {
        return new LancadorAtualizadorProcesso(Autostart::launcherDesteProcesso);
    }

    @Override
    public void lancar(Path plano) throws IOException {
        Path l = launcher.get().orElseThrow(() -> new IOException("este processo não roda pelo binário instalado (java direto): atualização automática indisponível"));
        Path saida = plano.resolveSibling("atualizador.log");
        Files.createDirectories(plano.getParent());
        ProcessBuilder pb = ProcessoFilho.novo(List.of(l.toString(), "--aplicar-atualizacao", plano.toString()))
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(saida.toFile()));
        Process p = pb.start();
        log.info("Atualizador lançado (pid {}): {} --aplicar-atualizacao {}", p.pid(), l, plano);
    }
}

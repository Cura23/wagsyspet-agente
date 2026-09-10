package br.com.wagner.wagsyspet.agente.app.atualizacao;

import br.com.wagner.wagsyspet.agente.app.autostart.Autostart;
import br.com.wagner.wagsyspet.agente.app.autostart.ComandoExterno;
import br.com.wagner.wagsyspet.agente.core.atualizacao.LancadorAtualizador;
import br.com.wagner.wagsyspet.agente.core.pareamento.DiretoriosDoAgente;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Lança o ATUALIZADOR a partir de uma CÓPIA do app-image em {@code <dados>/atualizacao/atualizador/} (o instalador vai trocar a pasta
 * de onde o agente roda — Windows segura os jars, Linux/macOS trocam o diretório) e "de fora" do grupo de processos do agente, senão o
 * supervisor mata o filho junto: Linux sob systemd ({@code INVOCATION_ID}) → {@code systemd-run --user --collect}; macOS →
 * {@code open -n -a <cópia.app> --args}; caso contrário processo direto (sem os marcadores {@code _JPACKAGE*}). Plano F6 D2.
 */
public final class LancadorAtualizadorDeFora implements LancadorAtualizador {

    private static final Logger log = LoggerFactory.getLogger(LancadorAtualizadorDeFora.class);
    static final String ARQUIVO_VERSAO_COPIA = "versao-da-copia.txt";

    private final DiretoriosDoAgente dirs;
    private final String osName;
    private final Map<String, String> env;
    private final Supplier<Optional<Path>> launcher;
    private final String versaoAtual;
    private final ComandoExterno cmd;
    private final Consumer<List<String>> direto;

    public LancadorAtualizadorDeFora(DiretoriosDoAgente dirs, String osName, Map<String, String> env, Supplier<Optional<Path>> launcher,
                                     String versaoAtual, ComandoExterno cmd, Consumer<List<String>> direto) {
        this.dirs = dirs;
        this.osName = osName;
        this.env = env;
        this.launcher = launcher;
        this.versaoAtual = versaoAtual;
        this.cmd = cmd;
        this.direto = direto;
    }

    @Override
    public void lancar(Path plano) throws IOException {
        Path l = launcher.get().orElseThrow(() -> new IOException("este processo não roda pelo binário instalado (java direto): atualização automática indisponível"));
        Path raiz = Plataforma.raizDoAppImage(l, osName).orElseThrow(() -> new IOException("não reconheço a pasta de instalação a partir de " + l));
        Path copia = copiar(raiz);
        Path launcherCopia = Plataforma.launcherEm(copia, osName);
        List<String> args = List.of("--aplicar-atualizacao", plano.toString());
        if (Plataforma.mac(osName)) {
            List<String> c = new ArrayList<>(List.of("open", "-n", "-a", copia.toString(), "--args"));
            c.addAll(args);
            executar(c);
        } else if (!Plataforma.windows(osName) && SystemdRun.sobSystemd(env)) {
            List<String> programa = new ArrayList<>(List.of(launcherCopia.toString()));
            programa.addAll(args);
            try {
                executar(SystemdRun.comando("agroease-atualizador", env, programa));
            } catch (IOException e) {
                // sem gerente de usuário do systemd (runner de CI, sessão sem systemd --user): o filho direto sobrevive porque a
                // unidade que nos contém (o serviço do runner) não vai parar
                log.warn("systemd-run indisponível ({}); lançando o atualizador direto", e.getMessage());
                direto.accept(programa);
            }
        } else {
            List<String> c = new ArrayList<>(List.of(launcherCopia.toString()));
            c.addAll(args);
            direto.accept(c);
        }
        log.info("Atualizador lançado a partir da cópia {} para aplicar {}", copia, plano);
    }

    private Path copiar(Path raiz) throws IOException {
        Path destino = dirs.atualizacao().resolve("atualizador").resolve(raiz.getFileName());
        Path marca = destino.resolve(ARQUIVO_VERSAO_COPIA);
        if (Files.isRegularFile(marca) && versaoAtual.equals(Files.readString(marca, StandardCharsets.UTF_8).trim())
                && Files.isRegularFile(Plataforma.launcherEm(destino, osName))) {
            return destino; // cópia desta mesma versão já existe
        }
        InstaladorTrocaDeDiretorio.apagarArvore(destino);
        Files.createDirectories(destino.getParent());
        InstaladorTrocaDeDiretorio.copiarArvore(raiz, destino);
        Files.writeString(marca, versaoAtual, StandardCharsets.UTF_8);
        Path launcherCopia = Plataforma.launcherEm(destino, osName);
        if (Files.exists(launcherCopia) && !launcherCopia.toFile().setExecutable(true, false)) {
            log.debug("não consegui marcar {} como executável", launcherCopia);
        }
        log.info("App-image copiado para {} (atualizador roda fora da pasta que vai ser trocada)", destino);
        return destino;
    }

    private void executar(List<String> comando) throws IOException {
        ComandoExterno.Saida s = cmd.executar(comando);
        if (s.exit() != 0) {
            throw new IOException("não consegui lançar o atualizador (" + comando.get(0) + " saiu com " + s.exit() + "): " + s.texto());
        }
    }

    /** Fábrica com o ambiente real: processo direto passa por {@code ProcessoFilho} (sem {@code _JPACKAGE*}). */
    public static LancadorAtualizadorDeFora padrao(DiretoriosDoAgente dirs, String versaoAtual, Consumer<List<String>> direto) {
        return new LancadorAtualizadorDeFora(dirs, System.getProperty("os.name"), System.getenv(), Autostart::launcherDesteProcesso, versaoAtual, ComandoExterno.real(), direto);
    }
}

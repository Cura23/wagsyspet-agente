package br.com.wagner.wagsyspet.agente.core.pareamento;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

/**
 * Pasta de dados do agente POR USUÁRIO do SO (plano F3 D15), com a marca que o lojista vê (AgroEase):
 * <ul>
 *   <li>Windows: {@code %LOCALAPPDATA%\AgroEase\agente-impressao}</li>
 *   <li>Linux: {@code $XDG_CONFIG_HOME/agroease/agente-impressao} (default {@code ~/.config})</li>
 *   <li>macOS: {@code ~/Library/Application Support/AgroEase/agente-impressao}</li>
 * </ul>
 * Override para suporte/testes: env {@value #ENV_OVERRIDE} (ou {@code -Dagente.dir}). Conteúdo: {@code pareamento.enc}
 * (AES-GCM), {@code chave.bin}, {@code config.json}, {@code agente.lock}, {@code logs/}.
 */
public final class DiretoriosDoAgente {

    public static final String ENV_OVERRIDE = "AGROEASE_AGENTE_DIR";
    public static final String PROP_OVERRIDE = "agente.dir";

    private static final Set<PosixFilePermission> SO_DONO_DIR = PosixFilePermissions.fromString("rwx------");
    static final Set<PosixFilePermission> SO_DONO_ARQUIVO = PosixFilePermissions.fromString("rw-------");

    private final Path raiz;

    public DiretoriosDoAgente(Path raiz) {
        // sem toAbsolutePath(): as pastas por SO já vêm absolutas e um caminho de outro SO (teste) não pode ganhar o cwd
        this.raiz = Objects.requireNonNull(raiz).normalize();
    }

    /** Resolve com o ambiente REAL do processo. */
    public static DiretoriosDoAgente padrao() {
        return resolver(System.getenv(), System.getProperties());
    }

    /** Resolução pura (testável): {@code env} = variáveis de ambiente; {@code props} = {@code os.name}, {@code user.home}, override. */
    static DiretoriosDoAgente resolver(Map<String, String> env, Properties props) {
        String override = primeiro(props.getProperty(PROP_OVERRIDE), env.get(ENV_OVERRIDE));
        if (override != null) {
            return new DiretoriosDoAgente(Path.of(override).toAbsolutePath());
        }
        String os = props.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        Path home = Path.of(props.getProperty("user.home", "."));
        if (os.contains("win")) {
            String local = env.get("LOCALAPPDATA");
            Path base = local != null && !local.isBlank() ? Path.of(local) : home.resolve("AppData").resolve("Local");
            return new DiretoriosDoAgente(base.resolve("AgroEase").resolve("agente-impressao"));
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return new DiretoriosDoAgente(home.resolve("Library").resolve("Application Support").resolve("AgroEase").resolve("agente-impressao"));
        }
        String xdg = env.get("XDG_CONFIG_HOME");
        Path base = xdg != null && !xdg.isBlank() ? Path.of(xdg) : home.resolve(".config");
        return new DiretoriosDoAgente(base.resolve("agroease").resolve("agente-impressao"));
    }

    private static String primeiro(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b != null && !b.isBlank() ? b : null;
    }

    public Path raiz() {
        return raiz;
    }

    public Path pareamento() {
        return raiz.resolve("pareamento.enc");
    }

    public Path chave() {
        return raiz.resolve("chave.bin");
    }

    public Path config() {
        return raiz.resolve("config.json");
    }

    public Path lock() {
        return raiz.resolve("agente.lock");
    }

    public Path logs() {
        return raiz.resolve("logs");
    }

    /** Cria a árvore; em sistemas POSIX a raiz fica {@code 0700}. No Windows a herança do perfil do usuário já restringe. */
    public void garantir() throws IOException {
        Files.createDirectories(logs());
        if (suportaPosix(raiz)) {
            Files.setPosixFilePermissions(raiz, SO_DONO_DIR);
        }
    }

    static boolean suportaPosix(Path p) {
        try {
            return Files.getFileStore(p).supportsFileAttributeView("posix");
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public String toString() {
        return raiz.toString();
    }
}

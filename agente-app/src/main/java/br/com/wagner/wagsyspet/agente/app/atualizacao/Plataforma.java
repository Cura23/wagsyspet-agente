package br.com.wagner.wagsyspet.agente.app.atualizacao;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/** Geometria do app-image do jpackage por SO — tudo o que o L2 precisa saber sobre onde o agente está instalado. */
public final class Plataforma {

    public static final String NOME = "AgroEase-Agente-Impressao";

    private Plataforma() {
    }

    public static boolean windows(String osName) {
        return osName != null && osName.toLowerCase(Locale.ROOT).contains("win");
    }

    public static boolean mac(String osName) {
        String s = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        return s.contains("mac") || s.contains("darwin");
    }

    /**
     * Raiz do app-image a partir do launcher: Linux {@code <raiz>/bin/X}; macOS {@code <X.app>/Contents/MacOS/X} (a raiz é o .app);
     * Windows {@code <raiz>/X.exe}. Vazio quando o caminho não tem essa forma (java cru).
     */
    public static Optional<Path> raizDoAppImage(Path launcher, String osName) {
        if (launcher == null) {
            return Optional.empty();
        }
        if (windows(osName)) {
            return Optional.ofNullable(launcher.getParent());
        }
        if (mac(osName)) {
            Path macos = launcher.getParent();
            Path contents = macos == null ? null : macos.getParent();
            Path app = contents == null ? null : contents.getParent();
            if (macos != null && contents != null && app != null && "MacOS".equals(nome(macos)) && "Contents".equals(nome(contents)) && nome(app).endsWith(".app")) {
                return Optional.of(app);
            }
            return Optional.empty();
        }
        Path bin = launcher.getParent();
        if (bin != null && "bin".equals(nome(bin)) && bin.getParent() != null) {
            return Optional.of(bin.getParent());
        }
        return Optional.empty();
    }

    /** Launcher GUI dentro de uma raiz de app-image (a inversa de {@link #raizDoAppImage}). */
    public static Path launcherEm(Path raiz, String osName) {
        if (windows(osName)) {
            return raiz.resolve(NOME + ".exe");
        }
        if (mac(osName)) {
            return raiz.resolve("Contents").resolve("MacOS").resolve(NOME);
        }
        return raiz.resolve("bin").resolve(NOME);
    }

    private static String nome(Path p) {
        return p.getFileName() == null ? "" : p.getFileName().toString();
    }
}

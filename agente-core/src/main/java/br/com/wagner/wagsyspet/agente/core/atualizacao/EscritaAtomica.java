package br.com.wagner.wagsyspet.agente.core.atualizacao;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * Escrita atômica (tmp ao lado + move) — extraída das cópias privadas de {@code ConfiguracaoLocalArquivo} e {@code CofreCredencial}
 * (adversarial F6, mapa do código). Um leitor concorrente vê o arquivo antigo inteiro ou o novo inteiro, nunca meio escrito; queda
 * de energia no meio deixa no máximo um {@code .tmp} órfão, que a próxima escrita sobrescreve.
 */
public final class EscritaAtomica {

    private static final Set<PosixFilePermission> SO_DONO = PosixFilePermissions.fromString("rw-------");

    private EscritaAtomica() {
    }

    public static void gravarTexto(Path destino, String conteudo, boolean soDono) throws IOException {
        gravar(destino, conteudo.getBytes(StandardCharsets.UTF_8), soDono);
    }

    /** @param soDono em sistemas POSIX aplica {@code 0600} ao arquivo (segredos); no Windows a herança do perfil já restringe */
    public static void gravar(Path destino, byte[] conteudo, boolean soDono) throws IOException {
        Path dir = destino.toAbsolutePath().getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        Path tmp = destino.resolveSibling(destino.getFileName() + ".tmp");
        Files.write(tmp, conteudo);
        if (soDono && suportaPosix(tmp)) {
            Files.setPosixFilePermissions(tmp, SO_DONO);
        }
        try {
            Files.move(tmp, destino, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, destino, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static boolean suportaPosix(Path p) {
        try {
            return Files.getFileStore(p).supportsFileAttributeView("posix");
        } catch (IOException e) {
            return false;
        }
    }
}

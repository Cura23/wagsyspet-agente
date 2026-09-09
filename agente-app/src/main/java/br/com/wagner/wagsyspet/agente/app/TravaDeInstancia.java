package br.com.wagner.wagsyspet.agente.app;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/**
 * Instância única por usuário (plano F3 D20): {@code FileChannel.tryLock} em {@code agente.lock} na pasta do agente. O SO
 * libera o lock se o processo morrer (não fica lock órfão). Uma 2ª instância avisa e sai 0 ("não reinicie"). A porta
 * continua sendo a 2ª trava (outro programa na 28421 não tem o nosso lock).
 */
final class TravaDeInstancia implements AutoCloseable {

    private final FileChannel canal;
    private final FileLock lock;
    private final Path arquivo;

    private TravaDeInstancia(Path arquivo, FileChannel canal, FileLock lock) {
        this.arquivo = arquivo;
        this.canal = canal;
        this.lock = lock;
    }

    /** Vazio = outra instância (deste usuário) já tem o lock. */
    static Optional<TravaDeInstancia> tentar(Path arquivo) throws IOException {
        Path dir = arquivo.toAbsolutePath().getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        FileChannel canal = FileChannel.open(arquivo, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ);
        try {
            FileLock lock = canal.tryLock();
            if (lock == null) {
                canal.close();
                return Optional.empty();
            }
            return Optional.of(new TravaDeInstancia(arquivo, canal, lock));
        } catch (OverlappingFileLockException e) {
            canal.close(); // mesma JVM já segura (testes / dois AgenteDesktop) — conta como ocupado
            return Optional.empty();
        }
    }

    Path arquivo() {
        return arquivo;
    }

    @Override
    public void close() throws IOException {
        try {
            lock.release();
        } finally {
            canal.close();
        }
    }
}

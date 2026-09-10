package br.com.wagner.wagsyspet.agente.app.atualizacao;

import br.com.wagner.wagsyspet.agente.app.AplicadorAtualizacao;
import br.com.wagner.wagsyspet.agente.app.autostart.ComandoExterno;
import br.com.wagner.wagsyspet.agente.core.atualizacao.EstadoAtualizacao;
import br.com.wagner.wagsyspet.agente.core.atualizacao.PlanoAtualizacao;
import br.com.wagner.wagsyspet.agente.core.atualizacao.Reversor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Rename-swap de diretório — o único lugar onde ele é literal (plano F6 D1): macOS ({@code .app} vindo de um {@code .dmg}) e Linux
 * app-image por usuário ({@code .tar.gz}). Fluxo: materializar o novo em {@code staging} (tar -xzf / hdiutil attach + ditto),
 * validar (launcher presente; no macOS {@code xattr -rd com.apple.quarantine} defensivo + {@code codesign --verify --strict --deep}),
 * mover instalado → anterior e staging → instalado. {@link #reverter} desfaz. Nada é apagado antes da troca dar certo.
 */
public final class InstaladorTrocaDeDiretorio implements AplicadorAtualizacao.Instalador, Reversor {

    private static final Logger log = LoggerFactory.getLogger(InstaladorTrocaDeDiretorio.class);

    private final ComandoExterno cmd;
    private final Path instalado;
    private final Path pastaAnterior;
    private final Path staging;

    /** @param instalado raiz do app-image em uso ({@code …/AgroEase-Agente-Impressao} ou {@code …/X.app}) */
    public InstaladorTrocaDeDiretorio(ComandoExterno cmd, Path instalado, Path pastaAnterior, Path staging) {
        this.cmd = cmd;
        this.instalado = instalado;
        this.pastaAnterior = pastaAnterior;
        this.staging = staging;
    }

    @Override
    public Resultado aplicar(PlanoAtualizacao plano) {
        Path artefato = Path.of(plano.artefato());
        String nome = artefato.getFileName().toString();
        try {
            apagarArvore(staging);
            Files.createDirectories(staging);
            Path novo = nome.endsWith(".dmg") ? materializarDmg(artefato) : materializarTar(artefato);
            Path launcherNovo = Plataforma.launcherEm(novo, nome.endsWith(".dmg") ? "Mac OS X" : "Linux");
            if (!Files.isRegularFile(launcherNovo)) {
                throw new IOException("o pacote não contém o launcher esperado (" + instalado.relativize(instalado).resolve(launcherNovo.getFileName()) + ")");
            }
            tornarExecutavel(launcherNovo);
            Path anterior = pastaAnterior.resolve(instalado.getFileName());
            Files.createDirectories(pastaAnterior);
            apagarArvore(anterior);
            Files.move(instalado, anterior);
            try {
                Files.move(novo, instalado);
            } catch (IOException e) {
                Files.move(anterior, instalado); // devolve o antigo antes de reportar
                throw e;
            }
            apagarArvore(staging);
            log.info("Trocado: {} ← {} (anterior guardado em {})", instalado, novo.getFileName(), anterior);
            return new Resultado(true, "pasta trocada por rename (anterior em " + anterior + ")", Optional.of(Plataforma.launcherEm(instalado, nome.endsWith(".dmg") ? "Mac OS X" : "Linux")));
        } catch (IOException | RuntimeException e) {
            apagarArvore(staging);
            log.error("Troca de diretório falhou: {}", e.toString());
            return new Resultado(false, "troca de pasta falhou: " + e.getMessage(), Optional.empty());
        }
    }

    @Override
    public boolean reverter(EstadoAtualizacao.EmAplicacao ap) {
        Path anterior = pastaAnterior.resolve(instalado.getFileName());
        if (!Files.isDirectory(anterior)) {
            log.warn("Sem versão anterior guardada em {}; não há como reverter {}", anterior, ap.versaoNova());
            return false;
        }
        try {
            Path lixo = staging.resolveSibling("revertido-" + ap.versaoNova());
            apagarArvore(lixo);
            if (Files.exists(instalado)) {
                Files.move(instalado, lixo);
            }
            Files.move(anterior, instalado);
            apagarArvore(lixo);
            log.warn("Revertido para {} (pasta {})", ap.versaoAnterior(), instalado);
            return true;
        } catch (IOException e) {
            log.error("Reversão falhou: {}", e.toString());
            return false;
        }
    }

    private Path materializarTar(Path tar) throws IOException {
        ComandoExterno.Saida s = cmd.executar(List.of("tar", "-xzf", tar.toString(), "-C", staging.toString()));
        if (s.exit() != 0) {
            throw new IOException("tar saiu com " + s.exit() + ": " + s.texto());
        }
        Path dentro = staging.resolve(Plataforma.NOME);
        if (Files.isDirectory(dentro)) {
            return dentro;
        }
        try (Stream<Path> f = Files.list(staging)) { // tolera um único diretório com outro nome
            List<Path> dirs = f.filter(Files::isDirectory).toList();
            if (dirs.size() == 1) {
                return dirs.get(0);
            }
        }
        throw new IOException("tar.gz sem o diretório do app-image");
    }

    private Path materializarDmg(Path dmg) throws IOException {
        Path ponto = staging.resolve("dmg");
        Files.createDirectories(ponto);
        ComandoExterno.Saida s = cmd.executar(List.of("hdiutil", "attach", "-nobrowse", "-readonly", "-mountpoint", ponto.toString(), dmg.toString()));
        if (s.exit() != 0) {
            throw new IOException("hdiutil attach saiu com " + s.exit() + ": " + s.texto());
        }
        Path destino = staging.resolve(instalado.getFileName());
        try {
            ComandoExterno.Saida d = cmd.executar(List.of("ditto", ponto.resolve(instalado.getFileName()).toString(), destino.toString()));
            if (d.exit() != 0) {
                throw new IOException("ditto saiu com " + d.exit() + ": " + d.texto());
            }
        } finally {
            cmd.executar(List.of("hdiutil", "detach", ponto.toString()));
        }
        cmd.executar(List.of("xattr", "-rd", "com.apple.quarantine", destino.toString())); // defensivo; não falha se não houver
        ComandoExterno.Saida c = cmd.executar(List.of("codesign", "--verify", "--strict", "--deep", destino.toString()));
        if (c.exit() != 0) {
            throw new IOException("codesign recusou o .app novo: " + c.texto());
        }
        return destino;
    }

    private static void tornarExecutavel(Path p) {
        try {
            if (Files.getFileStore(p).supportsFileAttributeView("posix")) {
                Set<PosixFilePermission> perms = Files.getPosixFilePermissions(p);
                perms.add(PosixFilePermission.OWNER_EXECUTE);
                perms.add(PosixFilePermission.GROUP_EXECUTE);
                perms.add(PosixFilePermission.OTHERS_EXECUTE);
                Files.setPosixFilePermissions(p, perms);
            }
        } catch (IOException e) {
            log.debug("chmod +x {}: {}", p, e.toString());
        }
    }

    static void apagarArvore(Path raiz) {
        if (raiz == null || !Files.exists(raiz)) {
            return;
        }
        try {
            Files.walkFileTree(raiz, new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException { Files.delete(f); return FileVisitResult.CONTINUE; }
                @Override public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException { Files.delete(d); return FileVisitResult.CONTINUE; }
            });
        } catch (IOException e) {
            log.debug("não apaguei {}: {}", raiz, e.toString());
        }
    }

    static void copiarArvore(Path origem, Path destino) throws IOException {
        Files.walkFileTree(origem, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) throws IOException {
                Files.createDirectories(destino.resolve(origem.relativize(d).toString()));
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                Files.copy(f, destino.resolve(origem.relativize(f).toString()), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}

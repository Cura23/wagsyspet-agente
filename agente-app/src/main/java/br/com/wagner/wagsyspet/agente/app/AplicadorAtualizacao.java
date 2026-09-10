package br.com.wagner.wagsyspet.agente.app;

import br.com.wagner.wagsyspet.agente.core.atualizacao.EstadoAtualizacao;
import br.com.wagner.wagsyspet.agente.core.atualizacao.GerenteAtualizacao;
import br.com.wagner.wagsyspet.agente.core.atualizacao.PlanoAtualizacao;
import br.com.wagner.wagsyspet.agente.core.pareamento.DiretoriosDoAgente;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * O ATUALIZADOR ({@code --aplicar-atualizacao <plano.json>}): processo separado que (1) espera o agente soltar o lock, (2) aplica o
 * instalador pelo {@link Instalador} do SO, (3) relança o agente pelo {@link Relancador}. Falha do instalador → a versão fica
 * recusada por 24 h e o launcher ANTERIOR é relançado. O {@code emAplicacao} do estado NÃO é limpo em caso de sucesso: quem confirma
 * é o agente novo, quando estiver escutando (sentinela de boot). Plano F6 D2/D4.
 */
public final class AplicadorAtualizacao {

    private static final Logger log = LoggerFactory.getLogger(AplicadorAtualizacao.class);
    static final Duration ESPERA_LOCK = Duration.ofSeconds(60);

    /** Aplica o instalador de uma plataforma (L2: MSI silencioso, troca do .app, tar per-user, dpkg assistido). */
    public interface Instalador {
        record Resultado(boolean ok, String detalhe, Optional<Path> launcherNovo) { }

        Resultado aplicar(PlanoAtualizacao plano);

        /** F6-L1: ainda sem estratégia por SO — falha honesta (a versão fica recusada e o agente atual volta). */
        static Instalador indisponivel() {
            return plano -> new Resultado(false, "instalação automática ainda não disponível neste sistema (F6-L2)", Optional.empty());
        }
    }

    /** Relança o agente depois da troca (L2: pelo supervisor de cada SO; aqui, processo direto). */
    public interface Relancador {
        void relancar(Path launcher) throws IOException;

        static Relancador processo() {
            return launcher -> {
                Process p = ProcessoFilho.novo(List.of(launcher.toString())).redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                log.info("Agente relançado (pid {}): {}", p.pid(), launcher);
            };
        }
    }

    private final PrintStream out;
    private final Instalador instalador;
    private final Relancador relancador;
    private final Duration esperaLock;

    AplicadorAtualizacao(PrintStream out, Instalador instalador, Relancador relancador, Duration esperaLock) {
        this.out = out;
        this.instalador = instalador;
        this.relancador = relancador;
        this.esperaLock = esperaLock;
    }

    /** Estratégia real por SO (F6-L2): o instalador é escolhido pelo plano na hora de aplicar; o relançamento passa pelo supervisor. */
    static AplicadorAtualizacao padrao(PrintStream out) {
        Instalador porPlano = plano -> br.com.wagner.wagsyspet.agente.app.atualizacao.Instaladores.paraEsteSo(plano).aplicar(plano);
        Relancador direto = Relancador.processo();
        Relancador supervisor = br.com.wagner.wagsyspet.agente.app.atualizacao.Relancadores.paraEsteSo(launcher -> {
            try {
                direto.relancar(launcher);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
        return new AplicadorAtualizacao(out, porPlano, supervisor, ESPERA_LOCK);
    }

    public int aplicar(Path arquivoPlano) {
        PlanoAtualizacao plano;
        try {
            plano = PlanoAtualizacao.ler(arquivoPlano);
        } catch (IOException e) {
            out.println("Plano de atualização ilegível: " + e.getMessage());
            return Main.SAIDA_FALHA;
        }
        DiretoriosDoAgente dirs = new DiretoriosDoAgente(Path.of(plano.dirDados()));
        EstadoAtualizacao estado = new EstadoAtualizacao(dirs.atualizacao().resolve(GerenteAtualizacao.ARQUIVO_ESTADO));
        try (PrintStream saida = comLogProprio(dirs.atualizacao().resolve("atualizador.log"))) {
            return aplicar(plano, arquivoPlano, dirs, estado, saida);
        }
    }

    /** Sob systemd-run/open o stdout vai para o journal/nada: o atualizador guarda o próprio rastro ao lado do estado. */
    private PrintStream comLogProprio(Path arquivoLog) {
        try {
            Files.createDirectories(arquivoLog.getParent());
            java.io.OutputStream f = Files.newOutputStream(arquivoLog, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            java.io.OutputStream ambos = new java.io.OutputStream() {
                @Override public void write(int b) throws IOException { out.write(b); f.write(b); }
                @Override public void write(byte[] b, int o, int l) throws IOException { out.write(b, o, l); f.write(b, o, l); }
                @Override public void flush() throws IOException { out.flush(); f.flush(); }
                @Override public void close() throws IOException { f.close(); }
            };
            PrintStream ps = new PrintStream(ambos, true, java.nio.charset.StandardCharsets.UTF_8);
            ps.println("=== " + java.time.Instant.now() + " atualizador iniciado");
            return ps;
        } catch (IOException e) {
            return new PrintStream(new java.io.OutputStream() {
                @Override public void write(int b) throws IOException { out.write(b); }
                @Override public void write(byte[] b, int o, int l) throws IOException { out.write(b, o, l); }
            }, true, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private int aplicar(PlanoAtualizacao plano, Path arquivoPlano, DiretoriosDoAgente dirs, EstadoAtualizacao estado, PrintStream out) {
        out.println("Atualizando " + plano.versaoAnterior() + " → " + plano.versaoNova() + " (" + plano.arquivo() + ")");

        if (!esperarAgenteSair(dirs)) {
            out.println("O agente não encerrou em " + esperaLock.toSeconds() + " s; atualização cancelada (ele segue na versão atual).");
            GerenteAtualizacao.esquecerAplicacao(estado, "agente não soltou o lock");
            apagar(arquivoPlano);
            return Main.SAIDA_FALHA;
        }
        final Instalador.Resultado r = aplicarComSeguranca(plano);
        apagar(arquivoPlano);
        if (r.ok()) {
            out.println("Instalado: " + r.detalhe());
            Path launcher = r.launcherNovo().or(() -> plano.launcherAtual().map(Path::of)).orElse(null);
            relancar(launcher, "novo", out);
            return 0;
        }
        out.println("Falha ao instalar: " + r.detalhe());
        estado.ler().emAplicacao().ifPresentOrElse(
                ap -> GerenteAtualizacao.registrarRecusa(estado, ap, Clock.systemUTC(), r.detalhe()),
                () -> log.warn("estado sem emAplicacao ao falhar; nada a recusar"));
        relancar(plano.launcherAtual().map(Path::of).orElse(null), "anterior", out);
        return Main.SAIDA_FALHA;
    }

    private Instalador.Resultado aplicarComSeguranca(PlanoAtualizacao plano) {
        try {
            return instalador.aplicar(plano);
        } catch (RuntimeException e) {
            return new Instalador.Resultado(false, "exceção no instalador: " + e, Optional.empty());
        }
    }

    private boolean esperarAgenteSair(DiretoriosDoAgente dirs) {
        long limite = System.nanoTime() + esperaLock.toNanos();
        while (System.nanoTime() < limite) {
            try {
                Optional<TravaDeInstancia> t = TravaDeInstancia.tentar(dirs.lock());
                if (t.isPresent()) {
                    t.get().close(); // só queríamos saber que está livre; o agente novo vai tomar o lock
                    return true;
                }
                Thread.sleep(200);
            } catch (IOException e) {
                log.warn("lock ilegível ({}); seguindo", e.toString());
                return true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private void relancar(Path launcher, String qual, PrintStream out) {
        if (launcher == null) {
            out.println("Sem launcher conhecido para relançar o agente " + qual + "; abra-o manualmente.");
            return;
        }
        try {
            relancador.relancar(launcher);
            out.println("Agente " + qual + " relançado: " + launcher);
        } catch (IOException e) {
            out.println("Não consegui relançar o agente (" + launcher + "): " + e.getMessage());
        }
    }

    private static void apagar(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            log.debug("não apaguei {}: {}", p, e.toString());
        }
    }
}

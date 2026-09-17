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
    private final java.util.function.Consumer<PlanoAtualizacao> antesDeInstalar;
    private final java.util.function.Consumer<Optional<PlanoAtualizacao>> desfazerPausa;
    private final Duration esperaLock;

    AplicadorAtualizacao(PrintStream out, Instalador instalador, Relancador relancador, Duration esperaLock) {
        this(out, instalador, relancador, esperaLock, plano -> { });
    }

    /**
     * @param antesDeInstalar pausa o supervisor que relança sozinho (Windows: tarefa keepalive) — o agente já pausa ao sair, mas o
     *                        {@code --atualizar} do CLI não tem agente para isso. Quem reabilita é o {@link Relancador}.
     */
    AplicadorAtualizacao(PrintStream out, Instalador instalador, Relancador relancador, Duration esperaLock, java.util.function.Consumer<PlanoAtualizacao> antesDeInstalar) {
        this(out, instalador, relancador, esperaLock, antesDeInstalar, plano -> { });
    }

    /**
     * @param desfazerPausa o agente pausa o supervisor ANTES de sair para atualizar; quando o atualizador desiste sem instalar (plano
     *                      ilegível → vazio; agente não soltou a trava → o plano) a pausa tem de ser desfeita, senão a loja fica sem
     *                      supervisor até o próximo login. NÃO relança: com o agente vivo, relançar mata a instância no macOS
     *                      ({@code kickstart -k}) e abre o diálogo de 2ª instância no Linux sem unit.
     */
    AplicadorAtualizacao(PrintStream out, Instalador instalador, Relancador relancador, Duration esperaLock,
                         java.util.function.Consumer<PlanoAtualizacao> antesDeInstalar, java.util.function.Consumer<Optional<PlanoAtualizacao>> desfazerPausa) {
        this.out = out;
        this.instalador = instalador;
        this.relancador = relancador;
        this.esperaLock = esperaLock;
        this.antesDeInstalar = antesDeInstalar;
        this.desfazerPausa = desfazerPausa;
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
        return new AplicadorAtualizacao(out, porPlano, supervisor, ESPERA_LOCK, plano -> {
            try {
                br.com.wagner.wagsyspet.agente.app.autostart.Autostart.paraEsteSo(Path.of(plano.dirDados())).pausar(plano.launcherAtual().map(Path::of));
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }, plano -> {
            try {
                Path dados = plano.map(p -> Path.of(p.dirDados())).orElseGet(() -> DiretoriosDoAgente.padrao().raiz());
                br.com.wagner.wagsyspet.agente.app.autostart.Autostart.paraEsteSo(dados).retomar(plano.flatMap(PlanoAtualizacao::launcherAtual).map(Path::of));
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
    }

    public int aplicar(Path arquivoPlano) {
        PlanoAtualizacao plano;
        try {
            plano = PlanoAtualizacao.ler(arquivoPlano);
        } catch (IOException e) {
            out.println("Plano de atualização ilegível: " + e.getMessage());
            desfazerPausa(Optional.empty(), out);
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

        // Fecho F6: a trava de instância fica COM o atualizador até a hora de relançar. Qualquer agente aberto no meio da
        // instalação (atalho, keepalive, Run do logon) cai no caminho de 2ª instância e sai sem tocar em nada — antes subia o
        // binário VELHO, reabilitava a tarefa de 1 min e prendia os arquivos que o instalador ia trocar. E se este processo
        // morrer, a trava morre junto: o keepalive traz o agente de volta.
        Optional<Optional<TravaDeInstancia>> trava = esperarAgenteSair(dirs);
        if (trava.isEmpty()) {
            out.println("O agente não encerrou em " + esperaLock.toSeconds() + " s; atualização cancelada (ele segue na versão atual).");
            GerenteAtualizacao.esquecerAplicacao(estado, "agente não soltou o lock");
            apagar(arquivoPlano);
            desfazerPausa(Optional.of(plano), out); // supervisor de novo ligado: traz o agente de volta quando (e se) ele morrer
            return Main.SAIDA_FALHA;
        }
        try {
            antesDeInstalar.accept(plano);
        } catch (RuntimeException e) {
            out.println("Aviso: não consegui pausar o supervisor antes de instalar (" + e.getMessage() + "); seguindo.");
        }
        // plano INVERTIDO = reversão entregue pela sentinela (Windows): nova do plano = anterior da troca pendente, e vice-versa
        final Optional<EstadoAtualizacao.EmAplicacao> revertendo = estado.ler().emAplicacao()
                .filter(ap -> ap.versaoNova().equals(plano.versaoAnterior()) && plano.versaoNova().equals(ap.versaoAnterior()));
        final Instalador.Resultado r;
        try {
            r = artefatoConfere(plano) ? aplicarComSeguranca(plano)
                    : new Instalador.Resultado(false, "o instalador em " + plano.artefato() + " não confere mais com o sha256 do plano (foi trocado depois da "
                    + "verificação?) — NÃO executado", Optional.empty());
        } finally {
            if (trava.get().isPresent()) { // solta só agora: o agente que vai ser relançado precisa dela
                try {
                    trava.get().get().close();
                } catch (IOException e) {
                    log.warn("não consegui soltar a trava de instância ({}); o agente relançado pode demorar a subir", e.toString());
                }
            }
        }
        apagar(arquivoPlano);
        if (revertendo.isPresent()) {
            // quem fecha o estado da reversão é quem instalou, com o resultado REAL (adversarial L3 r2)
            if (r.ok()) {
                out.println("Instalado: " + r.detalhe() + " (reversão para " + plano.versaoNova() + ")");
                GerenteAtualizacao.registrarRecusa(estado, revertendo.get(), Clock.systemUTC(), "revertida para " + plano.versaoNova());
                GerenteAtualizacao.esquecerAplicacao(estado, "reversão concluída");
                relancar(r.launcherNovo().or(() -> plano.launcherAtual().map(Path::of)).orElse(null), "novo", out);
                return 0;
            }
            out.println("Falha ao instalar: " + r.detalhe() + " (a reversão para " + plano.versaoNova() + " NÃO aconteceu; segue a " + plano.versaoAnterior() + ")");
            GerenteAtualizacao.esquecerAplicacao(estado, "reversão para " + plano.versaoNova() + " falhou: " + r.detalhe());
            relancar(plano.launcherAtual().map(Path::of).orElse(null), "anterior", out);
            return Main.SAIDA_FALHA;
        }
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

    private void desfazerPausa(Optional<PlanoAtualizacao> plano, PrintStream out) {
        try {
            desfazerPausa.accept(plano);
        } catch (RuntimeException e) {
            out.println("Aviso: não consegui retomar o supervisor: " + e.getMessage());
        }
    }

    private Instalador.Resultado aplicarComSeguranca(PlanoAtualizacao plano) {
        try {
            return instalador.aplicar(plano);
        } catch (RuntimeException e) {
            return new Instalador.Resultado(false, "exceção no instalador: " + e, Optional.empty());
        }
    }

    /**
     * O último sha256 era conferido DENTRO do agente, antes de sair. Depois vêm a saída, a espera da trava (até 60 s), a pausa do
     * supervisor, as novas tentativas do msiexec e — no .deb — o tempo de a pessoa digitar a senha: o arquivo fica numa pasta
     * gravável pelo próprio usuário. No .deb a execução atravessa para ROOT ({@code pkexec dpkg -i}); é a única fronteira de
     * privilégio do self-update e não pode ser cruzada sem hash. Plano sem sha (formato antigo) segue como antes.
     */
    private static boolean artefatoConfere(PlanoAtualizacao plano) {
        String sha = plano.sha256();
        return sha == null || sha.isBlank() || GerenteAtualizacao.shaConfere(Path.of(plano.artefato()), sha);
    }

    /**
     * Vazio = o agente não saiu no prazo. Presente = pode instalar; por dentro vem a trava TOMADA (que o chamador segura até
     * relançar) ou vazio quando o arquivo de trava é ilegível (segue sem ela, como sempre foi).
     */
    private Optional<Optional<TravaDeInstancia>> esperarAgenteSair(DiretoriosDoAgente dirs) {
        long limite = System.nanoTime() + esperaLock.toNanos();
        while (System.nanoTime() < limite) {
            try {
                Optional<TravaDeInstancia> t = TravaDeInstancia.tentar(dirs.lock());
                if (t.isPresent()) {
                    return Optional.of(t);
                }
                Thread.sleep(200);
            } catch (IOException e) {
                log.warn("lock ilegível ({}); seguindo", e.toString());
                return Optional.of(Optional.empty());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
        return Optional.empty();
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

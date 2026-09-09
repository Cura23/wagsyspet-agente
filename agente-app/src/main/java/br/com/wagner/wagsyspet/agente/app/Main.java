package br.com.wagner.wagsyspet.agente.app;

import br.com.wagner.wagsyspet.agente.app.ui.Bandeja;
import br.com.wagner.wagsyspet.agente.app.ui.JanelaStatus;
import br.com.wagner.wagsyspet.agente.app.ui.Superficie;
import br.com.wagner.wagsyspet.agente.core.AgenteMain;
import br.com.wagner.wagsyspet.agente.core.InfoAgente;
import br.com.wagner.wagsyspet.agente.core.PortaImpressao;
import br.com.wagner.wagsyspet.agente.core.SubidaComFallback;
import br.com.wagner.wagsyspet.agente.core.VersaoDoBinario;
import br.com.wagner.wagsyspet.agente.core.pareamento.DiretoriosDoAgente;
import br.com.wagner.wagsyspet.agente.impressao.Impressora;
import br.com.wagner.wagsyspet.agente.impressao.ImpressoraJavaxPrint;
import br.com.wagner.wagsyspet.agente.protocolo.ProtocoloVersao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.GraphicsEnvironment;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import javax.swing.JOptionPane;

/**
 * Ponto de entrada do binário instalado na loja — <b>Agente de Impressão AgroEase</b> (plano F3 L4). Linha de comando
 * em {@link Argumentos#USO}. Sem comando: sobe o agente como programa de desktop ({@link AgenteDesktop}) com bandeja
 * ({@link Bandeja}) ou janela ({@link JanelaStatus}); sem tela, só se já estiver pareado.
 *
 * <p>Códigos de saída: 0 ok / "não reinicie"; 2 falha de comando; 3 servidor morreu depois de subir (supervisor
 * reinicia); 4 nenhuma porta livre. Log: {@code <dados>/logs/agente-0.log} ({@link LogDoAgente}).</p>
 */
public final class Main {

    public static final String NOME_PRODUTO = "Agente de Impressão AgroEase";
    static final int SAIDA_FALHA = 2;
    private static final Logger log = LoggerFactory.getLogger(Main.class);

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        System.exit(executar(args, System.out, System.err));
    }

    /** Toda a lógica testável: devolve o código de saída (o {@code main} só faz {@code System.exit}). */
    static int executar(String[] args, PrintStream out, PrintStream err) throws Exception {
        Argumentos a = Argumentos.parse(args);
        if (!a.erros().isEmpty()) {
            a.erros().forEach(e -> err.println("Erro: " + e));
            err.println(Argumentos.USO);
            return SAIDA_FALHA;
        }
        if (a.comando() == Argumentos.Comando.DEV) {
            // host de DESENVOLVIMENTO: sem pareamento, Origins/porta por -D, todo auth recusado (NAO_PAREADO); log no console
            LogDoAgente.soConsole(true);
            AgenteMain.main(a.posicionais().toArray(String[]::new),
                    new InfoAgente(versao(), ProtocoloVersao.ATUAL, System.getProperty("agente.id", AgenteMain.AGENTE_ID_DEV)));
            return 0;
        }
        DiretoriosDoAgente dirs = a.opcao(Argumentos.OPT_DIR) != null
                ? new DiretoriosDoAgente(Path.of(a.opcao(Argumentos.OPT_DIR)).toAbsolutePath())
                : DiretoriosDoAgente.padrao();
        boolean verboso = a.flag(Argumentos.FLAG_VERBOSO);

        if (a.comando() == Argumentos.Comando.SERVIR) {
            return servir(a, dirs, out, verboso);
        }

        // Comandos de linha: nunca precisam de tela (CI sem display, suporte por SSH); log só no console e só avisos.
        // O diagnóstico é a exceção: ele RELATA se há tela/bandeja, então não pode forçar headless.
        if (a.comando() != Argumentos.Comando.DIAGNOSTICO) {
            System.setProperty("java.awt.headless", "true");
        }
        LogDoAgente.soConsole(verboso);
        ComandosPareamento pareamento = new ComandosPareamento(out, err, dirs, versao());
        switch (a.comando()) {
            case AJUDA -> {
                out.println(linhaVersao());
                out.println(Argumentos.USO);
                return 0;
            }
            case VERSAO -> {
                out.println(linhaVersao());
                return 0;
            }
            case DIAGNOSTICO -> {
                return Diagnostico.rodar(out, dirs, linhaVersao()) ? 0 : SAIDA_FALHA;
            }
            case STATUS -> {
                out.println(linhaVersao());
                return pareamento.status();
            }
            case PAREAR -> {
                return pareamento.parear(a.posicionais().get(0), a.opcao(Argumentos.OPT_BACKEND));
            }
            case DESPAREAR -> {
                return pareamento.desparear();
            }
            case GERAR_PDF_TESTE -> {
                Path destino = Path.of(a.posicionais().get(0));
                Files.write(destino, PdfTeste.cupom80mm(NOME_PRODUTO, linhaVersao()));
                out.println("PDF de teste gravado em " + destino.toAbsolutePath() + " (" + Files.size(destino) + " bytes)");
                return 0;
            }
            case IMPRIMIR_TESTE -> {
                String impressora = a.posicionais().get(0);
                byte[] pdf = Files.readAllBytes(Path.of(a.posicionais().get(1)));
                ImpressoraJavaxPrint.Resultado r = Impressora.padrao()
                        .imprimirPdf(pdf, impressora, ImpressoraJavaxPrint.ModoPapel.PAPEL_DO_DRIVER, NOME_PRODUTO + " — teste");
                out.println("Impressão de teste em '" + impressora + "': " + r.estado() + " — " + r.detalhe());
                return r.aceito() ? 0 : SAIDA_FALHA;
            }
            case INSTALAR -> {
                return ComandosAutostart.padrao(out, err).instalar();
            }
            case DESINSTALAR -> {
                return ComandosAutostart.padrao(out, err).desinstalar();
            }
            default -> throw new IllegalStateException("comando não tratado: " + a.comando());
        }
    }

    /** Sem comando: programa de desktop. Uma instância por usuário; bandeja → janela → só CLI (headless). */
    private static int servir(Argumentos a, DiretoriosDoAgente dirs, PrintStream out, boolean verboso) throws Exception {
        dirs.garantir();
        boolean console = System.console() != null || verboso;
        Path logAtivo = LogDoAgente.configurar(dirs.logs(), verboso, console);
        log.info("=== {} {} iniciando (pasta {}, log {}) ===", NOME_PRODUTO, versao(), dirs.raiz(), logAtivo);

        Optional<TravaDeInstancia> trava = TravaDeInstancia.tentar(dirs.lock());
        if (trava.isEmpty()) {
            String msg = NOME_PRODUTO + " já está em execução neste computador (procure o ícone na bandeja ou a janela do agente).";
            log.warn("Segunda instância; saindo com 0");
            out.println(msg);
            if (!GraphicsEnvironment.isHeadless() && !a.flag(Argumentos.FLAG_SEM_BANDEJA)) {
                JOptionPane.showMessageDialog(null, msg, NOME_PRODUTO, JOptionPane.INFORMATION_MESSAGE);
            }
            return 0;
        }
        try (TravaDeInstancia ignorada = trava.get()) {
            int[] portas = a.porta() != null ? new int[]{a.porta()} : SubidaComFallback.PORTAS_PADRAO;
            AgenteDesktop agente = new AgenteDesktop(dirs, versao(), portas, out, PortaImpressao.real());
            Superficie ui = montarUi(a, agente);
            if (ui != null) {
                agente.ui(ui);
                ligarQuitHandler(agente);
            }
            int codigo = agente.executar();
            log.info("=== encerrando com código {} ===", codigo);
            return codigo;
        }
    }

    /** Bandeja se o SO tiver; senão janela; {@code --sem-bandeja} ou headless = nenhuma (modo serviço). */
    private static Superficie montarUi(Argumentos a, AgenteDesktop agente) {
        if (a.flag(Argumentos.FLAG_SEM_BANDEJA) || GraphicsEnvironment.isHeadless()) {
            log.info("Sem interface gráfica ({}); só linha de comando", GraphicsEnvironment.isHeadless() ? "headless" : "--sem-bandeja");
            return null;
        }
        try {
            if (Bandeja.disponivel()) {
                return new Bandeja(agente);
            }
        } catch (Exception | Error e) {
            log.warn("Bandeja indisponível ({}); usando a janela de status", e.toString());
        }
        try {
            return new JanelaStatus(agente, versao());
        } catch (Exception | Error e) {
            log.warn("Janela indisponível ({}); seguindo sem interface", e.toString());
            return null;
        }
    }

    /**
     * macOS: ⌘Q / Dock → Sair viraria {@code System.exit(0)} da AWT e o launchd ({@code SuccessfulExit:false}) não reiniciaria — toda
     * saída passa pelo {@code sair()} do agente. Com {@code -Dapple.awt.UIElement=true} (jpackage) não há ícone no Dock, mas o
     * handler fica como cinto. Em Windows/Linux a ação não é suportada e nada acontece. Adversarial F3 L4-A3.
     */
    private static void ligarQuitHandler(AgenteDesktop agente) {
        try {
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop d = java.awt.Desktop.getDesktop();
                if (d.isSupported(java.awt.Desktop.Action.APP_QUIT_HANDLER)) {
                    d.setQuitHandler((e, r) -> {
                        agente.sair();
                        r.cancelQuit(); // o encerramento normal (código 0) vem do executar()
                    });
                }
            }
        } catch (RuntimeException e) {
            log.debug("QuitHandler indisponível: {}", e.toString());
        }
    }

    /**
     * Versão do binário = {@code Implementation-Version} do MANIFEST ({@code ${revision}} do Maven) ou {@code -Dagente.versao}
     * em dev. Sem fonte → {@link IllegalStateException} (build inválido) — nunca "dev", que o backend recusa com 426.
     */
    static String versao() {
        return VersaoDoBinario.doClasspath(Main.class);
    }

    static String linhaVersao() {
        return NOME_PRODUTO + " " + versao()
                + " · Java " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")"
                + " · " + System.getProperty("os.name") + " " + System.getProperty("os.arch");
    }
}

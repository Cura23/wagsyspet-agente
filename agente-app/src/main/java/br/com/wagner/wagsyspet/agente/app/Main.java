package br.com.wagner.wagsyspet.agente.app;

import br.com.wagner.wagsyspet.agente.core.AgenteMain;
import br.com.wagner.wagsyspet.agente.impressao.Impressora;
import br.com.wagner.wagsyspet.agente.impressao.ImpressoraJavaxPrint;
import br.com.wagner.wagsyspet.agente.protocolo.ticket.AssinadorTicket;
import br.com.wagner.wagsyspet.agente.protocolo.ticket.ChavesTicket;
import br.com.wagner.wagsyspet.agente.protocolo.ticket.TicketClaims;
import br.com.wagner.wagsyspet.agente.protocolo.ticket.VerificadorTicket;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Ponto de entrada do binário instalado na loja — <b>Agente de Impressão AgroEase</b>.
 *
 * <pre>
 *   (sem argumentos)                         sobe o servidor do agente (ws://127.0.0.1:28421)
 *   --versao                                 imprime versão/Java/SO e sai 0
 *   --diagnostico                            Ed25519 do runtime, javax.print, impressoras, heap → sai 0 se tudo OK, 2 se não
 *   --gerar-pdf-teste &lt;arquivo.pdf&gt;         gera um cupom 80 mm de teste (PDFBox) — insumo do smoke
 *   --imprimir-teste &lt;impressora&gt; &lt;pdf&gt;     imprime pelo mesmo motor do agente; sai 0 se ACEITO_SPOOLER, 2 se não
 * </pre>
 * Os comandos existem para o <b>smoke de empacotamento</b> (CI nos 3 SOs executa o binário gerado pelo jpackage) e
 * para suporte na loja ("rode o diagnóstico e me mande a saída"). Textos voltados ao usuário: marca AgroEase.
 */
public final class Main {

    public static final String NOME_PRODUTO = "Agente de Impressão AgroEase";
    private static final int SAIDA_FALHA = 2;

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        PrintStream out = System.out;
        if (args.length == 0 || args[0].startsWith("-D")) {
            AgenteMain.main(args);
            return;
        }
        // Comandos de linha: nunca precisam de tela (CI sem display, suporte por SSH). A bandeja (F3) NÃO passa aqui.
        System.setProperty("java.awt.headless", "true");
        switch (args[0]) {
            case "--versao" -> out.println(linhaVersao());
            case "--diagnostico" -> System.exit(diagnostico(out) ? 0 : SAIDA_FALHA);
            case "--gerar-pdf-teste" -> {
                exigir(args, 2, "--gerar-pdf-teste <arquivo.pdf>");
                Path destino = Path.of(args[1]);
                Files.write(destino, PdfTeste.cupom80mm(NOME_PRODUTO, linhaVersao()));
                out.println("PDF de teste gravado em " + destino.toAbsolutePath() + " (" + Files.size(destino) + " bytes)");
            }
            case "--imprimir-teste" -> {
                exigir(args, 3, "--imprimir-teste <impressora> <arquivo.pdf>");
                byte[] pdf = Files.readAllBytes(Path.of(args[2]));
                ImpressoraJavaxPrint.Resultado r = Impressora.padrao()
                        .imprimirPdf(pdf, args[1], ImpressoraJavaxPrint.ModoPapel.PAPEL_DO_DRIVER, NOME_PRODUTO + " — teste");
                out.println("Impressão de teste em '" + args[1] + "': " + r.estado() + " — " + r.detalhe());
                System.exit(r.aceito() ? 0 : SAIDA_FALHA);
            }
            default -> {
                System.err.println("Argumento desconhecido: " + args[0]);
                System.err.println("Uso: --versao | --diagnostico | --gerar-pdf-teste <pdf> | --imprimir-teste <impressora> <pdf>");
                System.exit(SAIDA_FALHA);
            }
        }
    }

    static String versao() {
        String v = Main.class.getPackage() == null ? null : Main.class.getPackage().getImplementationVersion();
        return v == null ? "dev" : v;
    }

    static String linhaVersao() {
        return NOME_PRODUTO + " " + versao()
                + " · Java " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")"
                + " · " + System.getProperty("os.name") + " " + System.getProperty("os.arch");
    }

    /** Cada linha "OK"/"FALHA"; devolve true só se tudo OK. É o que o CI executa DENTRO do binário do jpackage. */
    static boolean diagnostico(PrintStream out) {
        boolean ok = true;
        out.println(linhaVersao());
        out.println("Heap máximo: " + (Runtime.getRuntime().maxMemory() / (1024 * 1024)) + " MB");
        out.println("Módulos no runtime: " + ModuleLayer.boot().modules().size()
                + (ModuleLayer.boot().findModule("jdk.crypto.ec").isPresent() ? " (jdk.crypto.ec presente)" : " (jdk.crypto.ec AUSENTE)"));

        // Ed25519 de ponta a ponta com as classes reais do ticket (o jlink sem jdk.crypto.ec falha AQUI, não em produção)
        try {
            KeyPair kp = ChavesTicket.gerar();
            Instant agora = Instant.now();
            String ticket = new AssinadorTicket(kp.getPrivate()).assinar(TicketClaims.novo(1L, "diag", agora, Duration.ofMinutes(1)));
            new VerificadorTicket(kp.getPublic(), 1L, "diag", Clock.systemUTC(), Duration.ofMinutes(1)).verificar(ticket);
            out.println("OK    Ed25519 (assinar + verificar ticket)");
        } catch (Exception | Error e) {
            ok = false;
            out.println("FALHA Ed25519: " + e);
        }

        // javax.print (módulo java.desktop) + enumeração real das impressoras do SO
        try {
            Impressora impressora = Impressora.padrao();
            List<String> nomes = impressora.listarImpressoras();
            out.println("OK    javax.print: " + nomes.size() + " impressora(s) " + nomes
                    + " · padrão: " + impressora.impressoraPadrao().orElse("(nenhuma)"));
        } catch (Exception | Error e) {
            ok = false;
            out.println("FALHA javax.print: " + e);
        }

        out.println(ok ? "DIAGNÓSTICO OK" : "DIAGNÓSTICO COM FALHAS");
        return ok;
    }

    private static void exigir(String[] args, int n, String uso) {
        if (args.length < n) {
            System.err.println("Uso: " + uso);
            System.exit(SAIDA_FALHA);
        }
    }
}

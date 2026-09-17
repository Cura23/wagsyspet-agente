package br.com.wagner.wagsyspet.agente.impressao;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import static br.com.wagner.wagsyspet.agente.impressao.ImpressoraJavaxPrint.ModoPapel;
import static br.com.wagner.wagsyspet.agente.impressao.ImpressoraJavaxPrint.Resultado;

/**
 * Fachada de impressão do agente: <b>um</b> ponto de entrada, que enumera impressoras por nome com
 * {@code javax.print} (igual em todo SO) e roteia a <b>submissão</b> do PDF pelo sistema operacional:
 * <ul>
 *   <li><b>Windows</b> → {@link ImpressoraJavaxPrint} ({@code PrinterJob} + PDFBox; spooler Win32 nativo).</li>
 *   <li><b>Linux / macOS</b> → {@link ImpressoraCupsLp} (PDF nativo via {@code lp}); se {@code lp} não existir,
 *       cai no {@code PrinterJob} (que no Unix exige {@code /usr/bin/lpr}).</li>
 * </ul>
 * Motivo do roteamento (spike F0, 2026-09-07): o {@code PrinterJob} do OpenJDK no Unix hardcoda
 * {@code /usr/bin/lpr} e converte para PostScript; o {@code lp} recebe o PDF vetorial direto.
 */
public final class Impressora {

    /** Sistema operacional relevante para a estratégia de submissão. */
    public enum Sistema { WINDOWS, LINUX, MAC, OUTRO }

    /** Estratégia de submissão de um PDF ao spooler (Strategy — injetável para teste). */
    @FunctionalInterface
    public interface SubmissorPdf {
        Resultado imprimirPdf(byte[] pdf, String impressora, ModoPapel modo, String nomeJob);
    }

    /** F6-L5: bytes CRUS (gaveta/corte do catálogo {@code ComandosRaw}) — o driver não participa. */
    @FunctionalInterface
    public interface SubmissorRaw {
        Resultado enviarRaw(byte[] bytes, String impressora, String nomeJob);
    }

    private static final SubmissorRaw SEM_RAW = (bytes, impressora, job) ->
            new Resultado(Resultado.Estado.ERRO, impressora, "este motor de impressão não envia comandos diretos à impressora");

    private final Sistema sistema;
    private final SubmissorPdf windows;
    private final SubmissorPdf unix;
    private final SubmissorRaw rawWindows;
    private final SubmissorRaw rawUnix;

    public Impressora(Sistema sistema, SubmissorPdf windows, SubmissorPdf unix) {
        this(sistema, windows, unix, SEM_RAW, SEM_RAW);
    }

    public Impressora(Sistema sistema, SubmissorPdf windows, SubmissorPdf unix, SubmissorRaw rawWindows, SubmissorRaw rawUnix) {
        this.sistema = Objects.requireNonNull(sistema);
        this.windows = Objects.requireNonNull(windows);
        this.unix = Objects.requireNonNull(unix);
        this.rawWindows = Objects.requireNonNull(rawWindows);
        this.rawUnix = Objects.requireNonNull(rawUnix);
    }

    /** Fachada para o SO real, com os submissores reais. */
    public static Impressora padrao() {
        SubmissorPdf unix = ImpressoraCupsLp.disponivel()
                ? ImpressoraCupsLp::imprimirPdf
                : ImpressoraJavaxPrint::imprimirPdf; // fallback: exige lpr (cups-bsd)
        // Windows: o acompanhamento do job é armado ANTES do print() (F6-L4 — o spooler apaga o job ao imprimir)
        SubmissorPdf windows = (pdf, impressora, modo, nomeJob) -> ImpressoraJavaxPrint.imprimirPdf(pdf, impressora, modo, nomeJob,
                br.com.wagner.wagsyspet.agente.impressao.spooler.AcompanhamentoWindows::armar);
        // RAW: no Unix SÓ pelo lp (-o raw); o javax.print de lá hardcoda o lpr e não tem como pedir raw
        SubmissorRaw rawUnix = ImpressoraCupsLp.disponivel() ? ImpressoraCupsLp::enviarRaw
                : (bytes, impressora, job) -> new Resultado(Resultado.Estado.ERRO, impressora, "/usr/bin/lp ausente (instale cups-client)");
        return new Impressora(detectar(System.getProperty("os.name")), windows, unix, ImpressoraJavaxPrint::enviarRaw, rawUnix);
    }

    /** Mapeia {@code os.name} para {@link Sistema}. Público-estático para ser testável sem trocar de SO. */
    public static Sistema detectar(String osName) {
        String n = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (n.contains("win")) {
            return Sistema.WINDOWS;
        }
        if (n.contains("mac") || n.contains("darwin")) {
            return Sistema.MAC;
        }
        if (n.contains("linux")) {
            return Sistema.LINUX;
        }
        return Sistema.OUTRO;
    }

    public Sistema sistema() {
        return sistema;
    }

    /** Impressoras que o SO conhece, por nome (mesma API em todo SO). */
    public List<String> listarImpressoras() {
        return ImpressoraJavaxPrint.listarImpressoras();
    }

    public Optional<String> impressoraPadrao() {
        return ImpressoraJavaxPrint.impressoraPadrao();
    }

    /** Envia bytes crus do catálogo (gaveta/corte) como um job SEPARADO na mesma fila, pelo caminho certo para o SO. */
    public Resultado enviarRaw(byte[] bytes, String impressora, String nomeJob) {
        return ((sistema == Sistema.WINDOWS) ? rawWindows : rawUnix).enviarRaw(bytes, impressora, nomeJob);
    }

    /** Imprime o PDF na impressora indicada, sem diálogo, pelo caminho certo para o SO. */
    public Resultado imprimirPdf(byte[] pdf, String impressora, ModoPapel modo, String nomeJob) {
        SubmissorPdf alvo = (sistema == Sistema.WINDOWS) ? windows : unix;
        return alvo.imprimirPdf(pdf, impressora, modo, nomeJob);
    }
}

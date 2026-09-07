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

    private final Sistema sistema;
    private final SubmissorPdf windows;
    private final SubmissorPdf unix;

    public Impressora(Sistema sistema, SubmissorPdf windows, SubmissorPdf unix) {
        this.sistema = Objects.requireNonNull(sistema);
        this.windows = Objects.requireNonNull(windows);
        this.unix = Objects.requireNonNull(unix);
    }

    /** Fachada para o SO real, com os submissores reais. */
    public static Impressora padrao() {
        SubmissorPdf unix = ImpressoraCupsLp.disponivel()
                ? ImpressoraCupsLp::imprimirPdf
                : ImpressoraJavaxPrint::imprimirPdf; // fallback: exige lpr (cups-bsd)
        return new Impressora(detectar(System.getProperty("os.name")), ImpressoraJavaxPrint::imprimirPdf, unix);
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

    /** Imprime o PDF na impressora indicada, sem diálogo, pelo caminho certo para o SO. */
    public Resultado imprimirPdf(byte[] pdf, String impressora, ModoPapel modo, String nomeJob) {
        SubmissorPdf alvo = (sistema == Sistema.WINDOWS) ? windows : unix;
        return alvo.imprimirPdf(pdf, impressora, modo, nomeJob);
    }
}

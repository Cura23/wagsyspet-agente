package br.com.wagner.wagsyspet.agente.impressao;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static br.com.wagner.wagsyspet.agente.impressao.ImpressoraJavaxPrint.ModoPapel;
import static br.com.wagner.wagsyspet.agente.impressao.ImpressoraJavaxPrint.Resultado;

/**
 * Submissão de PDF ao CUPS (Linux/macOS) via {@code lp}: envia o <b>PDF nativo</b> (vetorial) direto ao
 * spooler, sem passar pelo {@code PrinterJob} do JDK — que no Unix hardcoda {@code /usr/bin/lpr}
 * ({@code cups-bsd}, ausente em muitas distros) e converte tudo para PostScript.
 *
 * <p>Provado no spike F0 (2026-09-07) contra a impressora virtual {@code PDF} do {@code cups-pdf}:
 * {@code lp -d PDF} aceita o PDF de 80mm, devolve {@code request id is PDF-N} e gera a saída; com
 * {@code -o media=Custom.80x<H>mm} a página sai em 80×H exatos; sem a opção, segue a mídia padrão
 * do driver ("papel do driver").</p>
 *
 * <p>Resultado honesto: request-id devolvido = <b>aceito pelo spooler</b>, não "papel saiu".</p>
 */
public final class ImpressoraCupsLp {

    private static final Path LP = Path.of("/usr/bin/lp");
    private static final Pattern REQUEST_ID = Pattern.compile("request id is (\\S+)");
    private static final float PT_POR_MM = 72f / 25.4f;
    private static final int LARGURA_BOBINA_MM = 80;

    private ImpressoraCupsLp() {
    }

    /** {@code lp} existe (cups-client). */
    public static boolean disponivel() {
        return Files.isExecutable(LP);
    }

    /**
     * Submete o PDF ao CUPS.
     *
     * @param modo {@link ModoPapel#PAPEL_DO_DRIVER} = sem opção de mídia (preset da impressora);
     *             {@link ModoPapel#PAPEL_DO_PDF} = {@code -o media=Custom.80x<H>mm} com H lido da 1ª página do PDF
     */
    public static Resultado imprimirPdf(byte[] pdf, String nomeImpressora, ModoPapel modo, String nomeJob) {
        if (!disponivel()) {
            return new Resultado(Resultado.Estado.ERRO, nomeImpressora, "/usr/bin/lp ausente (instale cups-client)");
        }
        Path tmp = null;
        try {
            tmp = Files.createTempFile("wagsyspet-cupom-", ".pdf");
            Files.write(tmp, pdf);

            List<String> cmd = new ArrayList<>(List.of(LP.toString(), "-d", nomeImpressora, "-t", nomeJob));
            if (modo == ModoPapel.PAPEL_DO_PDF) {
                cmd.add("-o");
                cmd.add("media=Custom." + LARGURA_BOBINA_MM + "x" + alturaMm(pdf) + "mm");
            }
            cmd.add(tmp.toString());

            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
            pb.environment().put("LC_ALL", "C"); // saída em inglês → parse estável de "request id is X-N"
            Process p = pb.start();
            String saida = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!p.waitFor(15, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return new Resultado(Resultado.Estado.ERRO, nomeImpressora, "lp não respondeu em 15s");
            }
            if (p.exitValue() != 0) {
                boolean naoExiste = saida.contains("does not exist") || saida.contains("unknown destination");
                return new Resultado(naoExiste ? Resultado.Estado.IMPRESSORA_INDISPONIVEL : Resultado.Estado.ERRO,
                        nomeImpressora, "lp saiu com " + p.exitValue() + ": " + saida);
            }
            Matcher m = REQUEST_ID.matcher(saida);
            String jobId = m.find() ? m.group(1) : "?";
            return new Resultado(Resultado.Estado.ACEITO_SPOOLER, nomeImpressora,
                    "Job '" + nomeJob + "' aceito pelo CUPS (request id " + jobId + ", modo " + modo + ")");
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new Resultado(Resultado.Estado.ERRO, nomeImpressora, "Falha ao executar lp: " + e.getMessage());
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    /* temp file — melhor esforço */
                }
            }
        }
    }

    /** Altura (mm, arredondada p/ cima) da 1ª página do PDF — o cupom de 80mm tem altura variável. */
    static int alturaMm(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDRectangle box = doc.getPage(0).getMediaBox();
            return (int) Math.ceil(box.getHeight() / PT_POR_MM);
        }
    }
}

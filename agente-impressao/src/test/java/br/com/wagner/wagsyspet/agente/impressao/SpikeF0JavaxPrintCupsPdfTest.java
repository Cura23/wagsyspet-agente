package br.com.wagner.wagsyspet.agente.impressao;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPIKE F0 — prova o CAMINHO REAL DE PRODUÇÃO do agente no Unix sem impressora física:
 * PDF nativo → {@code lp} → CUPS → impressora virtual {@code PDF} ({@code printer-driver-cups-pdf}) → arquivo
 * em {@code ~/PDF}. Enumeração via {@code javax.print}. Usa os PDFs de 80mm gerados pelo backend.
 *
 * <p>Depende do ambiente (CUPS com impressora "PDF" e {@code lp}); só roda com
 * {@code -Dspike.pdfs=<dir com itext-fiscal.pdf e itext-naofiscal.pdf>}. Não é teste de regressão.</p>
 *
 * <p>Achado registrado: o {@code PrinterJob} do OpenJDK no Unix exige {@code /usr/bin/lpr} (cups-bsd) —
 * ausente aqui — por isso a submissão no Unix é via {@link ImpressoraCupsLp} (PDF nativo, vetorial).</p>
 */
@EnabledIfSystemProperty(named = "spike.pdfs", matches = ".+")
class SpikeF0JavaxPrintCupsPdfTest {

    private static final String IMPRESSORA_VIRTUAL = "PDF";
    private static final Path SAIDA_CUPS_PDF = Paths.get(System.getProperty("user.home"), "PDF");
    private static final Duration ESPERA_SPOOLER = Duration.ofSeconds(20);

    private final Path pdfs = Paths.get(System.getProperty("spike.pdfs"));

    @Test
    @DisplayName("SPIKE: o JDK enxerga a impressora virtual PDF do CUPS pelo nome (javax.print)")
    void listaImpressorasDoCups() {
        var nomes = ImpressoraJavaxPrint.listarImpressoras();
        System.out.println("[SPIKE F0] impressoras vistas pelo javax.print: " + nomes
                + " | padrão: " + ImpressoraJavaxPrint.impressoraPadrao().orElse("(nenhuma)"));
        assertThat(nomes).contains(IMPRESSORA_VIRTUAL);
    }

    @Test
    @DisplayName("SPIKE: cupom FISCAL 80mm → lp (PDF nativo) → CUPS gera o arquivo — papel do PDF (Custom 80xH)")
    void imprimeFiscalNaVirtual() throws Exception {
        imprimirEEsperarArquivo("itext-fiscal.pdf", "wagsyspet-spike-fiscal", ImpressoraJavaxPrint.ModoPapel.PAPEL_DO_PDF);
    }

    @Test
    @DisplayName("SPIKE: cupom NÃO-FISCAL 80mm → lp → CUPS gera o arquivo — papel do PDF")
    void imprimeNaoFiscalNaVirtual() throws Exception {
        imprimirEEsperarArquivo("itext-naofiscal.pdf", "wagsyspet-spike-naofiscal", ImpressoraJavaxPrint.ModoPapel.PAPEL_DO_PDF);
    }

    @Test
    @DisplayName("SPIKE: modo 'papel do driver' (default do agente) — sem -o media, segue a mídia padrão da impressora")
    void imprimePapelDoDriver() throws Exception {
        imprimirEEsperarArquivo("itext-naofiscal.pdf", "wagsyspet-spike-driver", ImpressoraJavaxPrint.ModoPapel.PAPEL_DO_DRIVER);
    }

    @Test
    @DisplayName("SPIKE: impressora inexistente → IMPRESSORA_INDISPONIVEL (javax.print e lp)")
    void impressoraInexistente() throws Exception {
        byte[] pdf = Files.readAllBytes(pdfs.resolve("itext-naofiscal.pdf"));
        var viaJavax = ImpressoraJavaxPrint.imprimirPdf(pdf, "IMPRESSORA_QUE_NAO_EXISTE", ImpressoraJavaxPrint.ModoPapel.PAPEL_DO_DRIVER, "x");
        var viaLp = ImpressoraCupsLp.imprimirPdf(pdf, "IMPRESSORA_QUE_NAO_EXISTE", ImpressoraJavaxPrint.ModoPapel.PAPEL_DO_DRIVER, "x");
        System.out.println("[SPIKE F0] inexistente javax → " + viaJavax);
        System.out.println("[SPIKE F0] inexistente lp    → " + viaLp);
        assertThat(viaJavax.estado()).isEqualTo(ImpressoraJavaxPrint.Resultado.Estado.IMPRESSORA_INDISPONIVEL);
        assertThat(viaLp.estado()).isEqualTo(ImpressoraJavaxPrint.Resultado.Estado.IMPRESSORA_INDISPONIVEL);
    }

    @Test
    @DisplayName("SPIKE (achado): PrinterJob do JDK no Unix depende de /usr/bin/lpr — documenta o comportamento local")
    void printerJobDoJdkNoUnix() throws Exception {
        byte[] pdf = Files.readAllBytes(pdfs.resolve("itext-naofiscal.pdf"));
        var r = ImpressoraJavaxPrint.imprimirPdf(pdf, IMPRESSORA_VIRTUAL, ImpressoraJavaxPrint.ModoPapel.PAPEL_DO_PDF, "wagsyspet-spike-javax");
        boolean temLpr = Files.isExecutable(Path.of("/usr/bin/lpr"));
        System.out.println("[SPIKE F0] PrinterJob (javax.print) com lpr=" + temLpr + " → " + r);
        // Sem lpr o JDK falha (comportamento documentado); com lpr deve aceitar. Não é asserção de produto.
        assertThat(r.aceito()).isEqualTo(temLpr);
    }

    private void imprimirEEsperarArquivo(String arquivoPdf, String nomeJob, ImpressoraJavaxPrint.ModoPapel modo) throws Exception {
        byte[] pdf = Files.readAllBytes(pdfs.resolve(arquivoPdf));
        Instant antes = Instant.now().minusSeconds(1);

        var r = ImpressoraCupsLp.imprimirPdf(pdf, IMPRESSORA_VIRTUAL, modo, nomeJob);
        System.out.println("[SPIKE F0] " + nomeJob + " → " + r);
        assertThat(r.aceito()).as("CUPS deve aceitar: %s", r.detalhe()).isTrue();

        // cups-pdf grava ASSÍNCRONO em ~/PDF/<título-do-job>[sufixo].pdf — espera aparecer um arquivo novo com esse prefixo.
        Instant limite = Instant.now().plus(ESPERA_SPOOLER);
        Optional<Path> gerado = Optional.empty();
        while (Instant.now().isBefore(limite) && gerado.isEmpty()) {
            gerado = arquivoNovoComPrefixo(nomeJob, antes);
            if (gerado.isEmpty()) {
                Thread.sleep(250);
            }
        }
        assertThat(gerado).as("cups-pdf deve gerar ~/PDF/%s*.pdf", nomeJob).isPresent();
        assertThat(Files.size(gerado.get())).isGreaterThan(1000);
        System.out.println("[SPIKE F0] arquivo gerado pelo CUPS: " + gerado.get() + " (" + Files.size(gerado.get()) + " bytes)");
    }

    private static Optional<Path> arquivoNovoComPrefixo(String prefixo, Instant depoisDe) throws IOException {
        if (!Files.isDirectory(SAIDA_CUPS_PDF)) {
            return Optional.empty();
        }
        try (Stream<Path> s = Files.list(SAIDA_CUPS_PDF)) {
            return s.filter(p -> p.getFileName().toString().startsWith(prefixo))
                    .filter(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toInstant().isAfter(depoisDe);
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .findFirst();
        }
    }
}

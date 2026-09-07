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
import java.util.UUID;
import java.util.stream.Stream;

import static br.com.wagner.wagsyspet.agente.impressao.ImpressoraJavaxPrint.ModoPapel;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integração com IMPRESSORA VIRTUAL — o mesmo teste prova o caminho real de produção em cada SO:
 * <ul>
 *   <li><b>Linux</b>: {@code -Dimpressora.virtual=PDF -Dimpressao.saida=$HOME/PDF} ({@code printer-driver-cups-pdf};
 *       submissão via {@code lp}). A saída aparece como arquivo novo em {@code impressao.saida} (diretório).</li>
 *   <li><b>Windows</b>: {@code -Dimpressora.virtual="Microsoft Print to PDF" -Dimpressao.saida=C:\saida\cupom.pdf}
 *       com a impressora apontada para uma <i>porta de arquivo</i> (ver CI); submissão via {@code PrinterJob}.
 *       A saída é o próprio arquivo da porta.</li>
 * </ul>
 * Só roda quando {@code impressora.virtual} está definida (CI e máquinas preparadas). Não depende do backend:
 * gera o PDF de 80mm com PDFBox.
 */
@EnabledIfSystemProperty(named = "impressora.virtual", matches = ".+")
@DisplayName("Impressão em impressora virtual (caminho real de produção por SO)")
class ImpressaoVirtualEnvTest {

    private static final Duration ESPERA_SPOOLER = Duration.ofSeconds(40);

    private final String virtual = System.getProperty("impressora.virtual");
    private final Path saida = Paths.get(System.getProperty("impressao.saida",
            Paths.get(System.getProperty("user.home"), "PDF").toString()));
    private final Impressora impressora = Impressora.padrao();

    @Test
    @DisplayName("a impressora virtual aparece na enumeração por nome (javax.print)")
    void enumeraVirtual() {
        var nomes = impressora.listarImpressoras();
        System.out.println("[IMPRESSAO] SO=" + impressora.sistema() + " impressoras=" + nomes
                + " padrão=" + impressora.impressoraPadrao().orElse("(nenhuma)"));
        assertThat(nomes).contains(virtual);
    }

    @Test
    @DisplayName("cupom 80mm → aceito pelo spooler → arquivo gerado (modo papel do PDF)")
    void imprimePapelDoPdf() throws Exception {
        imprimirEVerificar(ModoPapel.PAPEL_DO_PDF);
    }

    @Test
    @DisplayName("cupom 80mm → aceito pelo spooler → arquivo gerado (modo papel do driver — default do agente)")
    void imprimePapelDoDriver() throws Exception {
        imprimirEVerificar(ModoPapel.PAPEL_DO_DRIVER);
    }

    @Test
    @DisplayName("impressora inexistente → IMPRESSORA_INDISPONIVEL, sem exceção")
    void inexistente() throws Exception {
        byte[] pdf = PdfDeTeste.cupom80mm(60, "teste");
        var r = impressora.imprimirPdf(pdf, "IMPRESSORA_QUE_NAO_EXISTE_" + UUID.randomUUID(), ModoPapel.PAPEL_DO_DRIVER, "x");
        System.out.println("[IMPRESSAO] inexistente → " + r);
        assertThat(r.estado()).isEqualTo(ImpressoraJavaxPrint.Resultado.Estado.IMPRESSORA_INDISPONIVEL);
    }

    private void imprimirEVerificar(ModoPapel modo) throws Exception {
        String job = "wagsyspet-ci-" + modo.name().toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 8);
        byte[] pdf = PdfDeTeste.cupom80mm(100,
                "WAGSYSPET - TESTE DE IMPRESSAO", "SO: " + impressora.sistema(), "Modo: " + modo, "Job: " + job,
                "--------------------------------", "Racao Premium 15kg   1  120,00", "TOTAL           R$ 120,00");
        Instant antes = Instant.now().minusSeconds(1);

        var r = impressora.imprimirPdf(pdf, virtual, modo, job);
        System.out.println("[IMPRESSAO] " + job + " → " + r);
        assertThat(r.aceito()).as("spooler deve aceitar: %s", r.detalhe()).isTrue();

        Optional<Path> gerado = esperarSaida(job, antes);
        assertThat(gerado).as("saída esperada em %s (job %s)", saida, job).isPresent();
        assertThat(Files.size(gerado.get())).isGreaterThan(500);
        System.out.println("[IMPRESSAO] saída: " + gerado.get() + " (" + Files.size(gerado.get()) + " bytes)");
    }

    /** Diretório (cups-pdf: arquivo novo com o prefixo do job) ou arquivo (porta de arquivo do Windows). */
    private Optional<Path> esperarSaida(String prefixoJob, Instant depoisDe) throws Exception {
        Instant limite = Instant.now().plus(ESPERA_SPOOLER);
        while (Instant.now().isBefore(limite)) {
            Optional<Path> achado = Files.isDirectory(saida)
                    ? novoNoDiretorio(prefixoJob, depoisDe)
                    : arquivoAtualizado(saida, depoisDe);
            if (achado.isPresent()) {
                return achado;
            }
            Thread.sleep(300);
        }
        return Optional.empty();
    }

    private static Optional<Path> novoNoDiretorio(String prefixo, Instant depoisDe) throws IOException {
        Path dir = Paths.get(System.getProperty("impressao.saida",
                Paths.get(System.getProperty("user.home"), "PDF").toString()));
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().startsWith(prefixo))
                    .filter(p -> modificadoDepois(p, depoisDe))
                    .findFirst();
        }
    }

    private static Optional<Path> arquivoAtualizado(Path arquivo, Instant depoisDe) {
        return Files.isRegularFile(arquivo) && modificadoDepois(arquivo, depoisDe) ? Optional.of(arquivo) : Optional.empty();
    }

    private static boolean modificadoDepois(Path p, Instant depoisDe) {
        try {
            return Files.getLastModifiedTime(p).toInstant().isAfter(depoisDe);
        } catch (IOException e) {
            return false;
        }
    }
}

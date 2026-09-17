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

    @Test
    @DisplayName("F6-L4: depois do aceite, o SPOOLER REAL do SO diz o estado final do job — IMPRESSO (cups-pdf: 'job-completed-successfully'; Windows: job visto na fila até PRINTED/sumir depois de imprimir)")
    void estadoFinalImpresso() throws Exception {
        String job = "AgroEase cupom ci-estado-" + UUID.randomUUID().toString().substring(0, 8);
        var r = impressora.imprimirPdf(PdfDeTeste.cupom80mm(80, "AGROEASE - ESTADO DO SPOOLER", "Job: " + job), virtual, ModoPapel.PAPEL_DO_DRIVER, job);
        assertThat(r.aceito()).as("spooler deve aceitar: %s", r.detalhe()).isTrue();
        assertThat(r.acompanhamento()).as("todo job aceito nasce com a alça de acompanhamento").isPresent();
        try (var acompanhamento = r.acompanhamento().get()) {
            br.com.wagner.wagsyspet.agente.impressao.spooler.EstadoSpooler e = acompanhamento.consultar();
            Instant limite = Instant.now().plus(ESPERA_SPOOLER);
            while (!e.encerrado() && Instant.now().isBefore(limite)) {
                Thread.sleep(300);
                e = acompanhamento.consultar();
            }
            System.out.println("[IMPRESSAO] estado final de " + job + " → " + e);
            assertThat(e.estado()).as(e.detalhe()).isEqualTo(br.com.wagner.wagsyspet.agente.impressao.spooler.EstadoSpooler.Estado.IMPRESSO);
            assertThat(e.encerrado()).isTrue();
        }
    }

    @Test
    @DisplayName("F6-L5 RAW no spooler REAL: o comando de gaveta do catálogo vai como job SEPARADO. Linux/macOS (lp -o raw): tem de ser ACEITO — e fica fixado o fato de que o spooler diz 'sucesso' mesmo para impressora que não é ESC/POS (por isso o opt-in + Testar). Windows (javax.print AUTOSENSE → pDatatype RAW): o resultado é REGISTRADO; nunca pode lançar")
    void rawNoSpoolerReal() {
        byte[] gaveta = br.com.wagner.wagsyspet.agente.impressao.raw.ComandosRaw.abrirGaveta(br.com.wagner.wagsyspet.agente.impressao.raw.ComandosRaw.Dialeto.ESCPOS, 2, 50);
        var r = impressora.enviarRaw(gaveta, virtual, "AgroEase gaveta ci-" + UUID.randomUUID().toString().substring(0, 8));
        System.out.println("[RAW] SO=" + impressora.sistema() + " → " + r);
        assertThat(r).isNotNull();
        if (impressora.sistema() != Impressora.Sistema.WINDOWS) {
            assertThat(r.aceito()).as("lp -o raw deve ser aceito pelo CUPS: %s", r.detalhe()).isTrue();
            assertThat(r.acompanhamento()).as("comando cru não é observado: o que importa é o PDF").isEmpty();
        }
        var inexistente = impressora.enviarRaw(gaveta, "IMPRESSORA_QUE_NAO_EXISTE_" + UUID.randomUUID(), "AgroEase gaveta x");
        assertThat(inexistente.aceito()).isFalse();
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

    /**
     * Diretório (cups-pdf: arquivo novo com o prefixo do job) ou arquivo (porta de arquivo do Windows).
     * O spooler cria o arquivo VAZIO e vai escrevendo (Windows: mtime nova com 0 bytes por alguns instantes — foi o
     * falso vermelho do CI 34127331719): só devolve quando o arquivo está não vazio e o tamanho ficou estável.
     */
    private Optional<Path> esperarSaida(String prefixoJob, Instant depoisDe) throws Exception {
        Instant limite = Instant.now().plus(ESPERA_SPOOLER);
        while (Instant.now().isBefore(limite)) {
            Optional<Path> achado = Files.isDirectory(saida)
                    ? novoNoDiretorio(prefixoJob, depoisDe)
                    : arquivoAtualizado(saida, depoisDe);
            if (achado.isPresent() && escritaConcluida(achado.get())) {
                return achado;
            }
            Thread.sleep(300);
        }
        return Optional.empty();
    }

    /** Não vazio e com o mesmo tamanho em duas leituras separadas por 500 ms. */
    private static boolean escritaConcluida(Path p) throws Exception {
        long t1 = Files.size(p);
        if (t1 <= 0) {
            return false;
        }
        Thread.sleep(500);
        return Files.exists(p) && Files.size(p) == t1;
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

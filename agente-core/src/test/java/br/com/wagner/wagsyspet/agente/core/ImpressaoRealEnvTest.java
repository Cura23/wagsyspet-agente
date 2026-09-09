package br.com.wagner.wagsyspet.agente.core;

import br.com.wagner.wagsyspet.agente.protocolo.ticket.AssinadorTicket;
import br.com.wagner.wagsyspet.agente.protocolo.ticket.ChavesTicket;
import br.com.wagner.wagsyspet.agente.protocolo.ticket.TicketClaims;
import br.com.wagner.wagsyspet.agente.protocolo.ticket.VerificadorTicket;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F3-L3 — o PROTOCOLO inteiro contra a impressora virtual do SO (o que o CI tem: {@code cups-pdf} no Linux, "Microsoft
 * Print to PDF" no Windows): cliente WebSocket real → hello → auth (ticket assinado) → listar_impressoras (a virtual
 * está na lista) → imprimir (PDF gerado aqui) → {@code imprimir_ok} → o arquivo aparece na saída do spooler.
 * Só roda com {@code -Dimpressora.virtual} (CI e máquinas preparadas); é o mesmo gate do módulo de impressão.
 */
@EnabledIfSystemProperty(named = "impressora.virtual", matches = ".+")
@DisplayName("Impressão REAL pelo protocolo (WS → fila → spooler do SO → arquivo)")
class ImpressaoRealEnvTest {

    private static final String ORIGIN = "https://app.agroease.com.br";
    private static final long LOJA = 1L;
    private static final String AGENTE_ID = "e2e-" + UUID.randomUUID();
    private static final Duration ESPERA_SPOOLER = Duration.ofSeconds(40);

    private final String virtual = System.getProperty("impressora.virtual");
    private final Path saida = Paths.get(System.getProperty("impressao.saida",
            Paths.get(System.getProperty("user.home"), "PDF").toString()));

    @Test
    @DisplayName("hello → auth → listar (virtual na lista) → imprimir → imprimir_ok → PDF na saída do spooler")
    void pontaAPonta() throws Exception {
        KeyPair chaves = ChavesTicket.gerar();
        VerificadorTicket verificador = new VerificadorTicket(chaves.getPublic(), LOJA, AGENTE_ID, Clock.systemUTC(), Duration.ofMinutes(5));
        ConfiguracaoLocalMemoria config = new ConfiguracaoLocalMemoria();
        ServidorAgente servidor = new ServidorAgente(0, Set.of(ORIGIN),
                new ServidorAgente.Dependencias(new InfoAgente("1.0.0-e2e", 1, AGENTE_ID), verificador, PortaImpressao.real(), config),
                new ServidorAgente.Prazos());
        servidor.iniciar(Duration.ofSeconds(10));
        try {
            ClienteTeste c = ClienteTeste.conectarAberto(servidor.getPort(), ORIGIN);
            c.send("{\"tipo\":\"hello\",\"versaoProtocolo\":1}");
            assertThat(c.proximaMensagem().get("tipo").asText()).isEqualTo("hello_ok");
            String ticket = new AssinadorTicket(chaves.getPrivate()).assinar(TicketClaims.novo(LOJA, AGENTE_ID, Instant.now(), Duration.ofMinutes(10)));
            c.send("{\"tipo\":\"auth\",\"ticket\":\"" + ticket + "\"}");
            assertThat(c.proximaMensagem().get("tipo").asText()).isEqualTo("auth_ok");

            c.send("{\"tipo\":\"listar_impressoras\",\"id\":\"l1\"}");
            JsonNode lista = c.proximaMensagem(15);
            assertThat(lista.get("tipo").asText()).isEqualTo("impressoras");
            assertThat(lista.get("nomes")).extracting(JsonNode::asText).contains(virtual);
            System.out.println("[E2E] impressoras=" + lista.get("nomes"));

            String id = "e2e" + UUID.randomUUID().toString().substring(0, 8);
            Instant antes = Instant.now().minusSeconds(1);
            String pdf = Base64.getEncoder().encodeToString(cupom80mm("AGROEASE - TESTE E2E", "job " + id, "SO " + System.getProperty("os.name")));
            c.send("{\"tipo\":\"imprimir\",\"id\":\"" + id + "\",\"formato\":\"pdf\",\"bytesBase64\":\"" + pdf + "\",\"impressora\":\"" + virtual.replace("\"", "\\\"") + "\"}");
            JsonNode r = c.proximaMensagem(20);
            System.out.println("[E2E] resposta=" + r);
            assertThat(r.get("tipo").asText()).as("resposta do agente: %s", r).isEqualTo("imprimir_ok");
            assertThat(r.get("id").asText()).isEqualTo(id);
            assertThat(r.get("estado").asText()).isEqualTo("ACEITO_SPOOLER");

            Optional<Path> gerado = esperarSaida(id, antes);
            assertThat(gerado).as("saída esperada em %s (job %s)", saida, id).isPresent();
            assertThat(Files.size(gerado.get())).isGreaterThan(300);
            System.out.println("[E2E] saída: " + gerado.get() + " (" + Files.size(gerado.get()) + " bytes)");
            c.close();
        } finally {
            servidor.stop(1000);
        }
    }

    /** Cupom mínimo de 80 mm com fonte padrão (não depende de fontes do SO). */
    static byte[] cupom80mm(String... linhas) throws IOException {
        float mm = 72f / 25.4f;
        try (PDDocument doc = new PDDocument()) {
            PDPage pagina = new PDPage(new PDRectangle(80 * mm, 60 * mm));
            doc.addPage(pagina);
            try (PDPageContentStream cs = new PDPageContentStream(doc, pagina)) {
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.COURIER), 9);
                float y = 55 * mm;
                for (String l : linhas) {
                    cs.beginText();
                    cs.newLineAtOffset(4 * mm, y);
                    cs.showText(l);
                    cs.endText();
                    y -= 5 * mm;
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    /** Diretório (cups-pdf: arquivo novo contendo o id do job no nome) ou arquivo (porta de arquivo do Windows), não vazio e estável. */
    private Optional<Path> esperarSaida(String idJob, Instant depoisDe) throws Exception {
        Instant limite = Instant.now().plus(ESPERA_SPOOLER);
        while (Instant.now().isBefore(limite)) {
            Optional<Path> achado = Files.isDirectory(saida) ? novoNoDiretorio(idJob, depoisDe) : arquivoAtualizado(saida, depoisDe);
            if (achado.isPresent() && escritaConcluida(achado.get())) {
                return achado;
            }
            Thread.sleep(300);
        }
        return Optional.empty();
    }

    private static boolean escritaConcluida(Path p) throws Exception {
        long t1 = Files.size(p);
        if (t1 <= 0) {
            return false;
        }
        Thread.sleep(500);
        return Files.exists(p) && Files.size(p) == t1;
    }

    private Optional<Path> novoNoDiretorio(String idJob, Instant depoisDe) throws IOException {
        try (Stream<Path> s = Files.list(saida)) {
            return s.filter(p -> p.getFileName().toString().contains(idJob)).filter(p -> modificadoDepois(p, depoisDe)).findFirst();
        }
    }

    private static Optional<Path> arquivoAtualizado(Path arquivo, Instant depoisDe) {
        return Files.exists(arquivo) && modificadoDepois(arquivo, depoisDe) ? Optional.of(arquivo) : Optional.empty();
    }

    private static boolean modificadoDepois(Path p, Instant t) {
        try {
            return Files.getLastModifiedTime(p).toInstant().isAfter(t);
        } catch (IOException e) {
            return false;
        }
    }
}

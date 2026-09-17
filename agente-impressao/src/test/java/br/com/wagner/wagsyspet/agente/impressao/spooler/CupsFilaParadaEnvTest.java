package br.com.wagner.wagsyspet.agente.impressao.spooler;

import br.com.wagner.wagsyspet.agente.impressao.ImpressoraCupsLp;
import br.com.wagner.wagsyspet.agente.impressao.ImpressoraJavaxPrint.ModoPapel;
import br.com.wagner.wagsyspet.agente.impressao.spooler.EstadoSpooler.Estado;
import br.com.wagner.wagsyspet.agente.impressao.spooler.EstadoSpooler.Motivo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CUPS REAL, casos negativos (plano F6 D9). Exige uma fila que ACEITA jobs mas está DESABILITADA, criada por quem roda o teste:
 * <pre>
 *   lpadmin -p AGROEASE_PARADA -E -v socket://127.0.0.1:9 -m raw && cupsdisable AGROEASE_PARADA
 *   ./mvnw -pl agente-impressao test -Dtest=CupsFilaParadaEnvTest -Dimpressora.parada=AGROEASE_PARADA
 *   lpadmin -x AGROEASE_PARADA
 * </pre>
 * É exatamente o cenário "cupsdisable por acidente / ErrorPolicy stop-printer depois de uma falha": o {@code lp} aceita e o cupom nunca sai.
 */
@EnabledIfSystemProperty(named = "impressora.parada", matches = ".+")
@DisplayName("CUPS real — fila parada → PENDENTE{FILA_PARADA}; job cancelado na fila → FALHOU{CANCELADO}")
class CupsFilaParadaEnvTest {

    private final String fila = System.getProperty("impressora.parada");

    @Test
    void filaParadaDepoisCancelado() throws Exception {
        byte[] pdf = ("%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj 2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj "
                + "3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 226 170]>>endobj\ntrailer<</Root 1 0 R>>\n%%EOF\n").getBytes(StandardCharsets.US_ASCII);
        var r = ImpressoraCupsLp.imprimirPdf(pdf, fila, ModoPapel.PAPEL_DO_DRIVER, "AgroEase cupom parada-" + UUID.randomUUID().toString().substring(0, 8));
        assertThat(r.aceito()).as("fila desabilitada AINDA aceita o job — é esse o buraco: %s", r.detalhe()).isTrue();
        AcompanhamentoCups a = (AcompanhamentoCups) r.acompanhamento().orElseThrow();

        EstadoSpooler parado = a.consultar();
        System.out.println("[CUPS] " + a.jobId() + " → " + parado);
        assertThat(parado.estado()).isEqualTo(Estado.PENDENTE);
        assertThat(parado.motivo()).isEqualTo(Motivo.FILA_PARADA);
        assertThat(parado.encerrado()).isFalse();

        ComandoSpooler.Saida cancel = ComandoSpooler.rodar(List.of("/usr/bin/cancel", a.jobId()), Duration.ofSeconds(10));
        assertThat(cancel.ok()).as(cancel.texto()).isTrue();
        EstadoSpooler cancelado = a.consultar();
        for (int i = 0; i < 20 && !cancelado.encerrado(); i++) {
            Thread.sleep(250);
            cancelado = a.consultar();
        }
        System.out.println("[CUPS] " + a.jobId() + " → " + cancelado);
        assertThat(cancelado.estado()).isEqualTo(Estado.FALHOU);
        assertThat(cancelado.motivo()).isEqualTo(Motivo.CANCELADO);
    }
}

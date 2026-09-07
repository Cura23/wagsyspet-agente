package br.com.wagner.wagsyspet.agente.impressao;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;

import static br.com.wagner.wagsyspet.agente.impressao.ImpressoraJavaxPrint.ModoPapel;
import static br.com.wagner.wagsyspet.agente.impressao.ImpressoraJavaxPrint.Resultado;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fachada {@link Impressora}: escolhe o caminho de submissão pelo SO — Windows usa o {@code PrinterJob} (spooler
 * nativo); Linux/macOS usam o {@code lp} do CUPS (o {@code PrinterJob} do OpenJDK no Unix exige {@code /usr/bin/lpr}).
 * Testes PUROS (sem impressora): a decisão de roteamento é o que se prova aqui.
 */
@DisplayName("Impressora — fachada por sistema operacional")
class ImpressoraTest {

    /** Submissor de mentira que só registra a chamada. */
    private static final class SubmissorFake implements Impressora.SubmissorPdf {
        final List<String> chamadas = new ArrayList<>();
        final String rotulo;

        SubmissorFake(String rotulo) {
            this.rotulo = rotulo;
        }

        @Override
        public Resultado imprimirPdf(byte[] pdf, String impressora, ModoPapel modo, String nomeJob) {
            chamadas.add(impressora + "/" + modo + "/" + nomeJob);
            return new Resultado(Resultado.Estado.ACEITO_SPOOLER, impressora, rotulo);
        }
    }

    @Nested
    @DisplayName("detecção do SO a partir de os.name")
    class Deteccao {
        @ParameterizedTest(name = "\"{0}\" → {1}")
        @CsvSource({
            "Windows 11, WINDOWS",
            "Windows 10, WINDOWS",
            "Windows Server 2022, WINDOWS",
            "Linux, LINUX",
            "Mac OS X, MAC",
            "FreeBSD, OUTRO",
        })
        void detecta(String osName, Impressora.Sistema esperado) {
            assertThat(Impressora.detectar(osName)).isEqualTo(esperado);
        }
    }

    @Nested
    @DisplayName("roteamento da submissão")
    class Roteamento {
        private final SubmissorFake windows = new SubmissorFake("via-printerjob");
        private final SubmissorFake unix = new SubmissorFake("via-lp");
        private final byte[] pdf = {0x25, 0x50, 0x44, 0x46};

        @Test
        @DisplayName("Windows → PrinterJob (spooler nativo), nunca o lp")
        void windowsUsaPrinterJob() {
            Impressora imp = new Impressora(Impressora.Sistema.WINDOWS, windows, unix);
            Resultado r = imp.imprimirPdf(pdf, "Elgin i9", ModoPapel.PAPEL_DO_DRIVER, "cupom-1");
            assertThat(r.detalhe()).isEqualTo("via-printerjob");
            assertThat(windows.chamadas).containsExactly("Elgin i9/PAPEL_DO_DRIVER/cupom-1");
            assertThat(unix.chamadas).isEmpty();
        }

        @Test
        @DisplayName("Linux → lp (PDF nativo ao CUPS), nunca o PrinterJob")
        void linuxUsaLp() {
            Impressora imp = new Impressora(Impressora.Sistema.LINUX, windows, unix);
            Resultado r = imp.imprimirPdf(pdf, "PDF", ModoPapel.PAPEL_DO_PDF, "cupom-2");
            assertThat(r.detalhe()).isEqualTo("via-lp");
            assertThat(unix.chamadas).containsExactly("PDF/PAPEL_DO_PDF/cupom-2");
            assertThat(windows.chamadas).isEmpty();
        }

        @Test
        @DisplayName("macOS → lp (CUPS), igual ao Linux")
        void macUsaLp() {
            Impressora imp = new Impressora(Impressora.Sistema.MAC, windows, unix);
            imp.imprimirPdf(pdf, "Epson", ModoPapel.PAPEL_DO_DRIVER, "cupom-3");
            assertThat(unix.chamadas).hasSize(1);
            assertThat(windows.chamadas).isEmpty();
        }

        @Test
        @DisplayName("SO desconhecido → tenta o caminho Unix (CUPS é o mais provável fora do Windows)")
        void outroUsaUnix() {
            Impressora imp = new Impressora(Impressora.Sistema.OUTRO, windows, unix);
            imp.imprimirPdf(pdf, "X", ModoPapel.PAPEL_DO_DRIVER, "cupom-4");
            assertThat(unix.chamadas).hasSize(1);
        }
    }

    @Test
    @DisplayName("padrao() constrói a fachada para o SO real com os submissores reais (não lança, lista impressoras)")
    void padraoConstroiSemLancar() {
        Impressora imp = Impressora.padrao();
        assertThat(imp.sistema()).isNotNull();
        assertThat(imp.listarImpressoras()).isNotNull(); // pode ser vazia numa máquina sem impressora
    }
}

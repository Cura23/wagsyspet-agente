package br.com.wagner.wagsyspet.agente.impressao;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.time.Duration;
import java.util.List;

import static br.com.wagner.wagsyspet.agente.impressao.ImpressoraJavaxPrint.ModoPapel;
import static br.com.wagner.wagsyspet.agente.impressao.ImpressoraJavaxPrint.Resultado.Estado;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * F3-L3 (plano D12) — o que o agente entende da saída do {@code lp} e como executa o processo: fila parada com
 * {@code cupsreject} é IMPRESSORA_INDISPONIVEL (não ERRO genérico), o prazo do {@code lp} vale de verdade (antes o
 * {@code readAllBytes} vinha ANTES do {@code waitFor} — um lp travado bloqueava para sempre) e nada com "wagsyspet"
 * aparece na fila que o lojista vê.
 */
@DisplayName("ImpressoraCupsLp — interpretação da saída do lp e execução com prazo")
class ImpressoraCupsLpTest {

    @Test
    @DisplayName("exit 0 + 'request id is PDF-12' → ACEITO_SPOOLER com o request id no detalhe")
    void aceito() {
        var r = ImpressoraCupsLp.interpretar(0, "request id is PDF-12 (1 file(s))", "PDF", "AgroEase cupom j1", ModoPapel.PAPEL_DO_DRIVER);
        assertThat(r.estado()).isEqualTo(Estado.ACEITO_SPOOLER);
        assertThat(r.detalhe()).contains("PDF-12").contains("AgroEase cupom j1");
    }

    @Test
    @DisplayName("'does not exist' / 'unknown destination' → IMPRESSORA_INDISPONIVEL")
    void naoExiste() {
        assertThat(ImpressoraCupsLp.interpretar(1, "lp: The printer or class does not exist.", "X", "j", ModoPapel.PAPEL_DO_DRIVER).estado())
                .isEqualTo(Estado.IMPRESSORA_INDISPONIVEL);
        assertThat(ImpressoraCupsLp.interpretar(1, "lp: Error - unknown destination \"X\"", "X", "j", ModoPapel.PAPEL_DO_DRIVER).estado())
                .isEqualTo(Estado.IMPRESSORA_INDISPONIVEL);
    }

    @Test
    @DisplayName("fila parada (cupsreject: 'is not accepting jobs') → IMPRESSORA_INDISPONIVEL — o operador vê a mensagem amigável de impressora")
    void naoAceitaJobs() {
        var r = ImpressoraCupsLp.interpretar(1, "lp: Destination \"EPSON\" is not accepting jobs.", "EPSON", "j", ModoPapel.PAPEL_DO_DRIVER);
        assertThat(r.estado()).isEqualTo(Estado.IMPRESSORA_INDISPONIVEL);
        assertThat(r.detalhe()).contains("not accepting");
    }

    @Test
    @DisplayName("outro exit ≠ 0 → ERRO com a saída do lp no detalhe (só no log; o operador não vê)")
    void erroGenerico() {
        var r = ImpressoraCupsLp.interpretar(2, "lp: Unable to print file: Bad request", "EPSON", "j", ModoPapel.PAPEL_DO_DRIVER);
        assertThat(r.estado()).isEqualTo(Estado.ERRO);
        assertThat(r.detalhe()).contains("Bad request");
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "executa /bin/sh; o caminho CUPS/lp não existe no Windows (lá o motor é o PrinterJob) — CI F3")
    @DisplayName("execução: processo que NÃO termina no prazo → ERRO 'não respondeu' (prazo real, sem bloquear no stdout)")
    void prazoDoProcesso() {
        // um 'lp' falso que segura o stdout aberto e não sai — antes do fix o readAllBytes travava para sempre
        var r = ImpressoraCupsLp.executar(List.of("/bin/sh", "-c", "sleep 30"), Duration.ofMillis(400), "X", "j", ModoPapel.PAPEL_DO_DRIVER);
        assertThat(r.estado()).isEqualTo(Estado.ERRO);
        assertThat(r.detalhe()).contains("não respondeu");
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "executa /bin/sh; o caminho CUPS/lp não existe no Windows (lá o motor é o PrinterJob) — CI F3")
    @DisplayName("execução: processo que responde → interpretado normalmente (saída lida depois do término)")
    void execucaoFeliz() {
        var r = ImpressoraCupsLp.executar(List.of("/bin/sh", "-c", "echo 'request id is X-7 (1 file(s))'"), Duration.ofSeconds(5), "X", "j", ModoPapel.PAPEL_DO_DRIVER);
        assertThat(r.estado()).isEqualTo(Estado.ACEITO_SPOOLER);
        assertThat(r.detalhe()).contains("X-7");
    }

    @Test
    @DisplayName("comando do lp leva -o nopdfAutoRotate: sem isso o pdftopdf gira 90° um cupom mais largo que alto (texto deitado — teste manual 10/09)")
    void naoGiraCupomPaisagem() {
        List<String> cmd = ImpressoraCupsLp.comandoLp("PDF", "AgroEase cupom x");
        assertThat(cmd).startsWith("lp".equals(cmd.get(0)) ? "lp" : cmd.get(0), "-d", "PDF", "-t", "AgroEase cupom x");
        assertThat(cmd).containsSequence("-o", "nopdfAutoRotate");
    }

    @Test
    @DisplayName("prefixo do arquivo temporário e nome do job nunca expõem 'wagsyspet' ao lojista")
    void marca() {
        assertThat(ImpressoraCupsLp.PREFIXO_TEMP).containsIgnoringCase("agroease").doesNotContainIgnoringCase("wagsyspet");
    }
}

package br.com.wagner.wagsyspet.agente.impressao.raw;

import br.com.wagner.wagsyspet.agente.impressao.ImpressoraJavaxPrint;
import br.com.wagner.wagsyspet.agente.impressao.spooler.ComandoSpooler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Adversarial L5: o JDK manda o comando cru SEMPRE como pDatatype="RAW" e aceita o flavor para QUALQUER fila; driver v4/XPSDrv DESCARTA
 * RAW em silêncio (Microsoft KB 2779300: "produces a 0-byte spool file") — a gaveta nunca abriria e o agente diria "sucesso" a cada
 * venda. Driver v4 fica registrado em ...\\Print\\Environments\\<arquitetura>\\Drivers\\Version-4\\<nome do driver>.
 */
@DisplayName("DriverWindows — driver de impressão v4/XPS não aceita comando direto: erro HONESTO em vez de 'sucesso' falso")
class DriverWindowsTest {

    @Test
    @DisplayName("driver sob Version-4 (em qualquer arquitetura) → v4; só sob Version-3 → não; driver desconhecido / reg falhando → não (não bloquear por palpite)")
    void detectaV4PeloRegistro() {
        List<List<String>> chamadas = new ArrayList<>();
        assertThat(DriverWindows.v4("TERMICA", nome -> Optional.of("Microsoft Print To PDF"), cmd -> {
            chamadas.add(cmd);
            return new ComandoSpooler.Saida(cmd.get(2).contains("Windows x64") ? 0 : 1, "", false);
        })).isTrue();
        assertThat(chamadas.get(0)).containsExactly("reg", "query",
                "HKLM\\SYSTEM\\CurrentControlSet\\Control\\Print\\Environments\\Windows x64\\Drivers\\Version-4\\Microsoft Print To PDF");
        assertThat(DriverWindows.v4("EPSON", nome -> Optional.of("EPSON TM-T20 Receipt"), cmd -> new ComandoSpooler.Saida(1, "ERROR: not found", false))).isFalse();
        assertThat(DriverWindows.v4("X", nome -> Optional.empty(), cmd -> { throw new AssertionError("sem nome de driver não consulta"); })).isFalse();
        assertThat(DriverWindows.v4("X", nome -> Optional.of("D"), cmd -> new ComandoSpooler.Saida(-1, "", true))).isFalse();
    }

    @Test
    @DisplayName("enviarRaw com driver v4 → ERRO com orientação (usar a opção de gaveta/corte do PRÓPRIO driver), sem tocar o spooler — vira aviso GAVETA_FALHOU / erro no 'Testar', nunca 'sucesso'")
    void rawEmDriverV4EhErroHonesto() {
        var r = ImpressoraJavaxPrint.enviarRaw(new byte[]{0x1B, 0x70, 0x00, 0x19, (byte) 0xFA}, "Microsoft Print to PDF", "AgroEase gaveta x", impressora -> true);
        assertThat(r.aceito()).isFalse();
        assertThat(r.estado()).isEqualTo(ImpressoraJavaxPrint.Resultado.Estado.ERRO);
        assertThat(r.detalhe()).containsIgnoringCase("driver").containsIgnoringCase("v4");
    }
}

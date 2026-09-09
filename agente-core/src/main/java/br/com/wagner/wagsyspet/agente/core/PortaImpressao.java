package br.com.wagner.wagsyspet.agente.core;

import br.com.wagner.wagsyspet.agente.impressao.Impressora;
import br.com.wagner.wagsyspet.agente.impressao.ImpressoraJavaxPrint.ModoPapel;
import br.com.wagner.wagsyspet.agente.impressao.ImpressoraJavaxPrint.Resultado;

import java.util.List;
import java.util.Optional;

/**
 * Porta do servidor para o motor de impressão (plano F3 D8). O protocolo fala com esta interface; a implementação real
 * delega à fachada {@link Impressora} (javax.print / CUPS) e os testes do contrato usam um fake — assim o protocolo é
 * provado sem impressora e sem tocar o spooler.
 */
public interface PortaImpressao {

    /** Nomes das impressoras instaladas neste computador (lista fresca a cada chamada). */
    List<String> listar();

    /** Impressora padrão do SO, se houver (só sugestão — o agente NÃO auto-seleciona). */
    Optional<String> padrao();

    /**
     * Envia o PDF ao spooler da impressora. Bloqueante (o servidor chama pela {@link FilaImpressao}, nunca na thread da
     * conexão). Nunca lança: falhas voltam em {@link Resultado#estado()}.
     */
    Resultado imprimir(byte[] pdf, String impressora, String nomeJob);

    /** Implementação real sobre a fachada por SO (Windows → PrinterJob; Linux/macOS → lp), papel do driver por padrão. */
    static PortaImpressao real() {
        Impressora impressora = Impressora.padrao();
        return new PortaImpressao() {
            @Override
            public List<String> listar() {
                return impressora.listarImpressoras();
            }

            @Override
            public Optional<String> padrao() {
                return impressora.impressoraPadrao();
            }

            @Override
            public Resultado imprimir(byte[] pdf, String nome, String nomeJob) {
                return impressora.imprimirPdf(pdf, nome, ModoPapel.PAPEL_DO_DRIVER, nomeJob);
            }
        };
    }
}

package br.com.wagner.wagsyspet.agente.impressao.spooler;

import br.com.wagner.wagsyspet.agente.impressao.spooler.AcompanhamentoWindows.JobNaFila;
import br.com.wagner.wagsyspet.agente.impressao.spooler.EstadoSpooler.Estado;
import br.com.wagner.wagsyspet.agente.impressao.spooler.EstadoSpooler.Motivo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static br.com.wagner.wagsyspet.agente.impressao.spooler.HistoricoJobWindows.*;
import static org.assertj.core.api.Assertions.assertThat;

/** A fila do Windows é lida por uma fonte injetável (a real é JNA/winspool); aqui um roteiro de "fotos" da fila. */
@DisplayName("AcompanhamentoWindows — acha o job pelo NOME do documento e fecha o estado pelo histórico")
class AcompanhamentoWindowsTest {

    private static final String DOC = "AgroEase cupom 3f2a9c1e-77aa-4c0e-9d1b-0a1b2c3d4e5f";

    private static final class FilaFalsa implements AcompanhamentoWindows.FonteDaFila {
        final Deque<Object> roteiro = new ArrayDeque<>();
        int statusImpressora;
        final AtomicBoolean fechada = new AtomicBoolean();

        @Override
        @SuppressWarnings("unchecked")
        public List<JobNaFila> jobs() throws IOException {
            Object proximo = roteiro.isEmpty() ? List.of() : roteiro.poll();
            if (proximo instanceof IOException e) {
                throw e;
            }
            return (List<JobNaFila>) proximo;
        }

        @Override public int statusImpressora() { return statusImpressora; }
        @Override public void close() { fechada.set(true); }
    }

    @Test
    @DisplayName("acha o job pelo documento (ignora os de outros programas e os de OUTROS cupons); spoolando → imprimindo → sumiu = IMPRESSO; encerrado fecha a fonte e para de olhar")
    void caminhoFeliz() {
        FilaFalsa fila = new FilaFalsa();
        fila.roteiro.add(List.of(new JobNaFila(7, "Relatório.docx", JOB_STATUS_PRINTING), new JobNaFila(8, "AgroEase cupom OUTRO-ID", JOB_STATUS_PAPEROUT)));
        fila.roteiro.add(List.of(new JobNaFila(9, DOC, JOB_STATUS_SPOOLING)));
        fila.roteiro.add(List.of(new JobNaFila(9, DOC, JOB_STATUS_PRINTING)));
        fila.roteiro.add(List.of());
        AcompanhamentoWindows a = new AcompanhamentoWindows(DOC, fila);
        a.olhar();
        assertThat(a.consultar().estado()).as("só havia jobs alheios").isEqualTo(Estado.PENDENTE);
        assertThat(a.consultar().motivo()).as("o PAPEROUT era de OUTRO cupom").isNull();
        a.olhar();
        a.olhar();
        assertThat(a.consultar().estado()).isEqualTo(Estado.PENDENTE);
        assertThat(a.olhar()).as("terminal → não precisa olhar mais").isFalse();
        EstadoSpooler e = a.consultar();
        assertThat(e.estado()).isEqualTo(Estado.IMPRESSO);
        assertThat(e.detalhe()).contains("9");
        assertThat(fila.fechada).isTrue();
    }

    @Test
    @DisplayName("nome de documento TRUNCADO pelo driver (≥ 24 caracteres iniciais iguais) ainda casa; prefixo curto demais não")
    void documentoTruncado() {
        FilaFalsa fila = new FilaFalsa();
        fila.roteiro.add(List.of(new JobNaFila(1, "AgroEase cupom", JOB_STATUS_PRINTED)));
        fila.roteiro.add(List.of(new JobNaFila(2, DOC.substring(0, 31), JOB_STATUS_PRINTED)));
        AcompanhamentoWindows a = new AcompanhamentoWindows(DOC, fila);
        a.olhar();
        assertThat(a.consultar().estado()).isEqualTo(Estado.PENDENTE);
        a.olhar();
        assertThat(a.consultar().estado()).isEqualTo(Estado.IMPRESSO);
    }

    @Test
    @DisplayName("job parado na fila: o motivo vem do job ou da impressora; falha ao ler a fila não derruba nada (CONSULTA_INDISPONIVEL, segue olhando); close() é idempotente")
    void pendenteEFalha() {
        FilaFalsa fila = new FilaFalsa();
        fila.roteiro.add(new IOException("EnumJobs falhou (1722)"));
        fila.roteiro.add(List.of(new JobNaFila(3, DOC, JOB_STATUS_SPOOLING)));
        fila.statusImpressora = PRINTER_STATUS_OFFLINE;
        AcompanhamentoWindows a = new AcompanhamentoWindows(DOC, fila);
        assertThat(a.olhar()).isTrue();
        assertThat(a.consultar().motivo()).isEqualTo(Motivo.CONSULTA_INDISPONIVEL);
        a.olhar();
        assertThat(a.consultar().estado()).isEqualTo(Estado.PENDENTE);
        assertThat(a.consultar().motivo()).isEqualTo(Motivo.IMPRESSORA_OFFLINE);
        a.close();
        a.close();
        assertThat(fila.fechada).isTrue();
        assertThat(a.olhar()).as("fechado não olha mais").isFalse();
    }

    @Test
    @DisplayName("armar() fora do Windows (ou sem a biblioteca nativa) devolve vazio em vez de lançar — a impressão nunca depende do acompanhamento")
    void armarForaDoWindows() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            assertThat(AcompanhamentoWindows.armar("Impressora Qualquer", DOC)).isEmpty();
        }
    }
}

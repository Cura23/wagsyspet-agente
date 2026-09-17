package br.com.wagner.wagsyspet.agente.impressao.spooler;

import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.Winspool;
import com.sun.jna.platform.win32.WinspoolUtil;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Fila de uma impressora do Windows por JNA ({@code winspool.drv}): {@code OpenPrinter} uma vez, {@code EnumJobs} nível 1 a cada foto (usado
 * por UMA thread só — o handle não é thread-safe),
 * {@code GetPrinter} nível 2 para o status. Sem privilégio especial (o handle padrão já lê a fila do próprio usuário). Só é carregada
 * no Windows — no Linux/macOS a classe nem é tocada.
 */
final class FilaWindowsJna implements AcompanhamentoWindows.FonteDaFila {

    private final String impressora;
    private final WinNT.HANDLEByReference handle = new WinNT.HANDLEByReference();

    FilaWindowsJna(String impressora) throws IOException {
        this.impressora = impressora;
        if (!Winspool.INSTANCE.OpenPrinter(impressora, handle, null)) {
            throw new IOException("OpenPrinter('" + impressora + "') falhou (" + Kernel32.INSTANCE.GetLastError() + ")");
        }
    }

    private static final int ERROR_INSUFFICIENT_BUFFER = 122;
    private static final int TENTATIVAS = 4;

    /**
     * {@code EnumJobs} nível 1 chamado DIRETO, não pelo {@code WinspoolUtil.getJobInfo1} da JNA 5.17 (adversarial L4): aquele (a) ignora
     * a falha da 1ª chamada e devolve "fila vazia" com o spooler caído — o que aqui viraria "job sumiu" → impresso falso —, e (b) tem um
     * laço que nunca zera o último erro: se a fila cresce entre a chamada do tamanho e a dos dados ({@code ERROR_INSUFFICIENT_BUFFER}),
     * ele gira para sempre a 100% de CPU. Aqui: falha real → IOException; tentativas limitadas.
     */
    @Override
    public List<AcompanhamentoWindows.JobNaFila> jobs() throws IOException {
        com.sun.jna.ptr.IntByReference necessario = new com.sun.jna.ptr.IntByReference();
        com.sun.jna.ptr.IntByReference devolvidos = new com.sun.jna.ptr.IntByReference();
        try {
            for (int tentativa = 0; tentativa < TENTATIVAS; tentativa++) {
                int tamanho = necessario.getValue();
                Winspool.JOB_INFO_1 buffer = tamanho <= 0 ? null : new Winspool.JOB_INFO_1(tamanho);
                boolean ok = Winspool.INSTANCE.EnumJobs(handle.getValue(), 0, 255, 1, buffer == null ? null : buffer.getPointer(), Math.max(tamanho, 0), necessario, devolvidos);
                if (ok) {
                    if (buffer == null || devolvidos.getValue() <= 0) {
                        return List.of();
                    }
                    buffer.read();
                    Winspool.JOB_INFO_1[] jobs = (Winspool.JOB_INFO_1[]) buffer.toArray(devolvidos.getValue());
                    List<AcompanhamentoWindows.JobNaFila> lista = new ArrayList<>(jobs.length);
                    for (Winspool.JOB_INFO_1 j : jobs) {
                        lista.add(new AcompanhamentoWindows.JobNaFila(j.JobId, j.pDocument, j.Status));
                    }
                    return lista;
                }
                int erro = Kernel32.INSTANCE.GetLastError();
                if (erro != ERROR_INSUFFICIENT_BUFFER) {
                    throw new IOException("EnumJobs falhou (" + erro + ")");
                }
            }
            throw new IOException("EnumJobs: a fila não parou de mudar em " + TENTATIVAS + " tentativas");
        } catch (RuntimeException e) {
            throw new IOException("EnumJobs falhou: " + e, e);
        }
    }

    @Override
    public int statusImpressora() {
        try {
            // Status E Attributes: a USB desligada aparece como "Usar impressora offline" nos ATRIBUTOS, com Status = 0
            Winspool.PRINTER_INFO_2 info = WinspoolUtil.getPrinterInfo2(impressora);
            return HistoricoJobWindows.statusEfetivo(info.Status, info.Attributes);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    @Override
    public void close() {
        if (handle.getValue() != null) {
            Winspool.INSTANCE.ClosePrinter(handle.getValue());
        }
    }
}

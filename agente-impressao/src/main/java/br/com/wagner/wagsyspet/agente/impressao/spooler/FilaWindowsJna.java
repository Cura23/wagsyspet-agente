package br.com.wagner.wagsyspet.agente.impressao.spooler;

import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.Winspool;
import com.sun.jna.platform.win32.WinspoolUtil;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Fila de uma impressora do Windows por JNA ({@code winspool.drv}): {@code OpenPrinter} uma vez, {@code EnumJobs} nível 1 a cada foto,
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

    @Override
    public List<AcompanhamentoWindows.JobNaFila> jobs() throws IOException {
        try {
            Winspool.JOB_INFO_1[] jobs = WinspoolUtil.getJobInfo1(handle);
            List<AcompanhamentoWindows.JobNaFila> lista = new ArrayList<>(jobs.length);
            for (Winspool.JOB_INFO_1 j : jobs) {
                lista.add(new AcompanhamentoWindows.JobNaFila(j.JobId, j.pDocument, j.Status));
            }
            return lista;
        } catch (RuntimeException e) { // Win32Exception
            throw new IOException("EnumJobs falhou: " + e.getMessage(), e);
        }
    }

    @Override
    public int statusImpressora() {
        try {
            return WinspoolUtil.getPrinterInfo2(impressora).Status;
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

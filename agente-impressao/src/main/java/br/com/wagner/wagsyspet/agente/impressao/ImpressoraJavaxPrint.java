package br.com.wagner.wagsyspet.agente.impressao;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.printing.PDFPageable;
import org.apache.pdfbox.printing.PDFPrintable;
import org.apache.pdfbox.printing.Scaling;

import javax.print.PrintService;
import javax.print.PrintServiceLookup;
import java.awt.print.PrinterException;
import java.awt.print.PrinterJob;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Imprime um PDF (o cupom de 80mm gerado pelo backend) numa impressora escolhida <b>por nome</b>,
 * silenciosamente, via {@code javax.print} + PDFBox — o mesmo código em Windows (spooler), Linux e
 * macOS (CUPS). É o motor único do agente (plano §2.2).
 *
 * <p><b>Estado de spike F0:</b> primeira versão funcional para provar o caminho de produção
 * ({@code javax.print} → CUPS → impressora virtual {@code PDF}). Será endurecida por TDD na F3
 * (fila de 1 job, {@code PrintJobListener} para estado assíncrono, timeout, logs por job).</p>
 */
public final class ImpressoraJavaxPrint {

    /** Como dimensionar o papel do job. */
    public enum ModoPapel {
        /** Usa o tamanho de página do próprio PDF (80mm × H). Requer que o driver aceite mídia custom. */
        PAPEL_DO_PDF,
        /**
         * Não define mídia: o driver aplica seu preset (ex.: 80×3276mm da térmica). Modo <b>default</b>
         * por impressora — é o que funciona em qualquer térmica (ver #3421 no plano).
         */
        PAPEL_DO_DRIVER
    }

    /** Resultado honesto: "aceito pelo spooler" não é "papel saiu" (limite de qualquer spooler). */
    /**
     * @param acompanhamento alça para perguntar ao spooler o que aconteceu DEPOIS do aceite (F6-L4); {@code null} quando o job não
     *                       foi aceito ou o motor não sabe acompanhar
     */
    public record Resultado(Estado estado, String impressora, String detalhe,
                            br.com.wagner.wagsyspet.agente.impressao.spooler.AcompanhamentoSpooler acompanhamentoOuNull) {
        public enum Estado { ACEITO_SPOOLER, IMPRESSORA_INDISPONIVEL, ERRO }

        public Resultado(Estado estado, String impressora, String detalhe) {
            this(estado, impressora, detalhe, null);
        }

        public java.util.Optional<br.com.wagner.wagsyspet.agente.impressao.spooler.AcompanhamentoSpooler> acompanhamento() {
            return java.util.Optional.ofNullable(acompanhamentoOuNull);
        }

        public boolean aceito() {
            return estado == Estado.ACEITO_SPOOLER;
        }
    }

    private ImpressoraJavaxPrint() {
    }

    /** Nomes das impressoras que o SO conhece (spooler do Windows / CUPS no Unix). */
    public static List<String> listarImpressoras() {
        return Arrays.stream(PrintServiceLookup.lookupPrintServices(null, null))
                .map(PrintService::getName)
                .sorted()
                .toList();
    }

    /** Impressora padrão do SO, se houver. */
    public static Optional<String> impressoraPadrao() {
        return Optional.ofNullable(PrintServiceLookup.lookupDefaultPrintService()).map(PrintService::getName);
    }

    /**
     * Imprime o PDF na impressora indicada, sem diálogo.
     *
     * @param pdf            bytes do PDF (cupom 80mm)
     * @param nomeImpressora nome exato como listado pelo SO
     * @param modo           {@link ModoPapel}
     * @param nomeJob        título do job no spooler (útil para diagnóstico; o cups-pdf usa como nome do arquivo)
     */
    public static Resultado imprimirPdf(byte[] pdf, String nomeImpressora, ModoPapel modo, String nomeJob) {
        return imprimirPdf(pdf, nomeImpressora, modo, nomeJob, (impressora, job) -> Optional.empty());
    }

    /**
     * @param armar F6-L4: abre o acompanhamento do job no spooler ANTES do {@code print()} (o Windows apaga o job ao imprimir — quem
     *              só olha depois não vê nada). Vazio = sem acompanhamento; nunca pode impedir a impressão.
     */
    public static Resultado imprimirPdf(byte[] pdf, String nomeImpressora, ModoPapel modo, String nomeJob,
                                        java.util.function.BiFunction<String, String, Optional<br.com.wagner.wagsyspet.agente.impressao.spooler.AcompanhamentoSpooler>> armar) {
        Optional<PrintService> servico = localizar(nomeImpressora);
        if (servico.isEmpty()) {
            return new Resultado(Resultado.Estado.IMPRESSORA_INDISPONIVEL, nomeImpressora,
                    "Impressora não encontrada no SO. Disponíveis: " + listarImpressoras());
        }
        br.com.wagner.wagsyspet.agente.impressao.spooler.AcompanhamentoSpooler acompanhamento = null;
        boolean aceito = false;
        try (PDDocument documento = Loader.loadPDF(pdf)) {
            PrinterJob job = PrinterJob.getPrinterJob();
            job.setPrintService(servico.get());
            job.setJobName(nomeJob);
            switch (modo) {
                case PAPEL_DO_PDF -> job.setPageable(new PDFPageable(documento));
                case PAPEL_DO_DRIVER -> job.setPrintable(new PDFPrintable(documento, Scaling.SHRINK_TO_FIT));
            }
            try {
                acompanhamento = armar.apply(servico.get().getName(), nomeJob).orElse(null);
            } catch (RuntimeException | LinkageError e) {
                acompanhamento = null; // o acompanhamento é um extra: a impressão segue
            }
            job.print(); // silencioso: nenhum diálogo; lança PrinterException se o spooler recusar
            aceito = true;
            if (acompanhamento != null) {
                acompanhamento.submetido(); // só agora "nunca apareceu na fila" pode ser conclusão
            }
            return new Resultado(Resultado.Estado.ACEITO_SPOOLER, servico.get().getName(),
                    "Job '" + nomeJob + "' aceito pelo spooler (" + documento.getNumberOfPages() + " pág., modo " + modo + ")", acompanhamento);
        } catch (PrinterException e) {
            // Mensagem pode vir null (ex.: falha ao executar o spooler do SO); inclui classe + causa + 1º frame
            String causa = e.getCause() != null ? " causa=" + e.getCause() : "";
            String origem = e.getStackTrace().length > 0 ? " em " + e.getStackTrace()[0] : "";
            return new Resultado(Resultado.Estado.ERRO, nomeImpressora,
                    "Spooler recusou o job: " + e.getClass().getSimpleName() + "(" + e.getMessage() + ")" + causa + origem);
        } catch (IOException e) {
            return new Resultado(Resultado.Estado.ERRO, nomeImpressora, "PDF inválido: " + e.getMessage());
        } finally {
            if (!aceito && acompanhamento != null) {
                acompanhamento.close(); // job recusado: solta o handle da fila e a thread que já estava olhando
            }
        }
    }

    /**
     * F6-L5 (Windows): bytes crus pelo spooler — {@code DocFlavor.BYTE_ARRAY.AUTOSENSE} faz o JDK abrir o documento com
     * {@code pDatatype="RAW"} e escrever com {@code WritePrinter}: o driver NÃO renderiza nada. Job separado, na mesma fila do cupom.
     */
    public static Resultado enviarRaw(byte[] bytes, String nomeImpressora, String nomeJob) {
        return enviarRaw(bytes, nomeImpressora, nomeJob, br.com.wagner.wagsyspet.agente.impressao.raw.DriverWindows::v4);
    }

    /** @param driverV4 driver v4/XPSDrv descarta RAW em silêncio: erro HONESTO em vez de "sucesso" (ver {@code DriverWindows}) */
    public static Resultado enviarRaw(byte[] bytes, String nomeImpressora, String nomeJob, java.util.function.Predicate<String> driverV4) {
        if (driverV4.test(nomeImpressora)) {
            return new Resultado(Resultado.Estado.ERRO, nomeImpressora, "O driver desta impressora é do tipo v4 (XPS) e não aceita comando direto: "
                    + "ligue a gaveta/o corte nas preferências do PRÓPRIO driver, ou instale o driver do fabricante (v3)");
        }
        Optional<PrintService> servico = localizar(nomeImpressora);
        if (servico.isEmpty()) {
            return new Resultado(Resultado.Estado.IMPRESSORA_INDISPONIVEL, nomeImpressora,
                    "Impressora não encontrada no SO. Disponíveis: " + listarImpressoras());
        }
        try {
            javax.print.attribute.HashPrintRequestAttributeSet atributos = new javax.print.attribute.HashPrintRequestAttributeSet();
            atributos.add(new javax.print.attribute.standard.JobName(nomeJob, null));
            servico.get().createPrintJob().print(new javax.print.SimpleDoc(bytes, javax.print.DocFlavor.BYTE_ARRAY.AUTOSENSE, null), atributos);
            return new Resultado(Resultado.Estado.ACEITO_SPOOLER, servico.get().getName(), "Job '" + nomeJob + "' (" + bytes.length + " bytes crus) aceito pelo spooler");
        } catch (javax.print.PrintException | RuntimeException e) {
            return new Resultado(Resultado.Estado.ERRO, nomeImpressora, "Spooler recusou o comando direto: " + e);
        }
    }

    private static Optional<PrintService> localizar(String nome) {
        if (nome == null || nome.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(PrintServiceLookup.lookupPrintServices(null, null))
                .filter(s -> s.getName().equals(nome))
                .findFirst();
    }
}

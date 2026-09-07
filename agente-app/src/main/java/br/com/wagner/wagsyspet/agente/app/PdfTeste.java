package br.com.wagner.wagsyspet.agente.app;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Cupom de TESTE 80 mm (PDFBox, fonte padrão Courier — sem arquivo de fonte externo, roda em qualquer runtime jlink).
 * Usado pelo "Imprimir teste" do painel (F3) e pelo smoke de empacotamento. O cupom real vem do backend (iText).
 */
final class PdfTeste {

    private static final float PT_POR_MM = 72f / 25.4f;
    private static final int ALTURA_MM = 100;

    private PdfTeste() {
    }

    static byte[] cupom80mm(String titulo, String... linhasExtras) throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage pagina = new PDPage(new PDRectangle(80 * PT_POR_MM, ALTURA_MM * PT_POR_MM));
            doc.addPage(pagina);
            try (PDPageContentStream cs = new PDPageContentStream(doc, pagina)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.COURIER_BOLD), 10);
                cs.setLeading(12f);
                cs.newLineAtOffset(3 * PT_POR_MM, (ALTURA_MM - 8) * PT_POR_MM);
                cs.showText(ascii(titulo));
                cs.newLine();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.COURIER), 8);
                cs.showText("Teste de impressao");
                cs.newLine();
                cs.showText(LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")));
                cs.newLine();
                cs.showText("--------------------------------------");
                cs.newLine();
                for (String linha : linhasExtras) {
                    for (String pedaco : quebrar(ascii(linha), 38)) {
                        cs.showText(pedaco);
                        cs.newLine();
                    }
                }
                cs.showText("--------------------------------------");
                cs.newLine();
                cs.showText("Se este cupom saiu inteiro e legivel,");
                cs.newLine();
                cs.showText("a impressora esta pronta para o PDV.");
                cs.endText();
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    /** Courier padrão (WinAnsi) não tem todos os acentos combinados; normaliza pra ASCII no cupom de teste. */
    private static String ascii(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("[^\\p{ASCII}]", "");
    }

    private static String[] quebrar(String s, int largura) {
        int n = (s.length() + largura - 1) / largura;
        String[] partes = new String[Math.max(n, 1)];
        for (int i = 0; i < partes.length; i++) {
            partes[i] = s.substring(i * largura, Math.min(s.length(), (i + 1) * largura));
        }
        return partes;
    }
}

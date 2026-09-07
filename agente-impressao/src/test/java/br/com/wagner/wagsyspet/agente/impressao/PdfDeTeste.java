package br.com.wagner.wagsyspet.agente.impressao;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** Gera um PDF de cupom 80mm sintético (sem depender do backend) para testes de impressão. */
final class PdfDeTeste {

    private static final float PT_POR_MM = 72f / 25.4f;

    private PdfDeTeste() {
    }

    /** Cupom 80mm × {@code alturaMm}, fonte Courier 9pt, com as linhas dadas. */
    static byte[] cupom80mm(int alturaMm, String... linhas) throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage pagina = new PDPage(new PDRectangle(80 * PT_POR_MM, alturaMm * PT_POR_MM));
            doc.addPage(pagina);
            try (PDPageContentStream cs = new PDPageContentStream(doc, pagina)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.COURIER), 9);
                cs.setLeading(11f);
                cs.newLineAtOffset(3 * PT_POR_MM, (alturaMm - 6) * PT_POR_MM);
                for (String linha : linhas) {
                    cs.showText(linha);
                    cs.newLine();
                }
                cs.endText();
            }
            doc.save(out);
            return out.toByteArray();
        }
    }
}

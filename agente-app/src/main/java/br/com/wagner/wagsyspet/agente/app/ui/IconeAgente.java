package br.com.wagner.wagsyspet.agente.app.ui;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/** Ícone desenhado em código (sem asset): quadrado arredondado verde AgroEase com "A"; cinza quando não pareado. */
public final class IconeAgente {

    private static final Color VERDE = new Color(0x05, 0x96, 0x69);
    private static final Color CINZA = new Color(0x6B, 0x72, 0x80);

    private IconeAgente() {
    }

    public static BufferedImage imagem(int tamanho, boolean pareado) {
        BufferedImage img = new BufferedImage(tamanho, tamanho, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(pareado ? VERDE : CINZA);
            int raio = Math.max(3, tamanho / 4);
            g.fillRoundRect(0, 0, tamanho, tamanho, raio, raio);
            g.setColor(Color.WHITE);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(8, (int) (tamanho * 0.7))));
            FontMetrics fm = g.getFontMetrics();
            String letra = "A";
            int x = (tamanho - fm.stringWidth(letra)) / 2;
            int y = (tamanho - fm.getHeight()) / 2 + fm.getAscent();
            g.drawString(letra, x, y);
        } finally {
            g.dispose();
        }
        return img;
    }
}

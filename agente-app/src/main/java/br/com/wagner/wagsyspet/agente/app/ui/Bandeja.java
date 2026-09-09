package br.com.wagner.wagsyspet.agente.app.ui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.AWTException;
import java.awt.GraphicsEnvironment;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

/**
 * Ícone na bandeja do sistema (plano F3 D21) — Windows, macOS e Linux com KDE/XFCE ou GNOME ≥ 45 com extensão.
 * <b>Opcional</b>: {@link #disponivel()} é false no GNOME sem AppIndicator (o JDK 21 desliga a bandeja à força abaixo do
 * GNOME 45) e em headless — aí a casca usa a {@link JanelaStatus}. Menu pt-BR mínimo; balões só em eventos raros.
 */
public final class Bandeja implements Superficie {

    private static final Logger log = LoggerFactory.getLogger(Bandeja.class);

    private final TrayIcon icone;
    private final MenuItem itemEstado;
    private final MenuItem itemDetalhe;
    private final AcoesUi acoes;

    public static boolean disponivel() {
        try {
            return !GraphicsEnvironment.isHeadless() && SystemTray.isSupported();
        } catch (RuntimeException e) {
            return false;
        }
    }

    public Bandeja(AcoesUi.Agente agente) throws AWTException {
        this.acoes = new AcoesUi(agente, this::erroDialogo, this::avisoDialogo);
        PopupMenu menu = new PopupMenu();
        itemEstado = new MenuItem(AcoesUi.TITULO);
        itemEstado.setEnabled(false);
        itemDetalhe = new MenuItem("…");
        itemDetalhe.setEnabled(false);
        menu.add(itemEstado);
        menu.add(itemDetalhe);
        menu.addSeparator();
        menu.add(item("Parear…", acoes::parear));
        menu.add(item("Impressora…", acoes::escolherImpressora));
        menu.add(item("Imprimir teste", acoes::imprimirTeste));
        menu.add(item("Ver log", acoes::verLog));
        menu.addSeparator();
        menu.add(item("Desparear este computador…", acoes::desparear));
        menu.add(item("Sair", acoes::sair));

        icone = new TrayIcon(IconeAgente.imagem(tamanhoIcone(), false), AcoesUi.TITULO, menu);
        icone.setImageAutoSize(true);
        SystemTray.getSystemTray().add(icone);
        log.info("Bandeja do sistema ativa");
    }

    private static int tamanhoIcone() {
        try {
            return Math.max(16, SystemTray.getSystemTray().getTrayIconSize().width);
        } catch (RuntimeException e) {
            return 16;
        }
    }

    private static MenuItem item(String rotulo, Runnable acao) {
        MenuItem m = new MenuItem(rotulo);
        m.addActionListener(e -> acao.run());
        return m;
    }

    @Override
    public void estado(String titulo, String detalhe, boolean pareado) {
        SwingUtilities.invokeLater(() -> {
            itemEstado.setLabel(titulo);
            itemDetalhe.setLabel(detalhe);
            icone.setImage(IconeAgente.imagem(tamanhoIcone(), pareado));
            icone.setToolTip(AcoesUi.TITULO + " — " + titulo);
        });
    }

    @Override
    public void aviso(String titulo, String mensagem) {
        SwingUtilities.invokeLater(() -> icone.displayMessage(titulo, mensagem, TrayIcon.MessageType.INFO));
    }

    @Override
    public void erro(String titulo, String mensagem) {
        SwingUtilities.invokeLater(() -> icone.displayMessage(titulo, mensagem, TrayIcon.MessageType.ERROR));
    }

    @Override
    public void erroFatal(String titulo, String mensagem) {
        Runnable r = () -> JOptionPane.showMessageDialog(null, mensagem, titulo, JOptionPane.ERROR_MESSAGE);
        try {
            if (SwingUtilities.isEventDispatchThread()) {
                r.run();
            } else {
                SwingUtilities.invokeAndWait(r);
            }
        } catch (Exception e) {
            log.warn("não foi possível mostrar o erro fatal ({}): {}", e.toString(), mensagem);
        }
    }

    private void erroDialogo(String mensagem) {
        JOptionPane.showMessageDialog(null, mensagem, AcoesUi.TITULO, JOptionPane.ERROR_MESSAGE);
    }

    private void avisoDialogo(String mensagem) {
        JOptionPane.showMessageDialog(null, mensagem, AcoesUi.TITULO, JOptionPane.INFORMATION_MESSAGE);
    }
}

package br.com.wagner.wagsyspet.agente.app.ui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/**
 * Janela de status — a casca quando NÃO há bandeja (GNOME sem extensão, inclusive a máquina do dono) (plano F3 D21).
 * Fechar a janela <b>não</b> encerra o agente: ela é minimizada e o servidor continua (sair só pelo botão "Sair").
 */
public final class JanelaStatus implements Superficie {

    private static final Logger log = LoggerFactory.getLogger(JanelaStatus.class);

    private final JFrame frame;
    private final JLabel titulo = new JLabel(" ");
    private final JLabel detalhe = new JLabel(" ");
    private final AcoesUi acoes;

    public JanelaStatus(AcoesUi.Agente agente, String versao) {
        this.acoes = new AcoesUi(agente, this::erroDialogo, this::avisoDialogo);
        frame = new JFrame(AcoesUi.TITULO);
        frame.setIconImage(IconeAgente.imagem(64, false));
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                frame.setExtendedState(Frame.ICONIFIED); // continua rodando; só some da frente
            }
        });

        JPanel topo = new JPanel(new GridLayout(2, 1, 0, 4));
        topo.setBorder(BorderFactory.createEmptyBorder(14, 16, 6, 16));
        titulo.setFont(titulo.getFont().deriveFont(Font.BOLD, 15f));
        detalhe.setForeground(new Color(0x4B, 0x55, 0x63));
        topo.add(titulo);
        topo.add(detalhe);

        JPanel botoes = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        botoes.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));
        botoes.add(botao("Parear…", acoes::parear));
        botoes.add(botao("Impressora…", acoes::escolherImpressora));
        botoes.add(botao("Imprimir teste", acoes::imprimirTeste));
        botoes.add(botao("Ver log", acoes::verLog));
        botoes.add(botao("Desparear…", acoes::desparear));
        botoes.add(botao("Sair", acoes::sair));

        JLabel rodape = new JLabel("Versão " + versao + " · fechar esta janela não desliga o agente (use Sair).");
        rodape.setFont(rodape.getFont().deriveFont(11f));
        rodape.setForeground(new Color(0x6B, 0x72, 0x80));
        rodape.setBorder(BorderFactory.createEmptyBorder(0, 16, 12, 16));

        frame.setLayout(new BorderLayout());
        frame.add(topo, BorderLayout.NORTH);
        frame.add(botoes, BorderLayout.CENTER);
        frame.add(rodape, BorderLayout.SOUTH);
        frame.pack();
        frame.setMinimumSize(frame.getSize());
        frame.setLocationByPlatform(true);
        frame.setVisible(true);
        log.info("Janela de status aberta (sem bandeja do sistema neste ambiente)");
    }

    private static JButton botao(String rotulo, Runnable acao) {
        JButton b = new JButton(rotulo);
        b.addActionListener(e -> acao.run());
        return b;
    }

    @Override
    public void estado(String t, String d, boolean pareado) {
        SwingUtilities.invokeLater(() -> {
            titulo.setText(t);
            titulo.setForeground(pareado ? new Color(0x05, 0x96, 0x69) : new Color(0xB4, 0x53, 0x09));
            detalhe.setText(d);
            frame.setIconImage(IconeAgente.imagem(64, pareado));
        });
    }

    @Override
    public void aviso(String t, String mensagem) {
        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(frame, mensagem, t, JOptionPane.INFORMATION_MESSAGE));
    }

    @Override
    public void erro(String t, String mensagem) {
        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(frame, mensagem, t, JOptionPane.ERROR_MESSAGE));
    }

    @Override
    public void erroFatal(String t, String mensagem) {
        Runnable r = () -> {
            frame.setExtendedState(Frame.NORMAL);
            frame.toFront();
            JOptionPane.showMessageDialog(frame, mensagem, t, JOptionPane.ERROR_MESSAGE);
        };
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
        JOptionPane.showMessageDialog(frame, mensagem, AcoesUi.TITULO, JOptionPane.ERROR_MESSAGE);
    }

    private void avisoDialogo(String mensagem) {
        JOptionPane.showMessageDialog(frame, mensagem, AcoesUi.TITULO, JOptionPane.INFORMATION_MESSAGE);
    }
}

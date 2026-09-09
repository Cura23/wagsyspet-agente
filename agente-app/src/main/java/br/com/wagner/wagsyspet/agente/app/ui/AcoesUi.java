package br.com.wagner.wagsyspet.agente.app.ui;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

/**
 * Ações comuns à bandeja e à janela (plano F3 D21): pedem os dados ao lojista com diálogos Swing (na EDT) e executam a
 * ação em thread própria (nunca na EDT — pareamento leva até 60 s no cold start; listar impressoras toca o spooler).
 * Os textos são os que o lojista vê: marca AgroEase, sem termos internos.
 */
public final class AcoesUi {

    /** O que a UI precisa do orquestrador (implementado por {@code AgenteDesktop}). */
    public interface Agente {
        void parear(String codigo, String backendUrlOpcional) throws Exception;

        void desparear() throws Exception;

        List<String> listarImpressoras();

        Optional<String> impressoraSelecionada();

        void selecionarImpressora(String nome) throws Exception;

        /** Devolve o texto do resultado para mostrar ("ACEITO_SPOOLER — …"). */
        String imprimirTeste() throws Exception;

        Path pastaLogs();

        boolean pareado();

        void sair();
    }

    public static final String TITULO = "Agente de Impressão AgroEase";

    private final Agente agente;
    private final Consumer<String> mostrarErro;
    private final Consumer<String> mostrarAviso;

    public AcoesUi(Agente agente, Consumer<String> mostrarErro, Consumer<String> mostrarAviso) {
        this.agente = agente;
        this.mostrarErro = mostrarErro;
        this.mostrarAviso = mostrarAviso;
    }

    public void parear() {
        String codigo = (String) JOptionPane.showInputDialog(null,
                "Cole o código de pareamento gerado no painel da loja\n(Configurações → Geral → Impressão de Cupom → Gerar código):",
                TITULO + " — Parear", JOptionPane.PLAIN_MESSAGE, null, null, "");
        if (codigo == null || codigo.isBlank()) {
            return;
        }
        emSegundoPlano("parear", () -> {
            agente.parear(codigo.trim(), null);
        });
    }

    public void desparear() {
        int r = JOptionPane.showConfirmDialog(null,
                "Remover o pareamento deste computador?\nOs caixas deixam de imprimir pelo agente até parear de novo.\n"
                        + "Se este computador não vai mais ser usado, revogue-o também no painel da loja.",
                TITULO + " — Desparear", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (r != JOptionPane.OK_OPTION) {
            return;
        }
        emSegundoPlano("desparear", agente::desparear);
    }

    public void escolherImpressora() {
        emSegundoPlano("listar impressoras", () -> {
            List<String> nomes = agente.listarImpressoras();
            String atual = agente.impressoraSelecionada().orElse(null);
            SwingUtilities.invokeLater(() -> {
                if (nomes.isEmpty()) {
                    mostrarErro.accept("Nenhuma impressora instalada neste computador. Instale a impressora no sistema e tente de novo.");
                    return;
                }
                Object escolha = JOptionPane.showInputDialog(null,
                        "Impressora que este computador usa para o cupom:", TITULO + " — Impressora",
                        JOptionPane.PLAIN_MESSAGE, null, nomes.toArray(), atual != null && nomes.contains(atual) ? atual : nomes.get(0));
                if (escolha == null) {
                    return;
                }
                emSegundoPlano("selecionar impressora", () -> {
                    agente.selecionarImpressora(escolha.toString());
                    avisar("Impressora deste computador: " + escolha + "\nUse \"Imprimir teste\" para conferir.");
                });
            });
        });
    }

    public void imprimirTeste() {
        emSegundoPlano("imprimir teste", () -> {
            String r = agente.imprimirTeste();
            avisar("Teste enviado. Confira se saiu o papel.\n" + r);
        });
    }

    /** Diálogos Swing só na EDT: as ações rodam em thread própria. */
    private void avisar(String msg) {
        if (SwingUtilities.isEventDispatchThread()) {
            mostrarAviso.accept(msg);
        } else {
            SwingUtilities.invokeLater(() -> mostrarAviso.accept(msg));
        }
    }

    public void verLog() {
        emSegundoPlano("abrir pasta de log", () -> abrirPasta(agente.pastaLogs()));
    }

    public void sair() {
        agente.sair();
    }

    /** {@code Desktop.open} e, se o SO não expuser (Wayland/GNOME sem integração), o comando nativo. */
    static void abrirPasta(Path pasta) throws IOException {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(pasta.toFile());
                return;
            }
        } catch (RuntimeException | IOException e) {
            // cai no comando nativo
        }
        String os = System.getProperty("os.name", "").toLowerCase();
        String[] cmd = os.contains("win") ? new String[]{"explorer.exe", pasta.toString()}
                : os.contains("mac") ? new String[]{"open", pasta.toString()}
                : new String[]{"xdg-open", pasta.toString()};
        new ProcessBuilder(cmd).inheritIO().start();
    }

    interface Acao {
        void executar() throws Exception;
    }

    private void emSegundoPlano(String nome, Acao acao) {
        Thread t = new Thread(() -> {
            try {
                acao.executar();
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                SwingUtilities.invokeLater(() -> mostrarErro.accept(msg));
            }
        }, "agente-ui-" + nome.replace(' ', '-'));
        t.setDaemon(true);
        t.start();
    }
}

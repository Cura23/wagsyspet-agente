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

        // F6-L5 — gaveta e corte (defaults: agente sem suporte)
        default Optional<br.com.wagner.wagsyspet.agente.core.ExtrasImpressao> extrasDaImpressora() {
            return Optional.empty();
        }

        default void configurarExtras(br.com.wagner.wagsyspet.agente.core.ExtrasImpressao extras) throws Exception {
            throw new UnsupportedOperationException();
        }

        default String testarGaveta(br.com.wagner.wagsyspet.agente.core.ExtrasImpressao valores) throws Exception {
            throw new UnsupportedOperationException();
        }

        default String testarCorte(br.com.wagner.wagsyspet.agente.core.ExtrasImpressao valores) throws Exception {
            throw new UnsupportedOperationException();
        }

        Path pastaLogs();

        boolean pareado();

        void sair();

        /** Versão nova já baixada e verificada, se houver (F6). */
        default Optional<String> atualizacaoDisponivel() {
            return Optional.empty();
        }

        /** Fecha as conexões com ATUALIZANDO, entrega o plano ao atualizador e encerra o processo (F6). */
        default void atualizarAgora() throws Exception {
            throw new IllegalStateException("atualização automática indisponível nesta instalação");
        }
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

    /**
     * F6-L5 — "Gaveta e corte…": opt-in DESTE computador para a impressora selecionada. Default tudo desligado; os botões "Testar" usam os
     * valores do diálogo SEM gravar nada — liga-se só depois de ver a gaveta abrir / o papel cortar. Impressora que não é térmica ESC/POS
     * recebe os bytes como lixo e o sistema diz "sucesso" (uma laser solta uma folha em branco por comando): por isso o aviso e o teste.
     */
    public void gavetaECorte() {
        emSegundoPlano("gaveta e corte", () -> {
            Optional<String> impressora = agente.impressoraSelecionada();
            if (impressora.isEmpty()) {
                SwingUtilities.invokeLater(() -> mostrarErro.accept("Escolha primeiro a impressora deste computador (\"Impressora…\")."));
                return;
            }
            Optional<br.com.wagner.wagsyspet.agente.core.ExtrasImpressao> atuais = agente.extrasDaImpressora();
            SwingUtilities.invokeLater(() -> mostrarDialogoGavetaECorte(impressora.get(), atuais));
        });
    }

    private void mostrarDialogoGavetaECorte(String impressora, Optional<br.com.wagner.wagsyspet.agente.core.ExtrasImpressao> atuais) {
        // combo ↔ enum por TABELA explícita (índice = posição aqui), não por ordinal(): reordenar o enum não pode trocar o dialeto gravado
        final br.com.wagner.wagsyspet.agente.impressao.raw.ComandosRaw.Dialeto[] dialetos = {
                br.com.wagner.wagsyspet.agente.impressao.raw.ComandosRaw.Dialeto.ESCPOS, br.com.wagner.wagsyspet.agente.impressao.raw.ComandosRaw.Dialeto.ESC_BEMA};
        javax.swing.JCheckBox gaveta = new javax.swing.JCheckBox("Abrir a gaveta ao imprimir o cupom de uma venda em dinheiro", atuais.map(e -> e.gaveta()).orElse(false));
        javax.swing.JCheckBox corte = new javax.swing.JCheckBox("Cortar o papel depois de cada cupom (só se a impressora não corta sozinha)", atuais.map(e -> e.corte()).orElse(false));
        javax.swing.JComboBox<String> dialeto = new javax.swing.JComboBox<>(new String[]{"Epson, Elgin e compatíveis (ESC/POS)", "Bematech no modo de fábrica (ESC/Bema)"});
        dialeto.setSelectedIndex(atuais.map(e -> java.util.Arrays.asList(dialetos).indexOf(e.dialeto())).filter(i -> i >= 0).orElse(0));
        javax.swing.JComboBox<Integer> pino = new javax.swing.JComboBox<>(new Integer[]{2, 5});
        pino.setSelectedItem(atuais.map(e -> e.gavetaPino()).orElse(br.com.wagner.wagsyspet.agente.impressao.raw.ComandosRaw.PINO_PADRAO));
        javax.swing.JSpinner pulso = new javax.swing.JSpinner(new javax.swing.SpinnerNumberModel(
                (int) atuais.map(e -> e.gavetaPulsoMs()).orElse(br.com.wagner.wagsyspet.agente.impressao.raw.ComandosRaw.PULSO_PADRAO_MS),
                br.com.wagner.wagsyspet.agente.impressao.raw.ComandosRaw.PULSO_MINIMO_MS, br.com.wagner.wagsyspet.agente.impressao.raw.ComandosRaw.PULSO_MAXIMO_MS, 10));
        java.util.function.Supplier<br.com.wagner.wagsyspet.agente.core.ExtrasImpressao> valores = () -> new br.com.wagner.wagsyspet.agente.core.ExtrasImpressao(
                impressora, dialetos[dialeto.getSelectedIndex()], gaveta.isSelected(), corte.isSelected(), (Integer) pino.getSelectedItem(),
                // o teto do pulso depende do dialeto (ESC/Bema: 200 ms): aperta o valor em vez de deixar a validação estourar no clique
                Math.min((Integer) pulso.getValue(), br.com.wagner.wagsyspet.agente.impressao.raw.ComandosRaw.pulsoMaximoMs(dialetos[dialeto.getSelectedIndex()])));

        javax.swing.JButton testarGaveta = new javax.swing.JButton("Testar gaveta");
        testarGaveta.addActionListener(e -> { var v = valores.get(); emSegundoPlano("testar gaveta", () -> avisar(agente.testarGaveta(v) + "\nA gaveta abriu? Se não abriu, aumente o pulso ou troque o tipo de impressora.")); });
        javax.swing.JButton testarCorte = new javax.swing.JButton("Testar corte");
        testarCorte.addActionListener(e -> { var v = valores.get(); emSegundoPlano("testar corte", () -> avisar(agente.testarCorte(v) + "\nO papel cortou? Se saíram caracteres estranhos, troque o tipo de impressora ou deixe desligado.")); });

        javax.swing.JPanel painel = new javax.swing.JPanel();
        painel.setLayout(new javax.swing.BoxLayout(painel, javax.swing.BoxLayout.Y_AXIS));
        String aviso = pareceVirtual(impressora)
                ? "<br><b>Esta impressora parece virtual (PDF/XPS/Fax): gaveta e corte não fazem sentido aqui.</b>" : "";
        painel.add(new javax.swing.JLabel("<html>Impressora deste computador: <b>" + impressora.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</b>" + aviso
                + "<br>Só para impressora térmica de cupom. Use <b>Testar</b> e ligue só se funcionar:<br>"
                + "em outro tipo de impressora sai uma folha em branco a cada comando.</html>"));
        for (java.awt.Component c : new java.awt.Component[]{gaveta, corte, new javax.swing.JLabel("Tipo de impressora:"), dialeto,
                new javax.swing.JLabel("Conector da gaveta (pino):"), pino, new javax.swing.JLabel("Pulso da gaveta (ms):"), pulso}) {
            ((javax.swing.JComponent) c).setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
            painel.add(c);
        }
        javax.swing.JPanel testes = new javax.swing.JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT));
        testes.add(testarGaveta);
        testes.add(testarCorte);
        testes.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        painel.add(testes);

        int escolha = JOptionPane.showConfirmDialog(null, painel, TITULO + " — Gaveta e corte", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (escolha != JOptionPane.OK_OPTION) {
            return;
        }
        var v = valores.get();
        emSegundoPlano("salvar gaveta e corte", () -> {
            agente.configurarExtras(v);
            avisar(v.algumLigado() ? "Salvo: " + (v.gaveta() ? "gaveta ligada" : "gaveta desligada") + ", " + (v.corte() ? "corte ligado" : "corte desligado") + "."
                    : "Gaveta e corte desligados neste computador.");
        });
    }

    /** Aviso de UX, não bloqueio: nomes típicos de impressora que não é hardware de cupom. */
    static boolean pareceVirtual(String nome) {
        String n = nome.toLowerCase(java.util.Locale.ROOT);
        return n.equals("pdf") || n.contains("print to pdf") || n.contains("cups-pdf") || n.contains("xps") || n.contains("fax") || n.contains("onenote");
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

    public void atualizar() {
        Optional<String> v = agente.atualizacaoDisponivel();
        if (v.isEmpty()) {
            avisar("Nenhuma atualização baixada ainda. O agente verifica sozinho algumas vezes por dia.");
            return;
        }
        int r = JOptionPane.showConfirmDialog(null,
                "Atualizar o agente para a versão " + v.get() + " agora?\nO agente fecha por até 1 minuto e volta sozinho. Não imprima nesse intervalo.",
                TITULO + " — Atualizar", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (r != JOptionPane.OK_OPTION) {
            return;
        }
        emSegundoPlano("atualizar", agente::atualizarAgora);
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

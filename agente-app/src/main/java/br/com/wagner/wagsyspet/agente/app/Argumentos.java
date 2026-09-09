package br.com.wagner.wagsyspet.agente.app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Linha de comando do binário (plano F3 D18). Um comando ({@code --parear}, {@code --status}, …) ou nenhum ({@code SERVIR});
 * opções com valor ({@code --backend-url}, {@code --porta}, {@code --dir-dados}) e flags ({@code --sem-bandeja}, {@code --verboso}).
 * Qualquer {@code -D…} como 1º argumento = host de DESENVOLVIMENTO (compatível com o F0). Erros são acumulados, não lançados:
 * o {@code Main} imprime todos de uma vez com o uso.
 */
record Argumentos(Comando comando, Map<String, String> opcoes, List<String> posicionais, List<String> erros) {

    enum Comando {
        SERVIR, PAREAR, DESPAREAR, STATUS, VERSAO, DIAGNOSTICO, GERAR_PDF_TESTE, IMPRIMIR_TESTE, INSTALAR, DESINSTALAR, AJUDA, DEV
    }

    static final String OPT_BACKEND = "backend-url";
    static final String OPT_PORTA = "porta";
    static final String OPT_DIR = "dir-dados";
    static final String FLAG_SEM_BANDEJA = "sem-bandeja";
    static final String FLAG_VERBOSO = "verboso";

    private static final Map<String, Comando> COMANDOS = Map.ofEntries(
            Map.entry("--parear", Comando.PAREAR),
            Map.entry("--desparear", Comando.DESPAREAR),
            Map.entry("--status", Comando.STATUS),
            Map.entry("--versao", Comando.VERSAO),
            Map.entry("--version", Comando.VERSAO),
            Map.entry("--diagnostico", Comando.DIAGNOSTICO),
            Map.entry("--gerar-pdf-teste", Comando.GERAR_PDF_TESTE),
            Map.entry("--imprimir-teste", Comando.IMPRIMIR_TESTE),
            Map.entry("--instalar", Comando.INSTALAR),
            Map.entry("--desinstalar", Comando.DESINSTALAR),
            Map.entry("--ajuda", Comando.AJUDA),
            Map.entry("--help", Comando.AJUDA),
            Map.entry("-h", Comando.AJUDA));
    private static final Set<String> COM_VALOR = Set.of(OPT_BACKEND, OPT_PORTA, OPT_DIR);
    private static final Set<String> FLAGS = Set.of(FLAG_SEM_BANDEJA, FLAG_VERBOSO);
    /** Quantos posicionais cada comando exige. */
    private static final Map<Comando, Integer> POSICIONAIS = Map.of(
            Comando.PAREAR, 1, Comando.GERAR_PDF_TESTE, 1, Comando.IMPRIMIR_TESTE, 2);

    static final String USO = """
            Uso: AgroEase-Agente-Impressao[-cli] [comando] [opções]
              (sem comando)                          sobe o agente (bandeja/janela se houver tela)
              --parear <codigo>                      pareia este computador com a loja (código gerado no painel)
              --desparear                            remove o pareamento deste computador
              --status                               pasta de dados, pareamento, backend, impressora
              --diagnostico                          checa Ed25519, javax.print, impressoras, portas, pasta, bandeja
              --versao                               versão do binário
              --gerar-pdf-teste <arquivo.pdf>        gera um cupom 80 mm de teste
              --imprimir-teste <impressora> <pdf>    imprime pelo motor do agente
              --instalar | --desinstalar             ativa/desativa "iniciar com o sistema" (também ativado ao parear)
            Opções: --backend-url <url>  --porta <n>  --dir-dados <pasta>  --sem-bandeja  --verboso
            """;

    static Argumentos parse(String[] args) {
        Map<String, String> opcoes = new LinkedHashMap<>();
        List<String> posicionais = new ArrayList<>();
        List<String> erros = new ArrayList<>();
        Comando comando = Comando.SERVIR;
        if (args.length > 0 && args[0].startsWith("-D")) {
            return new Argumentos(Comando.DEV, opcoes, List.of(args), erros);
        }
        boolean comandoDefinido = false;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (COMANDOS.containsKey(a)) {
                if (comandoDefinido) {
                    erros.add("só um comando por vez: '" + a + "' depois de '--" + comando.name().toLowerCase().replace('_', '-') + "'");
                } else {
                    comando = COMANDOS.get(a);
                    comandoDefinido = true;
                }
                continue;
            }
            if (a.startsWith("--")) {
                String nome = a.substring(2);
                int igual = nome.indexOf('=');
                String valorInline = null;
                if (igual > 0) {
                    valorInline = nome.substring(igual + 1);
                    nome = nome.substring(0, igual);
                }
                if (FLAGS.contains(nome)) {
                    opcoes.put(nome, "true");
                } else if (COM_VALOR.contains(nome)) {
                    String valor = valorInline;
                    if (valor == null) {
                        if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                            erros.add("--" + nome + " exige um valor");
                            continue;
                        }
                        valor = args[++i];
                    }
                    opcoes.put(nome, valor);
                } else {
                    erros.add("opção desconhecida: " + a);
                }
                continue;
            }
            posicionais.add(a);
        }
        int exigidos = POSICIONAIS.getOrDefault(comando, 0);
        if (posicionais.size() < exigidos) {
            erros.add("'" + args[0] + "' exige " + exigidos + " argumento(s)");
        } else if (posicionais.size() > exigidos) {
            erros.add("argumento inesperado: '" + posicionais.get(exigidos) + "'");
        }
        if (opcoes.containsKey(OPT_PORTA)) {
            try {
                int p = Integer.parseInt(opcoes.get(OPT_PORTA));
                if (p < 1 || p > 65535) {
                    erros.add("--porta fora de 1..65535");
                }
            } catch (NumberFormatException e) {
                erros.add("--porta deve ser um número");
            }
        }
        return new Argumentos(comando, Map.copyOf(opcoes), List.copyOf(posicionais), List.copyOf(erros));
    }

    boolean flag(String nome) {
        return "true".equals(opcoes.get(nome));
    }

    String opcao(String nome) {
        return opcoes.get(nome);
    }

    Integer porta() {
        return opcoes.containsKey(OPT_PORTA) ? Integer.valueOf(opcoes.get(OPT_PORTA)) : null;
    }
}

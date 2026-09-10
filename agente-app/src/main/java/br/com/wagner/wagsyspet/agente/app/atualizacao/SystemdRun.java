package br.com.wagner.wagsyspet.agente.app.atualizacao;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Monta {@code systemd-run --user --collect --unit=<prefixo>-<ts> [--setenv=…] <comando…>}: um processo lançado DIRETO de dentro de
 * uma unidade do systemd morre com ela (KillMode=control-group — provado no e2e do F6-L2: o agente relançado pelo atualizador foi
 * morto junto com a unidade do atualizador). Uma unidade transitória própria sobrevive. {@code systemd-run} não herda o ambiente:
 * repassamos só o que o agente lê ({@code AGROEASE_AGENTE_DIR}, {@code JAVA_TOOL_OPTIONS}) quando existir no chamador — em produção
 * não existem; servem ao CI e aos testes.
 */
final class SystemdRun {

    static final List<String> ENV_REPASSADAS = List.of("AGROEASE_AGENTE_DIR", "JAVA_TOOL_OPTIONS");

    private SystemdRun() {
    }

    static boolean sobSystemd(Map<String, String> env) {
        return env.containsKey("INVOCATION_ID");
    }

    static List<String> comando(String prefixoUnidade, Map<String, String> env, List<String> programaEArgs) {
        List<String> c = new ArrayList<>(List.of("systemd-run", "--user", "--collect", "--unit=" + prefixoUnidade + "-" + System.currentTimeMillis()));
        for (String v : ENV_REPASSADAS) {
            String valor = env.get(v);
            if (valor != null && !valor.isBlank()) {
                c.add("--setenv=" + v + "=" + valor);
            }
        }
        c.addAll(programaEArgs);
        return c;
    }
}

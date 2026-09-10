package br.com.wagner.wagsyspet.agente.protocolo.release;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Comparação {@code major.minor.patch} — PORTO da {@code service/impressao/VersaoSemantica} do backend (mesmos casos de teste,
 * mantidos em paridade). Sufixo de pré-release ({@code -SNAPSHOT}, {@code -rc1}) e prefixo {@code v} são ignorados; partes
 * ausentes valem 0; comparação NUMÉRICA (1.10 > 1.2). O self-update só age em {@link #maiorQue(String, String) estritamente maior}.
 */
public final class VersaoSemantica {

    private static final Pattern FORMA = Pattern.compile("^v?(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?(?:[-+].*)?$");

    private VersaoSemantica() {
    }

    /** {@code true} se a string é uma versão reconhecível (sem lançar). */
    public static boolean valida(String versao) {
        return versao != null && FORMA.matcher(versao.trim()).matches();
    }

    /** {@code versao >= minima}. */
    public static boolean atendeMinima(String versao, String minima) {
        return comparar(versao, minima) >= 0;
    }

    /** {@code candidata > atual} na base numérica — "1.0.0-SNAPSHOT" NÃO é maior que "1.0.0" (mesma base). */
    public static boolean maiorQue(String candidata, String atual) {
        return comparar(candidata, atual) > 0;
    }

    /** Lança {@link IllegalArgumentException} se qualquer uma não for uma versão. */
    public static int comparar(String a, String b) {
        int[] va = partes(a);
        int[] vb = partes(b);
        for (int i = 0; i < 3; i++) {
            int c = Integer.compare(va[i], vb[i]);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    private static int[] partes(String versao) {
        if (versao == null) {
            throw new IllegalArgumentException("versão nula");
        }
        Matcher m = FORMA.matcher(versao.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException("versão inválida: '" + versao + "'");
        }
        return new int[] {
            Integer.parseInt(m.group(1)),
            m.group(2) == null ? 0 : Integer.parseInt(m.group(2)),
            m.group(3) == null ? 0 : Integer.parseInt(m.group(3)),
        };
    }
}

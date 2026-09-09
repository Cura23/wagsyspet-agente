package br.com.wagner.wagsyspet.agente.core;

import java.util.regex.Pattern;

/**
 * Versão ÚNICA do binário (plano F3 D1). Fonte de verdade = {@code Implementation-Version} do MANIFEST do jar do
 * agente ({@code ${project.version}} do Maven, com {@code ${revision}} na raiz); em desenvolvimento ({@code exec:java}
 * sem jar) vale {@code -Dagente.versao}. Sem nenhuma das duas o processo <b>falha rápido</b>: anunciar {@code "dev"}
 * fazia o backend responder 426 no pareamento e o PWA fechar "desatualizado" — o binário nascia quebrado sem aviso.
 *
 * <p>O formato é o que backend ({@code VersaoSemantica}) e PWA ({@code versaoSemantica.ts}) sabem comparar:
 * {@code X.Y.Z} com sufixo opcional {@code -algo} (ignorado na comparação — {@code 1.0.0-SNAPSHOT} ≡ {@code 1.0.0}).</p>
 */
public final class VersaoDoBinario {

    /** Propriedade de sistema usada quando não há MANIFEST (dev). */
    public static final String PROPRIEDADE = "agente.versao";

    private static final Pattern FORMATO = Pattern.compile("^\\d+\\.\\d+\\.\\d+(-[0-9A-Za-z.]+(-[0-9A-Za-z.]+)*)?$");

    private VersaoDoBinario() {
    }

    /** Lê o MANIFEST do jar que contém {@code ancora}; sem ele, a propriedade {@code propriedade}. */
    public static String doClasspath(Class<?> ancora, String propriedade) {
        Package p = ancora.getPackage();
        String manifest = p == null ? null : p.getImplementationVersion();
        return resolver(manifest, System.getProperty(propriedade));
    }

    /** Atalho com a propriedade padrão {@link #PROPRIEDADE}. */
    public static String doClasspath(Class<?> ancora) {
        return doClasspath(ancora, PROPRIEDADE);
    }

    /**
     * @param implementationVersion valor do MANIFEST (pode ser nulo/branco)
     * @param propriedade           valor de {@code -Dagente.versao} (pode ser nulo/branco)
     * @throws IllegalStateException sem fonte ou com formato que backend/PWA não comparam
     */
    public static String resolver(String implementationVersion, String propriedade) {
        String v = primeiroNaoBranco(implementationVersion, propriedade);
        if (v == null) {
            throw new IllegalStateException("versão do binário desconhecida: sem Implementation-Version no MANIFEST e sem -D"
                    + PROPRIEDADE + " — build inválido (nunca anunciar 'dev')");
        }
        if (!FORMATO.matcher(v).matches()) {
            throw new IllegalStateException("versão do binário '" + v + "' fora do formato X.Y.Z[-sufixo] que backend e PWA comparam");
        }
        return v;
    }

    private static String primeiroNaoBranco(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        if (b != null && !b.isBlank()) {
            return b;
        }
        return null;
    }
}

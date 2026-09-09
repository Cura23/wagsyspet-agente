package br.com.wagner.wagsyspet.agente.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F3-L0 — versão ÚNICA do binário (plano F3 D1). O agente anuncia a versão no {@code hello_ok} e no {@code /parear};
 * backend e PWA comparam com a mesma regra semântica (sufixo {@code -xxx} ignorado). Antes da F3 havia TRÊS fontes
 * ({@code 0.1.0-spike}, {@code 0.1.0-SNAPSHOT}, {@code 1.0.0}) e um fallback {@code "dev"} — que o backend recusa com 426.
 */
@DisplayName("VersaoDoBinario — uma fonte só, nunca 'dev'")
class VersaoDoBinarioTest {

    @Test
    @DisplayName("Implementation-Version do MANIFEST vence a propriedade de sistema")
    void manifestVence() {
        assertThat(VersaoDoBinario.resolver("1.2.3", "9.9.9")).isEqualTo("1.2.3");
    }

    @Test
    @DisplayName("sem MANIFEST (exec:java em dev) usa -Dagente.versao")
    void propriedadeComoFallback() {
        assertThat(VersaoDoBinario.resolver(null, "1.0.0-dev")).isEqualTo("1.0.0-dev");
        assertThat(VersaoDoBinario.resolver("  ", "1.0.0-SNAPSHOT")).isEqualTo("1.0.0-SNAPSHOT");
    }

    @Test
    @DisplayName("sem nenhuma fonte → falha RÁPIDO (build inválido), nunca anuncia 'dev'")
    void semFonteLanca() {
        assertThatThrownBy(() -> VersaoDoBinario.resolver(null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("versão do binário");
        assertThatThrownBy(() -> VersaoDoBinario.resolver("", ""))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest(name = "\"{0}\" é recusada")
    @ValueSource(strings = {"dev", "1.0", "v1.0.0", "1.0.0 ", "abc", "1.0.0-", "0.1.0-spike "})
    @DisplayName("formato fora de X.Y.Z[-sufixo] → falha (é o que backend/PWA sabem comparar)")
    void formatoInvalidoLanca(String v) {
        assertThatThrownBy(() -> VersaoDoBinario.resolver(v, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(v.trim());
    }

    @ParameterizedTest(name = "\"{0}\" é aceita")
    @ValueSource(strings = {"1.0.0", "1.0.0-SNAPSHOT", "1.0.0-rc1", "10.20.30", "1.0.0-dev.2"})
    void formatoValidoPassa(String v) {
        assertThat(VersaoDoBinario.resolver(v, null)).isEqualTo(v);
    }

    @Test
    @DisplayName("lendo do classpath de teste (sem jar/MANIFEST) e sem propriedade → lança; com propriedade → devolve")
    void doClasspath() {
        String prop = "agente.versao.teste." + System.nanoTime();
        assertThatThrownBy(() -> VersaoDoBinario.doClasspath(VersaoDoBinarioTest.class, prop))
                .isInstanceOf(IllegalStateException.class);
        System.setProperty(prop, "2.0.0-teste");
        try {
            assertThat(VersaoDoBinario.doClasspath(VersaoDoBinarioTest.class, prop)).isEqualTo("2.0.0-teste");
        } finally {
            System.clearProperty(prop);
        }
    }
}

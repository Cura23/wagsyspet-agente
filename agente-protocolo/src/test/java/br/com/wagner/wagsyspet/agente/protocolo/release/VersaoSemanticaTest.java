package br.com.wagner.wagsyspet.agente.protocolo.release;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Porto da VersaoSemantica do backend (service/impressao) — os casos de paridade são os MESMOS do backend. */
@DisplayName("VersaoSemantica (agente) — paridade com o backend + 'estritamente maior' do self-update")
class VersaoSemanticaTest {

    @ParameterizedTest(name = "{0} atende mínima {1} → {2}")
    @CsvSource({
        "1.0.0, 1.0.0, true",
        "1.0.1, 1.0.0, true",
        "1.2.0, 1.10.0, false",
        "1.10.0, 1.2.0, true",
        "2.0.0, 1.99.99, true",
        "0.9.9, 1.0.0, false",
        "1.0.0-SNAPSHOT, 1.0.0, true",
        "1.0, 1.0.0, true",
        "1, 1.0.0, true",
        "v1.0.0, 1.0.0, true",
    })
    void atendeMinima(String versao, String minima, boolean esperado) {
        assertThat(VersaoSemantica.atendeMinima(versao, minima)).isEqualTo(esperado);
    }

    @ParameterizedTest(name = "{0} é maior que {1}? {2}")
    @CsvSource({
        "1.0.1, 1.0.0, true",
        "1.1.0, 1.0.9, true",
        "2.0.0, 1.99.99, true",
        "1.0.0, 1.0.0, false",
        "1.0.0-SNAPSHOT, 1.0.0, false",   // sufixo ignorado: mesma base NÃO é maior (não atualiza para 'a mesma')
        "1.1.0, 1.1.0-rc1, false",        // idem no outro sentido
        "1.0.0, 1.0.1, false",
        "1.2.0, 1.10.0, false",
    })
    void maiorQue(String candidata, String atual, boolean esperado) {
        assertThat(VersaoSemantica.maiorQue(candidata, atual)).isEqualTo(esperado);
    }

    @Test
    @DisplayName("lixo (vazio, letras, null) → IllegalArgumentException; valida() nunca lança")
    void lixo() {
        assertThatThrownBy(() -> VersaoSemantica.atendeMinima("", "1.0.0")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VersaoSemantica.atendeMinima("abc", "1.0.0")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VersaoSemantica.atendeMinima(null, "1.0.0")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VersaoSemantica.maiorQue("1.0.0", "x")).isInstanceOf(IllegalArgumentException.class);
        assertThat(VersaoSemantica.valida(null)).isFalse();
        assertThat(VersaoSemantica.valida("1.0.0-SNAPSHOT")).isTrue();
        assertThat(VersaoSemantica.valida("v2")).isTrue();
        assertThat(VersaoSemantica.valida("1.0.0 ")).isTrue();
        assertThat(VersaoSemantica.valida("1..0")).isFalse();
    }
}

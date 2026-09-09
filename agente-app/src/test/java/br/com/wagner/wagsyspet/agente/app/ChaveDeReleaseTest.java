package br.com.wagner.wagsyspet.agente.app;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ChaveDeRelease — chave pública embutida consistente com o kid (gate do build)")
class ChaveDeReleaseTest {

    @Test
    @DisplayName("release.properties carrega, a chave é Ed25519 utilizável e o kid bate; assinatura aleatória é recusada")
    void embutida() {
        ChaveDeRelease c = ChaveDeRelease.embutida();
        assertThat(c.kid()).matches("[0-9a-f]{16}");
        assertThat(c.publica().getAlgorithm()).containsIgnoringCase("Ed");
        assertThat(c.verificar("{}".getBytes(StandardCharsets.UTF_8), new byte[64])).isFalse();
    }
}

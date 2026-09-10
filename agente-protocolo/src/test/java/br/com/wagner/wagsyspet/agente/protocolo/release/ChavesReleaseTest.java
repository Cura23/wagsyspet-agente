package br.com.wagner.wagsyspet.agente.protocolo.release;

import br.com.wagner.wagsyspet.agente.protocolo.ticket.ChavesTicket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Raiz de confiança do self-update: 1 chave ATUAL + 1 RESERVA (rotação sem reinstalar a frota — plano F6 D12). */
@DisplayName("ChavesRelease — chave atual + reserva, kid conferido no carregamento, busca por kid")
class ChavesReleaseTest {

    private static Properties props(KeyPair atual, KeyPair reserva) {
        Properties p = new Properties();
        p.setProperty("release.chave.publica", ChavesTicket.exportarPublica(atual.getPublic()));
        p.setProperty("release.kid", VerificadorAssinaturaRelease.kid(atual.getPublic()));
        if (reserva != null) {
            p.setProperty("release.chave.publica.reserva", ChavesTicket.exportarPublica(reserva.getPublic()));
            p.setProperty("release.kid.reserva", VerificadorAssinaturaRelease.kid(reserva.getPublic()));
        }
        return p;
    }

    @Test
    @DisplayName("carrega atual + reserva; porKid acha as duas; kid estranho → vazio")
    void duasChaves() {
        KeyPair a = ChavesTicket.gerar(), r = ChavesTicket.gerar();
        ChavesRelease c = ChavesRelease.de(props(a, r));
        assertThat(c.todas()).hasSize(2);
        assertThat(c.porKid(VerificadorAssinaturaRelease.kid(a.getPublic()))).isPresent();
        assertThat(c.porKid(VerificadorAssinaturaRelease.kid(r.getPublic()))).isPresent();
        assertThat(c.porKid("0000000000000000")).isEmpty();
        assertThat(c.kidAtual()).isEqualTo(VerificadorAssinaturaRelease.kid(a.getPublic()));
    }

    @Test
    @DisplayName("só a atual (sem reserva) continua válido — compatível com o release.properties da F3")
    void soAtual() {
        ChavesRelease c = ChavesRelease.de(props(ChavesTicket.gerar(), null));
        assertThat(c.todas()).hasSize(1);
    }

    @Test
    @DisplayName("kid que não corresponde à chave (atual ou reserva) → build inválido; chave ausente idem")
    void kidErrado() {
        KeyPair a = ChavesTicket.gerar(), r = ChavesTicket.gerar();
        Properties p = props(a, r);
        p.setProperty("release.kid.reserva", "deadbeefdeadbeef");
        assertThatThrownBy(() -> ChavesRelease.de(p)).isInstanceOf(IllegalStateException.class).hasMessageContaining("reserva");
        Properties q = props(a, null);
        q.setProperty("release.kid", "deadbeefdeadbeef");
        assertThatThrownBy(() -> ChavesRelease.de(q)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ChavesRelease.de(new Properties())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("o release.properties EMBUTIDO no agente carrega (atual + reserva reais, kids batendo)")
    void embutido() {
        ChavesRelease c = ChavesRelease.embutidas();
        assertThat(c.kidAtual()).isEqualTo("b36cf9634d29c4f7");
        assertThat(c.todas()).hasSize(2);
    }
}

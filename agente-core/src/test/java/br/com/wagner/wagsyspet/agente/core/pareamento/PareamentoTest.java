package br.com.wagner.wagsyspet.agente.core.pareamento;

import br.com.wagner.wagsyspet.agente.protocolo.ticket.ChavesTicket;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Plano F3 D14 — o que o agente aceita da resposta do {@code POST /parear} (parser ESTRITO, fail-closed). */
@DisplayName("Pareamento — resposta do backend validada campo a campo")
class PareamentoTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CHAVE = ChavesTicket.exportarPublica(ChavesTicket.gerar().getPublic());
    private static final String BACKEND = "https://wagsyspet-backend-production.up.railway.app";

    private static ObjectNode respostaValida() {
        ObjectNode n = JSON.createObjectNode();
        n.put("agenteId", "3f1c2b6e-0f1a-4a2b-9c3d-000000000001");
        n.put("lojaId", 7);
        n.put("credencial", "c".repeat(43));
        n.put("chavePublicaTicket", CHAVE);
        n.putArray("origensPermitidas").add("https://app.agroease.com.br").add("https://wagsyspet-frontend.vercel.app");
        n.put("portaSugerida", 28421);
        n.put("versaoMinima", "1.0.0");
        return n;
    }

    private static void assertRecusa(Consumer<ObjectNode> mutacao, String trecho) {
        ObjectNode n = respostaValida();
        mutacao.accept(n);
        assertThatThrownBy(() -> Pareamento.daResposta(n, BACKEND, Instant.EPOCH))
                .isInstanceOf(PareamentoException.class)
                .hasMessageContaining(trecho)
                .extracting(e -> ((PareamentoException) e).motivo()).isEqualTo(PareamentoException.Motivo.RESPOSTA_INVALIDA);
    }

    @Test
    @DisplayName("resposta válida → todos os campos + backendUrl + pareadoEm; chave pública importável")
    void valida() throws Exception {
        Pareamento p = Pareamento.daResposta(respostaValida(), BACKEND, Instant.parse("2026-09-09T12:00:00Z"));
        assertThat(p.agenteId()).isEqualTo("3f1c2b6e-0f1a-4a2b-9c3d-000000000001");
        assertThat(p.lojaId()).isEqualTo(7L);
        assertThat(p.origensPermitidas()).containsExactly("https://app.agroease.com.br", "https://wagsyspet-frontend.vercel.app");
        assertThat(p.portaSugerida()).isEqualTo(28421);
        assertThat(p.versaoMinima()).isEqualTo("1.0.0");
        assertThat(p.backendUrl()).isEqualTo(BACKEND);
        assertThat(p.pareadoEm()).isEqualTo(Instant.parse("2026-09-09T12:00:00Z"));
        assertThat(p.chavePublica().getAlgorithm()).containsIgnoringCase("Ed");
    }

    @Test
    @DisplayName("agenteId ausente/fora do alfabeto → recusa (é a identidade do caixa nos tickets)")
    void agenteId() {
        assertRecusa(n -> n.remove("agenteId"), "agenteId");
        assertRecusa(n -> n.put("agenteId", "tem espaço"), "agenteId");
        assertRecusa(n -> n.put("agenteId", 123), "agenteId");
    }

    @Test
    @DisplayName("lojaId tem de ser número > 0")
    void lojaId() {
        assertRecusa(n -> n.put("lojaId", 0), "lojaId");
        assertRecusa(n -> n.put("lojaId", "7"), "lojaId");
        assertRecusa(n -> n.remove("lojaId"), "lojaId");
    }

    @Test
    @DisplayName("chave pública inválida → recusa (fail-fast, não no 1º ticket)")
    void chave() {
        assertRecusa(n -> n.put("chavePublicaTicket", "AAAA"), "chave");
        assertRecusa(n -> n.remove("chavePublicaTicket"), "chave");
    }

    @Test
    @DisplayName("origensPermitidas vazia ou com lixo → recusa (fail-closed: sem Origin não há agente)")
    void origens() {
        assertRecusa(n -> n.putArray("origensPermitidas"), "origensPermitidas");
        assertRecusa(n -> n.remove("origensPermitidas"), "origensPermitidas");
        assertRecusa(n -> n.putArray("origensPermitidas").add("ftp://x"), "origensPermitidas");
        assertRecusa(n -> n.putArray("origensPermitidas").add("https://app.agroease.com.br/"), "origensPermitidas"); // barra final = Origin nunca casa
    }

    @Test
    @DisplayName("portaSugerida fora de 1..65535 → recusa; versaoMinima e credencial são opcionais")
    void opcionais() throws Exception {
        assertRecusa(n -> n.put("portaSugerida", 0), "portaSugerida");
        ObjectNode n = respostaValida();
        n.remove("versaoMinima");
        n.remove("credencial");
        Pareamento p = Pareamento.daResposta(n, BACKEND, Instant.EPOCH);
        assertThat(p.versaoMinima()).isNull();
        assertThat(p.credencial()).isNull();
    }

    @Test
    @DisplayName("round-trip JSON do arquivo (o que o cofre grava e relê)")
    void roundTrip() throws Exception {
        Pareamento p = Pareamento.daResposta(respostaValida(), BACKEND, Instant.parse("2026-09-09T12:00:00Z"));
        Pareamento relido = Pareamento.deJson(p.paraJson());
        assertThat(relido).isEqualTo(p);
        assertThat(relido.origensPermitidas()).isEqualTo(List.of("https://app.agroease.com.br", "https://wagsyspet-frontend.vercel.app"));
    }
}

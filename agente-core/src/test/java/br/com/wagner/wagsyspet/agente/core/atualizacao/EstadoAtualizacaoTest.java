package br.com.wagner.wagsyspet.agente.core.atualizacao;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** estado.json da atualização: única fonte entre desktop, CLI e atualizador; tolerante a lixo; relido por mtime. */
@DisplayName("EstadoAtualizacao — round-trip, vazio por padrão, arquivo corrompido posto de lado, releitura por mtime")
class EstadoAtualizacaoTest {

    @Test
    void vazioRoundTrip(@TempDir Path dir) throws Exception {
        EstadoAtualizacao e = new EstadoAtualizacao(dir.resolve("atualizacao").resolve("estado.json"));
        assertThat(e.ler()).isEqualTo(EstadoAtualizacao.Estado.VAZIO);
        EstadoAtualizacao.Estado novo = EstadoAtualizacao.Estado.VAZIO
                .comVerificacao(Instant.parse("2026-09-10T12:00:00Z"), "W/\"etag1\"", "1.1.0")
                .comArtefatoBaixado(new EstadoAtualizacao.ArtefatoBaixado("1.1.0", dir.resolve("a.deb").toString(), "a".repeat(64)))
                .comEmAplicacao(new EstadoAtualizacao.EmAplicacao("1.1.0", "1.0.0", null, Instant.parse("2026-09-10T12:05:00Z")), 0);
        e.gravar(novo);
        EstadoAtualizacao.Estado lido = new EstadoAtualizacao(e.arquivo()).ler();
        assertThat(lido).isEqualTo(novo);
        assertThat(lido.emAplicacao()).isPresent();
        assertThat(lido.versaoDisponivel()).contains("1.1.0");
        assertThat(lido.etag()).contains("W/\"etag1\"");
    }

    @Test
    void corrompidoNaoDerruba(@TempDir Path dir) throws Exception {
        Path arq = dir.resolve("estado.json");
        Files.writeString(arq, "{lixo");
        EstadoAtualizacao e = new EstadoAtualizacao(arq);
        assertThat(e.ler()).isEqualTo(EstadoAtualizacao.Estado.VAZIO);
        assertThat(Files.exists(arq)).as("corrompido vai de lado, não é apagado em silêncio").isFalse();
        try (var s = Files.list(dir)) {
            assertThat(s.map(p -> p.getFileName().toString())).anyMatch(n -> n.startsWith("estado.json.corrompido"));
        }
    }

    @Test
    void relidoPorMtime(@TempDir Path dir) throws Exception {
        Path arq = dir.resolve("estado.json");
        EstadoAtualizacao a = new EstadoAtualizacao(arq);
        EstadoAtualizacao b = new EstadoAtualizacao(arq);
        a.gravar(EstadoAtualizacao.Estado.VAZIO.comVerificacao(Instant.parse("2026-09-10T12:00:00Z"), null, "1.2.0"));
        assertThat(b.ler().versaoDisponivel()).contains("1.2.0");
        Thread.sleep(20);
        b.gravar(b.ler().comTentativasBoot(2));
        assertThat(a.ler().tentativasBoot()).isEqualTo(2);
    }

    @Test
    void camposDesconhecidosSobrevivem(@TempDir Path dir) throws Exception {
        Path arq = dir.resolve("estado.json");
        Files.writeString(arq, "{\"tentativasBoot\":1,\"novidadeFutura\":{\"x\":1}}");
        EstadoAtualizacao e = new EstadoAtualizacao(arq);
        assertThat(e.ler().tentativasBoot()).isEqualTo(1);
    }
}

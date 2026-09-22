package br.com.wagner.wagsyspet.agente.app;

import br.com.wagner.wagsyspet.agente.core.atualizacao.EstadoAtualizacao;
import br.com.wagner.wagsyspet.agente.core.atualizacao.GerenteAtualizacao;
import br.com.wagner.wagsyspet.agente.core.pareamento.DiretoriosDoAgente;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lacuna 2 da auditoria de completude da F6: o {@code --diagnostico} (o que o suporte pede ao lojista) não dizia NADA sobre a
 * atualização automática — o estado só existia no {@code atualizacao/estado.json}. A linha é montada por um helper puro (o
 * {@code rodar()} inteiro fala com o backend real e enumera impressoras, inviável em unitário) e SÓ LÊ: nunca cria a pasta, nunca
 * consulta o manifesto.
 */
@DisplayName("Diagnostico — linha 'atualização automática' a partir do estado.json, só leitura, nunca derruba o diagnóstico")
class DiagnosticoTest {

    private static final Instant AGORA = Instant.parse("2026-09-22T12:00:00Z");
    private static final Clock RELOGIO = Clock.fixed(AGORA, ZoneOffset.UTC);

    @Test
    @DisplayName("instalação nova (sem estado.json) → 'ainda não verificada' e a pasta atualizacao/ NÃO é criada pelo diagnóstico")
    void semEstado(@TempDir Path tmp) {
        DiretoriosDoAgente dirs = new DiretoriosDoAgente(tmp);
        String linha = Diagnostico.linhaAtualizacao(dirs, RELOGIO, "1.1.0");
        assertThat(linha).contains("ainda não verificada");
        assertThat(dirs.atualizacao()).doesNotExist();
    }

    @Test
    @DisplayName("estado real (verificado há 40 min, instalador baixado) → a linha diz a versão pronta; versão esgotada só aparece enquanto a instalada é menor")
    void comEstado(@TempDir Path tmp) throws Exception {
        DiretoriosDoAgente dirs = new DiretoriosDoAgente(tmp);
        EstadoAtualizacao estado = new EstadoAtualizacao(dirs.atualizacao().resolve(GerenteAtualizacao.ARQUIVO_ESTADO));
        estado.gravar(EstadoAtualizacao.Estado.VAZIO.comVerificacao(AGORA.minus(Duration.ofMinutes(40)), "\"e1\"", "1.2.0")
                .comArtefatoBaixado(new EstadoAtualizacao.ArtefatoBaixado("1.2.0", tmp.resolve("i.bin").toString(), "00")));
        assertThat(Diagnostico.linhaAtualizacao(dirs, RELOGIO, "1.1.0")).contains("1.2.0").contains("baixada");

        estado.gravar(EstadoAtualizacao.Estado.VAZIO.comVerificacao(AGORA.minus(Duration.ofMinutes(40)), null, null)
                .comRecusada(new EstadoAtualizacao.Recusada("1.2.0", AGORA.minus(Duration.ofDays(2)), GerenteAtualizacao.TENTATIVAS_POR_VERSAO)));
        assertThat(Diagnostico.linhaAtualizacao(dirs, RELOGIO, "1.1.0")).contains("1.2.0").contains("não instalou");
        assertThat(Diagnostico.linhaAtualizacao(dirs, RELOGIO, "1.2.0")).as("instalada à mão: em dia").doesNotContain("não instalou").contains("há 40 min");
    }

    @Test
    @DisplayName("estado.json corrompido → não derruba o diagnóstico (segue como 'ainda não verificada'; o arquivo vai de lado como faz o agente)")
    void corrompidoNaoDerruba(@TempDir Path tmp) throws Exception {
        DiretoriosDoAgente dirs = new DiretoriosDoAgente(tmp);
        Files.createDirectories(dirs.atualizacao());
        Files.writeString(dirs.atualizacao().resolve(GerenteAtualizacao.ARQUIVO_ESTADO), "{lixo");
        assertThat(Diagnostico.linhaAtualizacao(dirs, RELOGIO, "1.1.0")).contains("ainda não verificada");
    }
}

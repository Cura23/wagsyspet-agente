package br.com.wagner.wagsyspet.agente.app.autostart;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ComandoExterno.real — drena a saída ENQUANTO espera o processo")
class ComandoExternoTest {

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "usa /bin/sh para gerar a saída grande; o comportamento é do Java, igual nos 3 SOs")
    @DisplayName("saída maior que o buffer do pipe (4 KB no Windows, 64 KB no Linux) não trava: ler só depois do waitFor deixava o filho bloqueado no write até o prazo — o 'schtasks /query /xml' viraria 'sem tarefa' e o pausar() do keepalive um no-op (adversarial L3 r2)")
    void saidaGrandeNaoTrava() throws Exception {
        long t0 = System.nanoTime();
        ComandoExterno.Saida s = ComandoExterno.real(Duration.ofSeconds(8)).executar(List.of("/bin/sh", "-c", "head -c 300000 /dev/zero | tr '\\0' 'a'; exit 7"));
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(6));
        assertThat(s.exit()).isEqualTo(7);
        assertThat(s.texto()).hasSize(300000);
    }
}

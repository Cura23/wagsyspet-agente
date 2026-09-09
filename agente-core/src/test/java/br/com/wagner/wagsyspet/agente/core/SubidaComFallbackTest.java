package br.com.wagner.wagsyspet.agente.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Plano F3 D20 — 28421 ocupada → UMA tentativa na próxima porta com instância nova; todas ocupadas → exit 4 (sem loop). */
@DisplayName("SubidaComFallback — portas em ordem, instância nova por tentativa, nunca loop")
class SubidaComFallbackTest {

    private static final InfoAgente INFO = new InfoAgente("1.0.0-teste", 1, "uid");
    private static final Set<String> ORIGINS = Set.of("https://app.agroease.com.br");

    /** Duas portas livres consecutivas (abre/fecha ServerSocket em 127.0.0.1). */
    private static int[] duasPortasLivres() throws IOException {
        for (int tentativa = 0; tentativa < 50; tentativa++) {
            int a;
            try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                a = s.getLocalPort();
            }
            try (ServerSocket s = new ServerSocket(a + 1, 1, InetAddress.getLoopbackAddress())) {
                return new int[]{a, a + 1};
            } catch (IOException ocupada) {
                // tenta outro par
            }
        }
        throw new IllegalStateException("não achei duas portas livres consecutivas");
    }

    @Test
    @DisplayName("1ª porta livre → sobe nela, fábrica chamada 1×")
    void primeiraLivre() throws Exception {
        int[] portas = duasPortasLivres();
        AtomicInteger criados = new AtomicInteger();
        ServidorAgente s = SubidaComFallback.subir(p -> { criados.incrementAndGet(); return new ServidorAgente(p, ORIGINS, INFO); }, portas, Duration.ofSeconds(10));
        try {
            assertThat(s.getPort()).isEqualTo(portas[0]);
            assertThat(criados.get()).isEqualTo(1);
        } finally {
            s.stop(1000);
        }
    }

    @Test
    @DisplayName("1ª ocupada por OUTRO processo → instância NOVA na 2ª; ambas ocupadas → NenhumaPortaLivreException após exatamente 2 tentativas")
    void fallbackUmaVez() throws Exception {
        int[] portas = duasPortasLivres();
        try (ServerSocket ocupante = new ServerSocket(portas[0], 1, InetAddress.getLoopbackAddress())) {
            AtomicInteger criados = new AtomicInteger();
            ServidorAgente s = SubidaComFallback.subir(p -> { criados.incrementAndGet(); return new ServidorAgente(p, ORIGINS, INFO); }, portas, Duration.ofSeconds(10));
            try {
                assertThat(s.getPort()).isEqualTo(portas[1]);
                assertThat(criados.get()).as("uma instância por porta tentada").isEqualTo(2);
            } finally {
                s.stop(1000);
            }
            // agora as duas ocupadas (a 2ª por outro processo também)
            try (ServerSocket ocupante2 = new ServerSocket(portas[1], 1, InetAddress.getLoopbackAddress())) {
                AtomicInteger criados2 = new AtomicInteger();
                assertThatThrownBy(() -> SubidaComFallback.subir(p -> { criados2.incrementAndGet(); return new ServidorAgente(p, ORIGINS, INFO); }, portas, Duration.ofSeconds(10)))
                        .isInstanceOf(SubidaComFallback.NenhumaPortaLivreException.class)
                        .hasMessageContaining(String.valueOf(portas[0]))
                        .hasMessageContaining(String.valueOf(portas[1]));
                assertThat(criados2.get()).as("sem loop: exatamente 1 tentativa por porta").isEqualTo(2);
                assertThat(ocupante2.isBound()).isTrue();
            }
        }
    }

    @Test
    @DisplayName("falha que NÃO é porta ocupada (onStart lança) → propaga sem tentar a próxima porta")
    void outraFalhaNaoAvanca() throws Exception {
        int[] portas = duasPortasLivres();
        AtomicInteger criados = new AtomicInteger();
        assertThatThrownBy(() -> SubidaComFallback.subir(p -> {
            criados.incrementAndGet();
            return new ServidorAgente(p, ORIGINS, INFO) {
                @Override
                protected void aoIniciar() {
                    throw new IllegalStateException("boom");
                }
            };
        }, portas, Duration.ofSeconds(10))).isInstanceOf(IOException.class).hasMessageContaining("boom");
        assertThat(criados.get()).isEqualTo(1);
    }
}

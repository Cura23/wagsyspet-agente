package br.com.wagner.wagsyspet.agente.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Porteiro do handshake WebSocket: só a PÁGINA do WagSysPet (Origin exata na allowlist) e só via loopback
 * (Host = 127.0.0.1/localhost na porta do agente) podem conectar. Qualquer outro site aberto no mesmo
 * navegador — ou um DNS rebinding — recebe 403 ANTES do upgrade. Regras puras, sem socket.
 */
@DisplayName("PorteiroHandshake — Origin allowlist + Host loopback")
class PorteiroHandshakeTest {

    private static final int PORTA = 28421;
    private final PorteiroHandshake porteiro = new PorteiroHandshake(
            Set.of("https://wagsyspet-frontend.vercel.app", "http://localhost:5173"), PORTA);

    @Nested
    @DisplayName("Origin")
    class Origin {
        @Test
        @DisplayName("Origin exata da allowlist + Host loopback → permitido")
        void permiteOriginExata() {
            var d = porteiro.avaliar("https://wagsyspet-frontend.vercel.app", "127.0.0.1:" + PORTA);
            assertThat(d.permitido()).isTrue();
        }

        @Test
        @DisplayName("Origin de dev (localhost:5173) na allowlist → permitido")
        void permiteOriginDev() {
            assertThat(porteiro.avaliar("http://localhost:5173", "127.0.0.1:" + PORTA).permitido()).isTrue();
        }

        @Test
        @DisplayName("Origin ausente → 403 (cliente não-navegador ou header removido)")
        void recusaSemOrigin() {
            var d = porteiro.avaliar(null, "127.0.0.1:" + PORTA);
            assertThat(d.permitido()).isFalse();
            assertThat(d.status()).isEqualTo(403);
            assertThat(d.motivo()).containsIgnoringCase("origin");
        }

        @ParameterizedTest(name = "recusa {0}")
        @ValueSource(strings = {
            "https://site-malicioso.com",
            "https://wagsyspet-frontend.vercel.app.evil.com",   // sufixo: prefixo idêntico
            "https://evil.com/wagsyspet-frontend.vercel.app",   // path enganoso
            "http://wagsyspet-frontend.vercel.app",             // scheme diferente
            "https://wagsyspet-frontend.vercel.app:8443",       // porta diferente
            "https://WAGSYSPET-FRONTEND.VERCEL.APP",            // casing: exigimos igualdade exata
            "null",                                             // Origin literal 'null' (sandbox/file://)
            "",
        })
        void recusaOriginForaDaAllowlist(String origin) {
            var d = porteiro.avaliar(origin, "127.0.0.1:" + PORTA);
            assertThat(d.permitido()).as("origin %s", origin).isFalse();
            assertThat(d.status()).isEqualTo(403);
        }
    }

    @Nested
    @DisplayName("Host (anti DNS rebinding)")
    class Host {
        @ParameterizedTest(name = "aceita Host {0}")
        @ValueSource(strings = {"127.0.0.1:28421", "localhost:28421", "[::1]:28421"})
        void aceitaLoopback(String host) {
            assertThat(porteiro.avaliar("https://wagsyspet-frontend.vercel.app", host).permitido()).isTrue();
        }

        @ParameterizedTest(name = "recusa Host {0}")
        @ValueSource(strings = {
            "agente.evil.com:28421",   // DNS rebinding: nome público resolvendo p/ 127.0.0.1
            "127.0.0.1:9999",          // porta errada
            "192.168.0.10:28421",      // IP da LAN (agente só é loopback)
            "127.0.0.1",               // sem porta
            "",
        })
        void recusaHostNaoLoopback(String host) {
            var d = porteiro.avaliar("https://wagsyspet-frontend.vercel.app", host);
            assertThat(d.permitido()).as("host %s", host).isFalse();
            assertThat(d.status()).isEqualTo(403);
            assertThat(d.motivo()).containsIgnoringCase("host");
        }

        @Test
        @DisplayName("Host ausente → 403")
        void recusaSemHost() {
            assertThat(porteiro.avaliar("https://wagsyspet-frontend.vercel.app", null).permitido()).isFalse();
        }
    }
}

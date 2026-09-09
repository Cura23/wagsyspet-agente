package br.com.wagner.wagsyspet.agente.app;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.logging.Handler;
import java.util.logging.LogManager;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/** Plano F3 D19/D20 — log em arquivo rotativo e instância única. */
@DisplayName("Casca — log em arquivo e trava de instância")
class CascaTest {

    @Nested
    @DisplayName("LogDoAgente")
    class Log {
        @Test
        @DisplayName("configura j.u.l → arquivo agente-0.log na pasta; SLF4J chega lá; sem console quando pedido; bibliotecas tagarelas em WARNING")
        void arquivo(@TempDir Path tmp) throws Exception {
            Path logs = tmp.resolve("dados/logs");
            Path ativo = LogDoAgente.configurar(logs, false, false);
            try {
                assertThat(ativo).isEqualTo(logs.resolve("agente-0.log"));
                LoggerFactory.getLogger("teste.casca").info("linha de teste {}", 42);
                LoggerFactory.getLogger("teste.casca").debug("não deve aparecer (INFO)");
                LoggerFactory.getLogger("org.java_websocket.x").info("ruído da lib não deve aparecer");
                for (Handler h : Logger.getLogger("").getHandlers()) {
                    h.flush();
                }
                String conteudo = Files.readString(ativo);
                assertThat(conteudo).contains("INFO ").contains("teste.casca - linha de teste 42");
                assertThat(conteudo).doesNotContain("não deve aparecer").doesNotContain("ruído da lib");
                assertThat(Logger.getLogger("").getHandlers()).as("sem ConsoleHandler").hasSize(1);
            } finally {
                LogManager.getLogManager().reset();
            }
        }

        @Test
        @DisplayName("verboso → FINE passa; console → 2 handlers")
        void verboso(@TempDir Path tmp) throws Exception {
            Path ativo = LogDoAgente.configurar(tmp, true, true);
            try {
                LoggerFactory.getLogger("teste.casca").debug("detalhe fino");
                for (Handler h : Logger.getLogger("").getHandlers()) {
                    h.flush();
                }
                assertThat(Files.readString(ativo)).contains("detalhe fino");
                assertThat(Logger.getLogger("").getHandlers()).hasSize(2);
            } finally {
                LogManager.getLogManager().reset();
            }
        }
    }

    @Nested
    @DisplayName("TravaDeInstancia")
    class Trava {
        @Test
        @DisplayName("1ª tentar() segura; 2ª (mesmo arquivo) volta vazia; depois de close() volta a segurar; arquivo criado na pasta")
        void unica(@TempDir Path tmp) throws Exception {
            Path lock = tmp.resolve("dados/agente.lock");
            Optional<TravaDeInstancia> primeira = TravaDeInstancia.tentar(lock);
            assertThat(primeira).isPresent();
            assertThat(Files.exists(lock)).isTrue();
            assertThat(TravaDeInstancia.tentar(lock)).as("segunda instância").isEmpty();
            primeira.get().close();
            Optional<TravaDeInstancia> depois = TravaDeInstancia.tentar(lock);
            assertThat(depois).isPresent();
            depois.get().close();
        }
    }
}
